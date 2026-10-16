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
// PROVISIONAL (M7): FlowHost-level D7 checks on the boundary fixture; re-run on the real export in M6.
package eu.europa.ec.ux.runtime.a11y

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.host.FlowHost
import eu.europa.ec.ux.runtime.host.PlaceholderTags
import eu.europa.ec.ux.runtime.screen.FlowScreens
import eu.europa.ec.ux.runtime.screen.ScreenCatalog
import eu.europa.ec.ux.runtime.screen.ScreenTree
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.QR_URI
import eu.europa.ec.ux.runtime.support.Results
import eu.europa.ec.ux.runtime.support.TestCopy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A recording fake: one line per bridge call, in order. */
class RecordingBridge : AccessibilityBridge {
    val calls = mutableListOf<String>()

    override suspend fun screenChanged(screen: String, headingNode: String?, headingText: String?, dialog: Boolean) {
        calls += "screen-changed $screen h1=$headingNode text='$headingText' dialog=$dialog"
    }

    override suspend fun announce(screen: String, liveRegionNode: String?, text: String) {
        calls += "announce $screen live=$liveRegionNode text='$text'"
    }
}

/**
 * D7 at FlowHost level (M7, KC3): real state transitions driven through the engine and the fake port, asserting the
 * bridge calls (a recording fake) and the semantics tree after each settle. Expected texts and node ids are read from
 * the bundle (screen `a11y` and the `_sk` copy), so no test names a screen id. Replaces the same-node checks of M5.
 */
@OptIn(ExperimentalTestApi::class)
abstract class ProvisionalStateChangeA11yScenario {

    private val bridge = RecordingBridge()
    private val lines = mutableListOf<String>()
    private var back: () -> Unit = {}

