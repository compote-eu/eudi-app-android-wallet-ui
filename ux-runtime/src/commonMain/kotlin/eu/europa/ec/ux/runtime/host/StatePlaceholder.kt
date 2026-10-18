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

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.engine.FlowStatus

/** Test tags for Maestro and the tests: [STATE_PREFIX] + state id marks the drawn state (FlowHost), the rest the placeholder. */
object PlaceholderTags {
    const val STATE: String = "ux-state"
    const val STATE_PREFIX: String = "ux-state-"
    const val EVENT_PREFIX: String = "ux-event-"
}

/**
 * A generic, state-agnostic rendering of a state whose screen the bundle does not describe (M4; since M5b only that
 * fallback, every described screen is interpreted): the state id,
 * its kind and the events of its `on`, each as a button. A button is enabled when the engine accepts the event now
 * and the event needs no payload (the placeholder has none to give).
 */
@Composable
internal fun StatePlaceholder(
    bundle: FlowBundle,
    status: FlowStatus.Active,
    isAccepted: (String) -> Boolean,
    onEvent: (String) -> Unit,
) {
    val state = bundle.state(status.state)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 64.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            "state: ${state.id}",
            Modifier.testTag(PlaceholderTags.STATE),
        )
        BasicText(
            "kind: ${state.kind.wire}  screen: ${state.screen}  ui_state: ${status.uiState}",
        )
        BasicText("gen: ${status.gen}  pending: ${status.pending}  back: ${state.back}")
        state.on.keys.forEach { event ->
            val def = bundle.events[event]
            val enabled = isAccepted(event) && def?.payload.isNullOrEmpty()
            val label = "$event (${def?.source})" + if (enabled) "" else "  [disabled]"
            BasicText(
                label,
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (enabled) Color.DarkGray else Color.LightGray)
                    .clickable(enabled = enabled, role = Role.Button) { onEvent(event) }
                    .padding(12.dp)
                    .testTag(PlaceholderTags.EVENT_PREFIX + event),
            )
        }
    }
}
