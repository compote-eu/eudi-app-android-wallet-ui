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

package eu.europa.ec.uxspike.ios

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.withFrameNanos
import eu.europa.ec.shared.ui.di.SharedUiModule
import eu.europa.ec.shared.ui.di.module as sharedUiDefinitions
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentDisclosure
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentRequest
import eu.europa.ec.ux.contract.pres.PresCrossDeviceSpec
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.uxspike.UxFlowEntry
import eu.europa.ec.uxspike.UxFlowRoute
import eu.europa.ec.uxspike.UxSpikeLog
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController

/** Whether the document-provider extension hosts the spike sub-flow: the app-group switch is on (`--ux-spike <script>`). */
@Suppress("FunctionNaming", "Unused")
fun uxDcApiSubflowEnabled(): Boolean = IosUxSpikeSwitch.script() != null

/**
 * The request sub-flow (request, auth, sending, outcome) as a `UIViewController` the extension's `ConsentHost` can host
 * in place of the wallet's own consent screen (SPIKE-PLAN M10, track C). Debug builds only (`iosUxSpikeMain`).
 *
 * It runs the generic [UxFlowEntry] on the fake engine, entering at `deeplink` so the flow skips scanning and starts at
 * its first request state. The extension has no `NavDisplay`, so the back handler is a no-op; the runtime's own
 * back policy (bundle data) is not exercised here.
 *
 * Whatever the outcome, [onDecision] is called exactly once with `null` (a refusal): the fake engine has no real
 * credential behind it, so no data leaves the wallet. Start time and memory are logged under [UxDcApiMetrics.TAG].
 */
@Suppress("FunctionNaming", "Unused")
fun UxDcApiSubflowViewController(
    request: IosPresentmentRequest,
    onDecision: (List<IosPresentmentDisclosure>?) -> Unit,
): UIViewController {
    val metrics = UxDcApiMetrics(host = if (NSBundle.mainBundle.bundlePath.endsWith(".appex")) "extension" else "in-app")
    startKoinIfNeeded()
    val script = IosUxSpikeSwitch.script()?.takeIf { it != HARNESS_SCRIPT } ?: DEFAULT_SCRIPT
    UxSpikeLog.log("dcapi sub-flow: ${request.combinations.size} combination(s), script $script")
    return ComposeUIViewController {
        Surface(modifier = Modifier.fillMaxSize()) {
            UxDcApiSubflow(script = script, metrics = metrics, onDecision = onDecision)
        }
    }
}

@Composable
private fun UxDcApiSubflow(
    script: String,
    metrics: UxDcApiMetrics,
    onDecision: (List<IosPresentmentDisclosure>?) -> Unit,
) {
    var decided by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // The first frame after this composition was committed; later frames are ignored by the metrics.
        withFrameNanos { }
        metrics.onFrame()
    }
    UxFlowEntry(
        route = UxFlowRoute(PresCrossDeviceSpec.FLOW_ID, entry = ENTRY, launch = LAUNCH),
        script = script,
        platform = FlowPlatform.IOS,
        backHandler = { _, _ -> },
        onExit = { exit ->
            if (!decided) {
                decided = true
                metrics.onComplete(outcome = exit?.toString() ?: "abandoned")
                onDecision(null)
            }
        },
    )
}

private fun startKoinIfNeeded() {
    if (KoinPlatform.getKoinOrNull() != null) return
    startKoin { modules(SharedUiModule().sharedUiDefinitions()) }
}

internal const val HARNESS_SCRIPT = "dcapi-harness"
private const val DEFAULT_SCRIPT = "happy"
private const val ENTRY = "deeplink"
private const val LAUNCH = "dcapi://extension"
