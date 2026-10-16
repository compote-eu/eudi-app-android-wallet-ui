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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.europa.ec.ux.contract.UxContractModule
import eu.europa.ec.ux.contract.resources.Res

/** Test tag of the placeholder text, so Maestro and the tests can find it. */
const val UX_FLOW_STUB_TAG = "ux-flow-stub"

/**
 * Placeholder body of [UxFlowRoute] (M2). It also reads the P3 probe resource from `:ux-contract`, to show that
 * compose resources of that module reach the running app.
 */
@Composable
fun UxFlowStub(route: UxFlowRoute) {
    var probe by remember { mutableStateOf("probe pending") }
    LaunchedEffect(Unit) {
        probe = runCatching { Res.readBytes(UxContractModule.PROBE_PATH).decodeToString() }
            .getOrElse { "probe FAILED: ${it.message}" }
        println("UX-SPIKE: stub shown flow=${route.flowId} entry=${route.entry} launch=${route.launch} P3=$probe")
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        BasicText(
            text = "UX-SPIKE stub (${route.flowId}, ${route.entry}) $probe",
            modifier = Modifier.testTag(UX_FLOW_STUB_TAG),
        )
    }
}
