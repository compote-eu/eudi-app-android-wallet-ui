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
// M7, re-run at M6a on the real export (was provisional on the boundary fixture): FlowHost-level D7 checks on the
// exported bundle.
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
import eu.europa.ec.ux.runtime.support.ExportWalk
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.TestCopy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * the bundle (screen `a11y` and the exported copy), so no test names a screen id. Replaces the same-node checks of M5.
 */
@OptIn(ExperimentalTestApi::class)
abstract class StateChangeA11yScenario {

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
                    entry = ExportWalk.entry(bundle),
                    launch = ExportWalk.launch(ExportWalk.entry(bundle)),
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

    /** What the node says, from the bundle: its `{copy}` props without placeholders, their exported text, joined. */
    private fun spoken(tree: ScreenTree, node: String): String =
        tree.node(node)!!.props.values.mapNotNull { v -> ((v as? JsonObject)?.get("copy") as? JsonPrimitive)?.content }
            .mapNotNull { TestCopy.sk(Fixture.root, it) }.joinToString(". ")

    /**
     * Matches a node showing one of [node]'s copy texts. Order-free: the export writes props in sorted key order (M6a),
     * so the first copy prop is no longer the heading's (EL-status-hero: `text` sorts before `title`).
     */
    private fun showsCopyOf(tree: ScreenTree, node: String): SemanticsMatcher =
        tree.node(node)!!.props.values.mapNotNull { v -> ((v as? JsonObject)?.get("copy") as? JsonPrimitive)?.content }
            .map { hasText(TestCopy.sk(Fixture.root, it)!!, substring = true) }.reduce { a, b -> a or b }

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
            assertEquals(1, count(isFocused() and isHeading() and showsCopyOf(t, t.a11y.heading!!)), "focus on the h1 of $state")
            assertEquals(1, count(isFocused()), "one focused node in $state")
        } else {
            assertEquals(0, count(isFocused()), "announce-live does not move focus ($state)")
        }
        assertTrue(count(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)) <= 1, "at most one live region in $state")
    }

    /** Performs one step of the export's main path: answers the service, or presses the node that emits the event. */
    private fun ComposeUiTest.perform(bundle: FlowBundle, port: FakePort, step: ExportWalk.Step) {
        if (step.service != null) {
            runOnIdle { port.complete(step.service, step.result!!) }
        } else {
            val node = assertNotNull(ExportWalk.emitter(tree(bundle, step.from), step.event!!), "no node of ${step.from} emits ${step.event}")
            waitForIdle()
            onNodeWithTag(node.id, useUnmergedTree = true).performClick()
        }
    }

    /** The main path up to (not into) a final state. */
    private fun path(bundle: FlowBundle) = ExportWalk.mainPath(bundle).takeWhile { !bundle.state(it.to).isFinal }

    @Test
    fun theMainPathFiresOneBridgeCallPerStateAfterItSettled() = runComposeUiTest {
        val port = FakePort()
        val bundle = host(port)
        val steps = path(bundle)
        val visited = mutableListOf(ExportWalk.entryState(bundle))
        settle(bundle, visited.single(), null)
        steps.forEach { step ->
            perform(bundle, port, step)
            if (step.to != step.from) {
                settle(bundle, step.to, step.from)
                visited += step.to
            } else {
                waitForIdle()
            }
        }
        assertTrue(visited.size > 1, "the main path leaves the entry state: $visited")
        // One call per state entry, in order.
        assertEquals(visited.map { expectedCall(bundle, it) }, bridge.calls)
    }

    @Test
    fun theLeavingStateHasNoSemanticsDuringTheCrossfade() = runComposeUiTest {
        val port = FakePort()
        val bundle = host(port)
        val step = path(bundle).first { it.to != it.from }
        val old = step.from
        settle(bundle, old, null)
        val t = tree(bundle, old)
        // The merged tree is what a screen reader gets (the unmerged one keeps cleared nodes for tests).
        val oldA11y = hasAnyAncestor(hasTestTag(PlaceholderTags.STATE_PREFIX + old)) and
            (isHeading() or SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) or showsCopyOf(t, t.a11y.liveRegion ?: t.a11y.heading!!))
        assertTrue(onAllNodes(oldA11y).fetchSemanticsNodes().isNotEmpty(), "settled: the old state is exposed")
        val before = bridge.calls.size
        mainClock.autoAdvance = false
        perform(bundle, port, step)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(CROSSFADE_PROBE_MS)
        assertEquals(1, count(hasTestTag(PlaceholderTags.STATE_PREFIX + old)), "mid-crossfade: the old state is still drawn")
        assertEquals(1, count(hasTestTag(PlaceholderTags.STATE_PREFIX + step.to)), "mid-crossfade: the new state is drawn")
        assertEquals(0, onAllNodes(oldA11y).fetchSemanticsNodes().size, "no h1, live region or text of the old state")
        assertEquals(before, bridge.calls.size, "no bridge call before the transition settled")
        mainClock.autoAdvance = true
        settle(bundle, step.to, old)
    }

    @Test
    fun backIsAStateChangeTooAndRefocusesTheHeading() = runComposeUiTest {
        val port = FakePort()
        val bundle = host(port)
        val steps = path(bundle)
        // A step of the main path the bundle's back policy undoes, into a screen that focuses its heading (read from the export).
        val k = steps.indexOfFirst { s ->
            s.service == null && s.to != s.from && ExportWalk.backReturnsTo(bundle.state(s.to), s.from) &&
                tree(bundle, s.from).a11y.onEnter == "focus-heading"
        }
        if (k < 0) return@runComposeUiTest println("D7: the export's main path has no step that Back undoes; nothing to check")
        settle(bundle, ExportWalk.entryState(bundle), null)
        steps.take(k + 1).forEach { step ->
            perform(bundle, port, step)
            if (step.to != step.from) settle(bundle, step.to, step.from) else waitForIdle()
        }
        val (from, to) = steps[k].from to steps[k].to
        val entries = bridge.calls.count { it == expectedCall(bundle, from) }
        runOnIdle { back() }
        settle(bundle, from, to)
        assertEquals(expectedCall(bundle, from), bridge.calls.last())
        assertEquals(entries + 1, bridge.calls.count { it == expectedCall(bundle, from) })
    }

    private companion object {
        const val TIMEOUT = 5_000L

        /** Inside AnimatedContent's default 90 ms fade-out, after the new content was composed. */
        const val CROSSFADE_PROBE_MS = 48L
    }
}
