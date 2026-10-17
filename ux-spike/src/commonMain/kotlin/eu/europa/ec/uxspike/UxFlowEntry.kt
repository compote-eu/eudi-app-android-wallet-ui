/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.uxspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.europa.ec.ux.contract.pres.PresCrossDeviceExit
import eu.europa.ec.ux.contract.pres.PresCrossDeviceSpec
import eu.europa.ec.ux.contract.resources.allDrawableResources
import eu.europa.ec.ux.contract.resources.allStringResources
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.LoadResult
import eu.europa.ec.ux.runtime.engine.FlowExit
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.engine.ServicePort
import eu.europa.ec.ux.runtime.host.FlowHost
import eu.europa.ec.ux.runtime.screen.CopyProvider
import eu.europa.ec.ux.runtime.screen.FlowScreens
import eu.europa.ec.ux.theme.UxAssets
import eu.europa.ec.ux.theme.UxComponentTokenTable
import eu.europa.ec.ux.theme.UxLibraryAssets
import eu.europa.ec.ux.theme.UxSpecResources
import eu.europa.ec.ux.theme.generated.UxBrandConfig
import eu.europa.ec.ux.theme.generated.UxThemeSpec
import eu.europa.ec.uxspike.resources.Res
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import eu.europa.ec.ux.contract.resources.Res as ContractRes

/** Test tag of the text shown when the bundle or the script cannot be loaded. */
const val UX_FLOW_ERROR_TAG = "ux-flow-error"

/** The spike's log sink: every line goes to stdout with the `UX-SPIKE:` prefix. */
val UxSpikeLog: FlowLog = FlowLog { println("UX-SPIKE: $it") }

/** What the entry needs before [FlowHost] can run. */
sealed interface UxSpikeFlow {
    data class Ready(
        val bundle: FlowBundle,
        val services: FakePresCrossDeviceServices,
        val copy: CopyProvider,
        val resources: UxSpecResources,
    ) : UxSpikeFlow {
        val port: ServicePort get() = services.port()
    }

    data class Failed(val reason: String) : UxSpikeFlow

    companion object {
        /** Script resources, `files/scripts/<name>.json`: the exported `ux-spec/fixtures/scripts` (build.gradle.kts). */
        fun scriptPath(name: String): String = "files/scripts/$name.json"

        /**
         * Loads the exported bundle with the spec-hash handshake (boundary §4): the bundle, the generated contract
         * (`PresCrossDeviceSpec.SPEC_HASH`) and the generated theme (`UxThemeSpec.SPEC_HASH`) must carry one hash, and a
         * provisional hash is refused (H7). This code exists only in debug builds (the hooks are debug-only).
         */
        suspend fun load(script: String, log: FlowLog = UxSpikeLog): UxSpikeFlow {
            val bytes = ContractRes.readBytes(PresCrossDeviceSpec.BUNDLE_PATH)
            val bundle = when (
                val r = FlowBundle.load(
                    bytes,
                    expectedFlowId = PresCrossDeviceSpec.FLOW_ID,
                    expectedSpecHash = PresCrossDeviceSpec.SPEC_HASH,
                    themeSpecHash = UxThemeSpec.SPEC_HASH,
                )
            ) {
                is LoadResult.Loaded -> r.bundle
                is LoadResult.Refused -> return Failed("bundle refused: ${r.reason}")
            }
            bundle.warnings.forEach { log.log("bundle warning: $it") }
            val text = runCatching { Res.readBytes(scriptPath(script)).decodeToString() }
                .getOrElse { return Failed("no script '$script' (${it.message})") }
            val parsed = FakeScript.parse(text)
            if (parsed.flowId != bundle.flowId) return Failed("script '$script' is for ${parsed.flowId}")
            if (parsed.specHash != null && parsed.specHash != bundle.specHash) {
                return Failed("script '$script' is for spec ${parsed.specHash}, the bundle is ${bundle.specHash}")
            }
            log.log("flow ${bundle.flowId} loaded (provisional=${bundle.provisional}), script ${parsed.script}")
            val services = FakePresCrossDeviceServices(FakeScriptPlayer(parsed) { log.log(it) })
            return Ready(bundle, services, exportedCopy(), exportedSpecResources())
        }
    }
}

