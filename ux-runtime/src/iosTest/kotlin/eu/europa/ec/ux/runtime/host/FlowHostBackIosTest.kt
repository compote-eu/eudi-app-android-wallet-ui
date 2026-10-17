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

// M1b, re-run at M6a on the real export; M9r (spike rule T1): FlowHost back wiring on the synthetic test bundle (one
// state per back policy, drawn by the state placeholder). The export's own back policies: ExportedPoliciesTest.
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
import eu.europa.ec.ux.runtime.support.SynthResults
import eu.europa.ec.ux.runtime.support.Synthetic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 at the host level (the simulator edge swipe could not be driven, SPIKE-LOG M4): the platform adapter is replaced
 * by a recorder, and a "press" calls the one callback FlowHost registered. The outcome must be the bundle policy only:
 * an event policy fires its event, to_history returns and cancels the call, blocked keeps the state, the handler enabled
 * and the one non-idempotent call.
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
                    bundle = Synthetic.bundle(),
                    port = port,
                    entry = Synthetic.LINK,
                    launch = Synthetic.launch,
                    platform = FlowPlatform.IOS,
                    backHandler = { enabled, onBack ->
                        recorder.enabled = enabled
                        recorder.press = onBack
                    },
                    onExit = {},
                )
            }
        }
        waitUntil(timeoutMillis = TIMEOUT) { onAll(Synthetic.CHOOSE) }
        steps.forEach { (event, state) ->
            // The synthetic bundle has no screens: the placeholder draws each event of the state as a button.
            onNodeWithTag(PlaceholderTags.EVENT_PREFIX + event, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { onAll(state) }
        }
        assertTrue(recorder.enabled, "the handler must be enabled in $last")
        runOnIdle { recorder.press!!.invoke() }
        waitUntil(timeoutMillis = TIMEOUT) { onAll(expected) }
        waitForIdle()
        afterPress(recorder)
    }

    private var steps: List<Pair<String, String>> = emptyList()
    private var last = Synthetic.CHOOSE
    private var expected = Synthetic.ABORTED
    private var afterPress: (Recorder) -> Unit = {}

    private fun androidx.compose.ui.test.ComposeUiTest.onAll(state: String): Boolean =
        onAllNodesWithTagCount(PlaceholderTags.STATE_PREFIX + state) > 0

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun anEventPolicyFiresItsEvent() {
        val port = FakePort().apply { auto[Synthetic.SVC_LOAD] = SynthResults.loaded }
        expected = Synthetic.ABORTED
        host(port, Recorder())
        assertEquals(1, port.calls(Synthetic.SVC_STOP))
    }

    @Test
    fun aToHistoryPolicyReturnsAndCancelsTheCall() {
        val port = FakePort().apply { auto[Synthetic.SVC_LOAD] = SynthResults.loaded }
        steps = listOf(Synthetic.GO to Synthetic.CHECK)
        last = Synthetic.CHECK
        expected = Synthetic.CHOOSE
        host(port, Recorder())
        assertEquals(1, port.cancelled[Synthetic.SVC_CHECK])
    }

    @Test
    fun aBlockedPolicyKeepsTheStateAndTheHandler() {
        val port = FakePort().apply {
            auto[Synthetic.SVC_LOAD] = SynthResults.loaded
            auto[Synthetic.SVC_CHECK] = SynthResults.passed
            auto[Synthetic.SVC_PRODUCE] = SynthResults.made
        }
        steps = listOf(Synthetic.GO to Synthetic.CODE, Synthetic.CONFIRM to Synthetic.COMMIT)
        last = Synthetic.COMMIT
        expected = Synthetic.COMMIT
        afterPress = { r -> assertTrue(r.enabled, "blocked keeps the handler enabled") }
        host(port, Recorder())
        assertEquals(1, port.calls(Synthetic.SVC_COMMIT))
        assertEquals(null, port.cancelled[Synthetic.SVC_COMMIT])
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
