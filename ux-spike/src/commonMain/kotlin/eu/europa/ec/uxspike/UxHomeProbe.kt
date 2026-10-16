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
import eu.europa.ec.uxspike.resources.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Script-name prefix of the M5d probe (`--ux-spike gallery-home[-<ui state>]`), checked before the template gallery. */
const val UX_HOME_PROBE_PREFIX = "gallery-home"

/** PROVISIONAL (M5d, OR-1): the one-state fragment bundles of the Home probe live here, not in `:ux-contract`. */
private const val HOME_PROBE_FLOW_ID = "FLW-home-probe"

/**
 * The Home-screen probe: a one-screen fragment bundle (`files/home-probe/home-<name>.bundle.json`, from
 * `spike-evidence/M5d`) hosted by the generic [FlowHost] with no services. It measures what the interpreter and
 * the registry lack for a real screen; a missing element or template draws its tagged placeholder. Debug only, no
 * screen-specific code: the name picks a resource, nothing is named here.
 */
@Composable
fun UxHomeProbe(
    script: String,
    platform: FlowPlatform,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
) {
    val name = script.removePrefix(UX_HOME_PROBE_PREFIX).trimStart('-').ifEmpty { "default" }
    val bundle by produceState<Result<FlowBundle>?>(null, name) { value = runCatching { loadHomeProbe(name) } }
    val loaded = bundle
    when {
        loaded == null -> Unit
        loaded.isFailure -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            BasicText("UX-SPIKE: ${loaded.exceptionOrNull()?.message}", Modifier.testTag(UX_FLOW_ERROR_TAG))
        }
        else -> {
            val b = loaded.getOrThrow()
            FlowHost(
                bundle = b,
                port = ServicePort { service, _ -> error("the Home probe has no services ($service)") },
                entry = "default",
                launch = JsonObject(emptyMap()),
                platform = platform,
                backHandler = backHandler,
                onExit = { },
                screens = remember(b) { uxSpikeScreens(b) },
                log = UxSpikeLog,
            )
        }
    }
}

private suspend fun loadHomeProbe(name: String): FlowBundle {
    val bytes = Res.readBytes("files/home-probe/home-$name.bundle.json")
    val hash = ((Json.parseToJsonElement(bytes.decodeToString()) as JsonObject)["spec_hash"] as JsonPrimitive).content
    return when (val r = FlowBundle.load(bytes, HOME_PROBE_FLOW_ID, hash, hash, allowProvisional = true)) {
        is LoadResult.Loaded -> r.bundle
        is LoadResult.Refused -> error("home probe '$name' refused: ${r.reason}")
    }
}