/**
 * The exported copy (`ux-spec/resources/values/strings.xml`, boundary §1.8): every string resource of `:ux-contract`,
 * read once, keyed by resource name, as the runtime's [CopyProvider] wants it (it is synchronous).
 */
suspend fun exportedCopy(): CopyProvider = CopyProvider.of(ContractRes.allStringResources.mapValues { (_, r) -> getString(r) })

/**
 * The export's data files beside the bundle (V32, M6c): `ux-assets.json` (asset id to drawable per brand),
 * `ux-library.json` (which asset each element part draws) and `ux-tokens.json` (`comp.*` values), read once from
 * `:ux-contract`'s `Res`; a drawable is loaded by its resource name (`Res.allDrawableResources`).
 */
suspend fun exportedSpecResources(): UxSpecResources {
    suspend fun file(name: String) = ContractRes.readBytes("files/$name").decodeToString()
    val drawables = ContractRes.allDrawableResources
    return UxSpecResources(
        assets = UxAssets.parse(file("ux-assets.json")),
        library = UxLibraryAssets.parse(file("ux-library.json")),
        componentTokens = UxComponentTokenTable.parse(file("ux-tokens.json")),
        painter = { resource -> drawables[resource]?.let { painterResource(it) } },
    )
}

/** The context path a `to-help` exit carries (boundary Q10). */
private const val ERROR_CLASS_PATH = "/flow/error_class"

/**
 * The flow's exit as the contract's typed exit (`PresCrossDeviceExit.of`), so the hooks map exits to the app without
 * naming a state. Null for an abandoned flow with no final state.
 */
fun FlowExit.typed(): PresCrossDeviceExit? =
    PresCrossDeviceExit.of(state, (carries[ERROR_CLASS_PATH] as? JsonPrimitive)?.takeIf { it.isString }?.content)

/**
 * How the spike draws the flow's screens: the hand-written consent screen by its screen id ([PresHandWrittenScreens]),
 * every other screen interpreted from the bundle, with the exported copy, brands (`UxBrandConfig`) and data files.
 */
fun uxSpikeScreens(copy: CopyProvider, resources: UxSpecResources): FlowScreens = FlowScreens(
    copy = copy,
    custom = PresHandWrittenScreens,
    brands = UxBrandConfig.brands.associateBy { it.id },
    resources = resources,
)

/**
 * The body of the [UxFlowRoute] entry: loads the bundle and the script, then hosts the flow. Each hook passes its
 * platform back-handler adapter and maps the typed exit onto the app's navigation.
 */
@Composable
fun UxFlowEntry(
    route: UxFlowRoute,
    script: String,
    platform: FlowPlatform,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
    onExit: (PresCrossDeviceExit?) -> Unit,
) {
    if (script.startsWith(UX_HOME_PROBE_PREFIX)) {
        UxHomeProbe(script, platform, backHandler)
        return
    }
    if (script.startsWith(UX_GALLERY_PREFIX)) {
        UxGallery(UxGalleryPage.fromScript(script))
        return
    }
    val flow by produceState<UxSpikeFlow?>(null, route, script) {
        value = UxSpikeFlow.load(script).also { (it as? UxSpikeFlow.Failed)?.let { f -> UxSpikeLog.log(f.reason) } }
    }
    when (val f = flow) {
        null -> Unit
        is UxSpikeFlow.Failed -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            BasicText("UX-SPIKE: ${f.reason}", Modifier.testTag(UX_FLOW_ERROR_TAG))
        }
        is UxSpikeFlow.Ready -> FlowHost(
            bundle = f.bundle,
            port = f.port,
            entry = route.entry,
            launch = route.launch?.let { JsonObject(mapOf("uri" to JsonPrimitive(it))) } ?: JsonObject(emptyMap()),
            platform = platform,
            backHandler = backHandler,
            onExit = { exit -> onExit(exit?.typed()) },
            screens = remember(f.bundle) { uxSpikeScreens(f.copy, f.resources) },
            log = UxSpikeLog,
        )
    }
}