    private fun ComposeUiTest.host(port: FakePort): FlowBundle {
        val bundle = Fixture.bundle()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                FlowHost(
                    bundle = bundle,
                    port = port,
                    entry = "deeplink",
                    launch = JsonObject(mapOf("uri" to JsonPrimitive(QR_URI))),
                    platform = FlowPlatform.IOS,
                    backHandler = { enabled, onBack -> back = if (enabled) onBack else ({}) },
                    onExit = {},
                    screens = FlowScreens(copy = TestCopy.provider(Fixture.root), accessibility = bridge),
                    log = FlowLog { lines += it },
                )
            }
        }
        return bundle
    }

    private fun ComposeUiTest.count(matcher: SemanticsMatcher): Int =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun tree(bundle: FlowBundle, state: String): ScreenTree =
        ScreenCatalog(bundle).screens.getValue(bundle.state(state).screen!!)

    /** What the node says, from the bundle: its `{copy}` props without placeholders, `_sk` text, joined. */
    private fun spoken(tree: ScreenTree, node: String): String =
        tree.node(node)!!.props.values.mapNotNull { v -> ((v as? JsonObject)?.get("copy") as? JsonPrimitive)?.content }
            .mapNotNull { TestCopy.sk(Fixture.root, it) }.joinToString(". ")

    private fun firstCopy(tree: ScreenTree, node: String): String =
        tree.node(node)!!.props.values.firstNotNullOf { v -> ((v as? JsonObject)?.get("copy") as? JsonPrimitive)?.content }
            .let { TestCopy.sk(Fixture.root, it)!! }

    /** The line the runtime must send when [state] has settled, built from the bundle's screen `a11y`. */
    private fun expectedCall(bundle: FlowBundle, state: String): String {
        val t = tree(bundle, state)
        return when (t.a11y.onEnter) {
            "focus-heading" -> "screen-changed ${t.id} h1=${t.a11y.heading} text='${spoken(t, t.a11y.heading!!)}' " +
                "dialog=${t.a11y.dialogLabelledBy != null}"
            else -> "announce ${t.id} live=${t.a11y.liveRegion} text='${spoken(t, t.a11y.liveRegion!!)}'"
        }
    }

    /** Waits for [state]'s bridge call, then checks the tree: old state gone, focus on the new h1, one live region at most. */
    private fun ComposeUiTest.settle(bundle: FlowBundle, state: String, from: String?) {
        val expected = expectedCall(bundle, state)
        waitUntil(timeoutMillis = TIMEOUT) { bridge.calls.lastOrNull() == expected }
        waitForIdle()
        if (from != null) assertEquals(0, count(hasTestTag(PlaceholderTags.STATE_PREFIX + from)), "$from is gone at $state's call")
        val t = tree(bundle, state)
        if (t.a11y.onEnter == "focus-heading") {
            val h1 = firstCopy(t, t.a11y.heading!!)
            assertEquals(1, count(isFocused() and isHeading() and hasText(h1, substring = true)), "focus on the h1 of $state")
            assertEquals(1, count(isFocused()), "one focused node in $state")
        } else {
            assertEquals(0, count(isFocused()), "announce-live does not move focus ($state)")
        }
        assertTrue(count(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)) <= 1, "at most one live region in $state")
    }

    @Test
    fun requestToSuccessFiresOneBridgeCallPerStateAfterItSettled() = runComposeUiTest {
        val port = FakePort()
        val bundle = host(port)
        settle(bundle, "connecting", null)
        runOnIdle { port.complete("fetchRequest", Results.requestReady) }
        settle(bundle, "request", "connecting")
        onNodeWithTag("continue", useUnmergedTree = true).performClick()
        settle(bundle, "auth", "request")
        runOnIdle { port.complete("authenticate", Results.authenticated) }
        settle(bundle, "verification-code", "auth")
        runOnIdle { port.complete("verificationCode", Results.codeReady) }
        waitForIdle()
        val confirm = tree(bundle, "verification-code").regions.getValue("actions").first { it.events.single().emits == "CONFIRM" }
        onNodeWithTag(confirm.id, useUnmergedTree = true).performClick()
        settle(bundle, "sending", "verification-code")
        runOnIdle { port.complete("send", Results.sent) }
        settle(bundle, "success", "sending")
        waitForIdle()
        // One call per state entry, in order: sending is announced once, success gets the screen change.
        val states = listOf("connecting", "request", "auth", "verification-code", "sending", "success")
        assertEquals(states.map { expectedCall(bundle, it) }, bridge.calls)
    }

    @Test
    fun theLeavingStateHasNoSemanticsDuringTheCrossfade() = runComposeUiTest {
        val port = FakePort()
        val bundle = host(port)
        settle(bundle, "connecting", null)
        val oldText = firstCopy(tree(bundle, "connecting"), "hero")
        // The merged tree is what a screen reader gets (the unmerged one keeps cleared nodes for tests).
        val oldA11y = hasAnyAncestor(hasTestTag(PlaceholderTags.STATE_PREFIX + "connecting")) and
            (isHeading() or SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) or hasText(oldText, substring = true))
        assertEquals(1, onAllNodes(oldA11y).fetchSemanticsNodes().size, "settled: the live hero is exposed")
        val before = bridge.calls.size
        mainClock.autoAdvance = false
        runOnIdle { port.complete("fetchRequest", Results.requestReady) }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(CROSSFADE_PROBE_MS)
        assertEquals(1, count(hasTestTag(PlaceholderTags.STATE_PREFIX + "connecting")), "mid-crossfade: the old state is still drawn")
        assertEquals(1, count(hasTestTag(PlaceholderTags.STATE_PREFIX + "request")), "mid-crossfade: the new state is drawn")
        assertEquals(0, onAllNodes(oldA11y).fetchSemanticsNodes().size, "no h1, live region or text of the old state")
        assertEquals(before, bridge.calls.size, "no bridge call before the transition settled")
        mainClock.autoAdvance = true
        settle(bundle, "request", "connecting")
    }

    @Test
    fun backIsAStateChangeTooAndRefocusesTheRequestHeading() = runComposeUiTest {
        val port = FakePort().apply { auto["fetchRequest"] = Results.requestReady }
        val bundle = host(port)
        settle(bundle, "request", null)
        onNodeWithTag("continue", useUnmergedTree = true).performClick()
        settle(bundle, "auth", "request")
        runOnIdle { back() }
        settle(bundle, "request", "auth")
        assertEquals(expectedCall(bundle, "request"), bridge.calls.last())
        assertEquals(2, bridge.calls.count { it == expectedCall(bundle, "request") })
    }

    private companion object {
        const val TIMEOUT = 5_000L

        /** Inside AnimatedContent's default 90 ms fade-out, after the new content was composed. */
        const val CROSSFADE_PROBE_MS = 48L
    }
}
