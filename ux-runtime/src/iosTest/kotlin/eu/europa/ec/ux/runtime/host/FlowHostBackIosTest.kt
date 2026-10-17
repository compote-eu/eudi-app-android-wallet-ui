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

// M1b, re-run at M6a on the real export (was provisional on the boundary fixture): FlowHost back wiring on the exported
// bundle.
package eu.europa.ec.ux.runtime.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.QR_URI
import eu.europa.ec.ux.runtime.support.Results
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 at the host level (the simulator edge swipe could not be driven, SPIKE-LOG M4): the platform adapter is replaced
 * by a recorder, and a "press" calls the one callback FlowHost registered. The outcome must be the bundle policy only:
 * request = REJECT (cancelled), auth = back to request, sending = blocked (handler still enabled, one send).
 */
@OptIn(ExperimentalTestApi::class)
class FlowHostBackIosTest {

    private class Recorder {
        var enabled = false
        var press: (() -> Unit)? = null
    }

    private fun host(port: FakePort, recorder: Recorder) = runComposeUiTest {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                FlowHost(
                    bundle = Fixture.bundle(),
                    port = port,
                    entry = "deeplink",
                    launch = JsonObject(mapOf("uri" to JsonPrimitive(QR_URI))),
                    platform = FlowPlatform.IOS,
                    backHandler = { enabled, onBack ->
                        recorder.enabled = enabled
                        recorder.press = onBack
                    },
                    onExit = {},
                )
            }
        }
        waitUntil(timeoutMillis = TIMEOUT) { onAll("request") }
        steps.forEach { (node, state) ->
            // Since M5b the screens are interpreted: a step presses the node (its bundle id is its test tag).
            onNodeWithTag(node, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { onAll(state) }
        }
        assertTrue(recorder.enabled, "the handler must be enabled in $last")
        runOnIdle { recorder.press!!.invoke() }
        waitUntil(timeoutMillis = TIMEOUT) { onAll(expected) }
        waitForIdle()
        afterPress(recorder)
    }

    private var steps: List<Pair<String, String>> = emptyList()
    private var last = "request"
    private var expected = "cancelled"
    private var afterPress: (Recorder) -> Unit = {}

    private fun androidx.compose.ui.test.ComposeUiTest.onAll(state: String): Boolean =
        onAllNodesWithTagCount(PlaceholderTags.STATE_PREFIX + state) > 0

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun backInRequestRejects() {
        val port = FakePort().apply { auto["fetchRequest"] = Results.requestReady }
        expected = "cancelled"
        host(port, Recorder())
        assertEquals(1, port.calls("cancel"))
    }

    @Test
    fun backInAuthReturnsToRequestAndCancelsTheAuthentication() {
        val port = FakePort().apply { auto["fetchRequest"] = Results.requestReady }
        steps = listOf("continue" to "auth")
        last = "auth"
        expected = "request"
        host(port, Recorder())
        assertEquals(1, port.cancelled["authenticate"])
    }

    @Test
    fun backInSendingIsBlocked() {
        val port = FakePort().apply {
            auto["fetchRequest"] = Results.requestReady
            auto["authenticate"] = Results.authenticated
            auto["verificationCode"] = Results.codeReady
        }
        steps = listOf("continue" to "verification-code", "confirm" to "sending")
        last = "sending"
        expected = "sending"
        afterPress = { r -> assertTrue(r.enabled, "blocked keeps the handler enabled") }
        host(port, Recorder())
        assertEquals(1, port.calls("send"))
        assertEquals(null, port.cancelled["send"])
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
