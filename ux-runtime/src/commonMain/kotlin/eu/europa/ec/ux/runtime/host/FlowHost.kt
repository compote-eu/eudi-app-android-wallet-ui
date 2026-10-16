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

package eu.europa.ec.ux.runtime.host

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.engine.FlowExit
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.engine.FlowStatus
import eu.europa.ec.ux.runtime.engine.ServicePort
import kotlinx.serialization.json.JsonObject

/**
 * Hosts one flow inside one Nav3 entry (SPIKE-PLAN §1.5). The flow's states are not Nav3 entries: they are rendered
 * here, one at a time, with [AnimatedContent].
 *
 * Back: this is the only place the runtime registers a platform back handler. [backHandler] is the platform's
 * adapter (each hook passes exactly one); it is enabled unless the state's policy is `pass`, and every press goes to
 * [FlowViewModel.back], which applies the bundle policy. Nothing else here reacts to Back.
 *
 * Until the interpreter exists (M5b), each state renders as [StatePlaceholder]: no state, screen or event is named.
 *
 * @param onExit called once when the flow reaches a final state or is abandoned; the hook maps the exit to the app.
 */
@Composable
fun FlowHost(
    bundle: FlowBundle,
    port: ServicePort,
    entry: String,
    launch: JsonObject,
    platform: FlowPlatform,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
    onExit: (FlowExit?) -> Unit,
    modifier: Modifier = Modifier,
    log: FlowLog = FlowLog.NONE,
) {
    val flow = viewModel { FlowViewModel.create(this, bundle, port, entry, launch, platform, log) }
    val status by flow.status.collectAsState()
    val active = status as? FlowStatus.Active

    backHandler(active?.backEnabled == true) { flow.back() }

    LaunchedEffect(status) {
        (status as? FlowStatus.Exited)?.let { exited ->
            log.log("exit ${exited.exit?.state} (${exited.reason})")
            onExit(exited.exit)
        }
    }

    Box(modifier.fillMaxSize()) {
        AnimatedContent(targetState = active, contentKey = { it?.state }) { shown ->
            if (shown != null) {
                StatePlaceholder(
                    bundle = bundle,
                    status = shown,
                    isAccepted = flow::isAccepted,
                    onEvent = { event -> flow.dispatch(event, JsonObject(emptyMap()), shown.gen) },
                )
            }
        }
    }
}
