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
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.LoadResult
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.engine.ServicePort
import eu.europa.ec.ux.runtime.host.FlowHost
import eu.europa.ec.ux.runtime.screen.CopyProvider
import eu.europa.ec.ux.theme.UxSpecResources
import eu.europa.ec.ux.theme.generated.UxBrandConfig
import eu.europa.ec.ux.theme.generated.UxThemeSpec
import eu.europa.ec.uxspike.resources.Res
import kotlinx.serialization.json.JsonObject
import eu.europa.ec.ux.contract.resources.Res as ContractRes

/** Script-name prefix of the screen probe (`--ux-spike gallery-home[-<ui state>][.<brand>][.<mode>]`), checked before the template gallery. */
const val UX_HOME_PROBE_PREFIX = "gallery-home"

/** The probe's name as the export names its file, `<name>.probe.json` (the build copies the export's probes to `files/probes`). */
private const val PROBE_NAME = "home"

private const val PROBE_FLOW_ID = "probe"

/**
 * The screen probe (M5e, was the M5d fragment bundles): the export's screen probe (`<screen id>.probe.json`) for the screen the prefix names, hosted by
 * the generic [FlowHost] with no services. [ScreenProbe] builds the one-state bundle of the brand, ui state and mode the
 * script asks for, applying the brand's profile gating; the interpreter draws it from the exporter's data and the exported
 * copy. Debug only, no screen-specific code: the probe file and its ids are data.
 */
@Composable
fun UxHomeProbe(
    script: String,
    platform: FlowPlatform,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
) {
    val rest = script.removePrefix(UX_HOME_PROBE_PREFIX)
    val loaded by produceState<Result<FlowBundle>?>(null, rest) { value = runCatching { loadProbe(rest) } }
    val resources by produceState(UxSpecResources.NONE) { value = exportedSpecResources() }
    val copy by produceState<CopyProvider?>(null) { value = exportedCopy() }
    val bundle = loaded
    val strings = copy
    when {
        bundle == null || strings == null -> Unit
        bundle.isFailure -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            BasicText("UX-SPIKE: ${bundle.exceptionOrNull()?.message}", Modifier.testTag(UX_FLOW_ERROR_TAG))
        }
        else -> {
            val b = bundle.getOrThrow()
            FlowHost(
                bundle = b,
                port = ServicePort { service, _ -> error("the probe has no services ($service)") },
                entry = "default",
                launch = JsonObject(emptyMap()),
                platform = platform,
                backHandler = backHandler,
                onExit = { },
                screens = remember(b, resources) { uxSpikeScreens(strings, resources) },
                log = UxSpikeLog,
            )
        }
    }
}

private suspend fun loadProbe(rest: String): FlowBundle {
    val probe = ScreenProbe.parse(
        Res.readBytes("files/probes/$PROBE_NAME.probe.json").decodeToString(),
        ContractRes.readBytes("files/ux-library.json").decodeToString(),
    )
    val selection = ProbeSelection.parse(rest, probe, UxBrandConfig.brands.map { it.id }.toSet())
    UxSpikeLog.log("probe ${probe.screenId}: ui state ${selection.uiState}, brand ${selection.brand}, mode ${selection.mode}")
    val bytes = probe.bundle(selection.brand, selection.uiState, selection.mode, PROBE_FLOW_ID).encodeToByteArray()
    return when (val r = FlowBundle.load(bytes, PROBE_FLOW_ID, probe.specHash, UxThemeSpec.SPEC_HASH)) {
        is LoadResult.Loaded -> r.bundle
        is LoadResult.Refused -> error("probe '${selection.uiState}' refused: ${r.reason}")
    }
}
