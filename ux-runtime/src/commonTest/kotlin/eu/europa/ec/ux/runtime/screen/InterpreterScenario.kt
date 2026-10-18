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

// M5b, re-run at M6a on the real export; M9r (spike rule T1): every state, node, event, service, result, copy text and
// layout the tests expect is read from ux-spec/ at test time (ExportWalk: the entry, the main path, the payloads of the
// exported scripts, the brand's layout), so a spec-only change moves the expectation, never the verdict.
package eu.europa.ec.ux.runtime.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
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
import eu.europa.ec.ux.elements.registry.UxElementRegistry
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.StateKind
import eu.europa.ec.ux.runtime.engine.FlowContext
import eu.europa.ec.ux.runtime.engine.FlowEngine
import eu.europa.ec.ux.runtime.engine.FlowExit
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.engine.FlowStatus
import eu.europa.ec.ux.runtime.engine.ServiceResult
import eu.europa.ec.ux.runtime.host.FlowHost
import eu.europa.ec.ux.runtime.host.PlaceholderTags
import eu.europa.ec.ux.runtime.support.AddedStateFixture
import eu.europa.ec.ux.runtime.support.ExportWalk
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.TestCopy
import eu.europa.ec.ux.runtime.support.expectedTexts
import eu.europa.ec.ux.runtime.support.obj
import eu.europa.ec.ux.runtime.support.unlinked
import eu.europa.ec.ux.runtime.support.visibleNodeIds
import eu.europa.ec.ux.runtime.support.with
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The screen interpreter (SPIKE-PLAN §1.4), asserted on the semantics tree. Test (i): screens drawn from bundle data
 * alone; test (ii): a state that exists only in a test copy of the bundle renders and transitions with no Kotlin change.
 */
@OptIn(ExperimentalTestApi::class)
abstract class InterpreterScenario {

    private val lines = mutableListOf<String>()
    private val log = FlowLog { lines += it }
    private var exits = mutableListOf<FlowExit?>()

    private fun ComposeUiTest.host(
        root: JsonObject,
        port: FakePort,
        custom: Map<String, CustomScreen> = emptyMap(),
    ): FlowBundle {
        val bundle = Fixture.bundle(root)
        val entry = ExportWalk.entry(bundle)
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                FlowHost(
                    bundle = bundle,
                    port = port,
                    entry = entry,
                    launch = ExportWalk.launch(entry),
                    platform = FlowPlatform.IOS,
                    backHandler = { _, _ -> },
                    onExit = { exits += it },
                    screens = FlowScreens(copy = TestCopy.provider(root), custom = custom),
                    log = log,
                )
            }
        }
        return bundle
    }

    private fun ComposeUiTest.count(matcher: SemanticsMatcher): Int =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.awaitState(state: String) =
        waitUntil(timeoutMillis = TIMEOUT) { count(hasTestTag(PlaceholderTags.STATE_PREFIX + state)) > 0 }

    /**
     * Every node of the state's screen that is drawn is drawn under its id, with its copy, and nothing is a placeholder.
     * Absent by design: [hidden], links with no href, the nodes the screen's rules hide in [context] (with their subtrees;
     * M6c, the D8-unseen pin), and the regions the template leaves out in the default brand's layout (M9r, the D9 pin:
     * `ExportWalk.drawnRegions`, read from the bundle's `layouts` and the generated brand config).
     */
    private fun ComposeUiTest.assertDrawnFromBundle(
        root: JsonObject,
        bundle: FlowBundle,
        state: String,
        hidden: Set<String> = emptySet(),
        context: JsonObject? = null,
    ) {
        val tree = assertNotNull(ScreenCatalog(bundle).screens[bundle.state(state).screen], "no tree for $state")
        val absent = hidden + unlinked(root, tree) + context?.let { ruleHidden(bundle, tree, it) }.orEmpty() + ExportWalk.undrawn(root, tree)
        waitForIdle()
        visibleNodeIds(tree, absent).forEach { id -> assertTrue(count(hasTestTag(id)) > 0, "node $id of ${tree.id} not drawn") }
        absent.forEach { id -> assertEquals(0, count(hasTestTag(id)), "node $id of ${tree.id} should be absent") }
        val texts = expectedTexts(root, tree, absent)
        texts.forEach { (id, text) ->
            assertTrue(count(hasText(text) or hasContentDescription(text)) > 0, "copy of $id ('$text') not drawn on ${tree.id}")
        }
        assertEquals(0, count(SemanticsMatcher("unknown part") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("ux-unknown-") == true }))
        assertEquals(0, count(hasTestTag(PlaceholderTags.STATE)), "the M4 placeholder must not be drawn")
        assertTrue(lines.any { it.startsWith("screen ${tree.id}: interpreted from the bundle") }, "interpreter log for ${tree.id}")
    }

    /** The nodes (with their subtrees) the screen's rules hide in [context]. */
    private fun ruleHidden(bundle: FlowBundle, tree: ScreenTree, context: JsonObject): Set<String> {
        val effects = NodeEffects.of(bundle.screens.getValue(tree.id).rules, context)
        return tree.nodes.filterNot { effects.isVisible(it.id) }.flatMap { n -> n.walk().map(ScreenNode::id).toList() }.toSet()
    }

    /** The texts of a node's `{copy}` props (no placeholders), from the exported copy. */
    private fun copyTexts(root: JsonObject, node: ScreenNode): List<String> =
        node.props.values.mapNotNull { v -> ((v as? JsonObject)?.get("copy") as? JsonPrimitive)?.content }.mapNotNull { TestCopy.sk(root, it) }

    @Test
    fun theEntryStateIsDrawnFromTheBundle() = runComposeUiTest {
        val port = FakePort() // the entry state's call stays pending
        val bundle = host(Fixture.root, port)
        val state = ExportWalk.entryState(bundle)
        awaitState(state)
        assertDrawnFromBundle(Fixture.root, bundle, state)
        val tree = ExportWalk.tree(bundle, state)
        // The live region and the announce-live hook come from the bundle's a11y fields.
        val live = tree.a11y.liveRegion
        if (live != null) {
            onNodeWithTag(live, useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }
        waitUntil(timeoutMillis = TIMEOUT) { lines.any { it.startsWith("a11y: ${tree.id} ${tree.a11y.onEnter}") } }
        // A node that emits an event the state accepts while its call is pending is enabled and moves the engine where
        // the bundle's first branch for that event says.
        val s = bundle.state(state)
        val accepted = { e: String -> e in s.on && s.invoke?.acceptsWhilePending?.contains(e) != false }
        val action = tree.nodes.firstOrNull { n -> n.events.any { accepted(it.emits) } } ?: return@runComposeUiTest
        val target = s.on.getValue(action.events.first { accepted(it.emits) }.emits).first().target ?: return@runComposeUiTest
        onNodeWithTag(action.id, useUnmergedTree = true).assertIsEnabled().performClick()
        if (bundle.state(target).isFinal) {
            waitUntil(timeoutMillis = TIMEOUT) { exits.isNotEmpty() }
        } else {
            awaitState(target)
            bundle.state(target).invoke?.let { assertEquals(1, port.calls(it.service), "${it.service} of $target") }
        }
    }

    /** The context the engine holds once it settles in [state] for these service results (the host's launch inputs). */
    private fun settledContext(root: JsonObject, results: Map<String, ServiceResult>, state: String): JsonObject {
        var context: JsonObject? = null
        runTest {
            val bundle = Fixture.bundle(root)
            val entry = ExportWalk.entry(bundle)
            val engine = FlowEngine(bundle, FakePort().apply { auto += results }, backgroundScope)
            engine.start(entry, ExportWalk.launch(entry), unlocked = true)
            runCurrent()
            val active = assertIs<FlowStatus.Active>(engine.current.value)
            assertEquals(state, active.state)
            context = active.context
        }
        return assertNotNull(context)
    }

    /**
     * A recovery outcome the entry state's call can end in (read from the bundle): the first result of the entry state's
     * invoke whose event leads to a `recovery` state with a screen that focuses its heading. Null when the export has none.
     */
    private fun recovery(bundle: FlowBundle): Pair<Map<String, ServiceResult>, String>? {
        val entry = bundle.state(ExportWalk.entryState(bundle))
        val inv = entry.invoke ?: return null
        return inv.on.entries.firstNotNullOfOrNull { (result, mapping) ->
            val target = mapping.event?.let { entry.on[it]?.firstOrNull { b -> b.guard == null }?.target } ?: return@firstNotNullOfOrNull null
            val t = bundle.state(target)
            val tree = t.screen?.let { ScreenCatalog(bundle).screens[it] }
            if (t.kind == StateKind.RECOVERY && tree?.a11y?.onEnter == "focus-heading" && tree.a11y.heading != null) {
                mapOf(inv.service to ExportWalk.result(inv.service, result)) to target
            } else {
                null
            }
        }
    }

    @Test
    fun aRecoveryOutcomeIsDrawnFromTheBundleAndFocusesItsHeading() {
        val (results, state) = recovery(Fixture.bundle()) ?: return println("the export's entry call leads to no recovery outcome")
        val context = settledContext(Fixture.root, results, state)
        runComposeUiTest {
            val port = FakePort().apply { auto += results }
            val bundle = host(Fixture.root, port)
            awaitState(state)
            assertDrawnFromBundle(Fixture.root, bundle, state, context = context)
            val tree = ExportWalk.tree(bundle, state)
            val heading = copyTexts(Fixture.root, tree.node(tree.a11y.heading!!)!!)
            assertTrue(heading.isNotEmpty(), "the heading node has copy")
            assertTrue(heading.any { count(isHeading() and hasText(it)) > 0 }, "the heading node is a heading")
            waitUntil(timeoutMillis = TIMEOUT) { lines.any { it.startsWith("a11y: ${tree.id} focus-heading") } }
            waitForIdle()
            val focused = onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().map { it.config.toString() }
            assertTrue(
                heading.any { count(isFocused() and hasText(it, substring = true)) > 0 },
                "focus is on the heading after the state settled: ${lines.filter { it.startsWith("a11y") }} focused=$focused",
            )
            // A drawn node that ends the flow ends it in the final state its event leads to (the abandon state first).
            val s = bundle.state(state)
            val ends = tree.nodes.filter { count(hasTestTag(it.id)) > 0 }.flatMap { n ->
                n.events.mapNotNull { e -> s.on[e.emits]?.firstOrNull()?.target?.takeIf { bundle.state(it).isFinal }?.let { n to it } }
            }
            val (close, end) = ends.firstOrNull { it.second == bundle.abandonState } ?: ends.firstOrNull() ?: return@runComposeUiTest
            onNodeWithTag(close.id, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { exits.isNotEmpty() }
            assertEquals(end, exits.single()?.state)
        }
    }

    @Test
    fun anAddedStateRendersAndTransitionsWithNoKotlinChange() = runComposeUiTest {
        // The copy is built from the current export, so it holds whatever states the spec has; the added one is the
        // single state that is not in the export.
        val root = AddedStateFixture.root
        val port = FakePort().apply { auto[AddedStateFixture.service] = ServiceResult(AddedStateFixture.RESULT) }
        val bundle = host(root, port)
        val added = (bundle.states.keys - Fixture.bundle().states.keys).single()
        assertEquals(AddedStateFixture.STATE, added)
        awaitState(added)
        assertDrawnFromBundle(root, bundle, added)
        val tree = ScreenCatalog(bundle).screens.getValue(bundle.state(added).screen!!)
        val leave = tree.nodes.first { it.events.isNotEmpty() }
        // Back in the host state, its call answers with the result the export's main path takes (and its payload).
        val main = ExportWalk.mainPath(Fixture.bundle()).firstOrNull { it.from == AddedStateFixture.host && it.service == AddedStateFixture.service }
        main?.let { port.auto[AddedStateFixture.service] = it.result!! }
        onNodeWithTag(leave.id, useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = TIMEOUT) { port.calls(AddedStateFixture.service) == 2 }
        main?.let { awaitState(it.to) }
        assertEquals(2, port.calls(AddedStateFixture.service))
    }

    @Test
    fun aNodeTheRulesHideInTheTestContextIsExpectedAbsent() {
        // M6c regression for the D8-unseen pin (RULING-M8b-6): a test copy adds a rule that hides a node of a recovery
        // outcome exactly in the context of this run (a path that is empty there), as the unseen spec change did. The
        // helper must expect the node absent, not drawn.
        val (results, state) = recovery(Fixture.bundle()) ?: return println("the export's entry call leads to no recovery outcome")
        val screen = assertNotNull(Fixture.bundle().state(state).screen)
        val tree = ScreenCatalog(Fixture.bundle()).screens.getValue(screen)
        val before = settledContext(Fixture.root, results, state)
        val hiddenBefore = ruleHidden(Fixture.bundle(), tree, before)
        val undrawn = ExportWalk.undrawn(Fixture.root, tree)
        val target = tree.regions.values.flatten().first { it.id !in hiddenBefore && it.id !in undrawn && it.events.isEmpty() && !it.a11y.inert }.id
        val empty = Fixture.bundle().context.keys.first { p ->
            when (val v = FlowContext.get(before, p)) {
                is JsonNull -> true
                is JsonPrimitive -> v.isString && v.content.isEmpty()
                is JsonArray -> v.isEmpty()
                else -> false
            }
        }
        val rules = ((Fixture.root["screens"] as JsonObject)[screen] as JsonObject)["rules"] as? JsonArray ?: JsonArray(emptyList())
        val rule = obj("""{"id": "RUL-_test-hide-when-empty", "when": {"op": "is_empty", "path": "$empty"},
            "then": [{"effect": "hide", "node": "$target"}]}""")
        val root = Fixture.root.with("/screens/$screen/rules", JsonArray(rules + rule))
        val bundle = Fixture.bundle(root)
        val context = settledContext(root, results, state)
        assertEquals(hiddenBefore + target, ruleHidden(bundle, tree, context), "the planted rule hides its node here")
        runComposeUiTest {
            host(root, FakePort().apply { auto += results })
            awaitState(state)
            assertDrawnFromBundle(root, bundle, state, context = context)
            assertEquals(0, count(hasTestTag(target)), "$target is hidden by the rule")
        }
    }

    /** Drives the host along the export's main path (services answered by the port's `auto`) until [state] is shown. */
    private fun ComposeUiTest.walkTo(bundle: FlowBundle, steps: List<ExportWalk.Step>, state: String) {
        for (step in steps) {
            if (step.from == state) break
            // A state the user leaves waits for the user, so it is shown; a state a service leaves may pass in one frame.
            if (step.service == null) {
                awaitState(step.from)
                waitForIdle()
                val node = assertNotNull(ExportWalk.emitter(ExportWalk.tree(bundle, step.from), step.event!!), "no node of ${step.from} emits ${step.event}")
                onNodeWithTag(node.id, useUnmergedTree = true).performClick()
            }
        }
        awaitState(state)
    }

    @Test
    fun rulesAndBindingsFollowTheContext() {
        // A state of the main path whose call answers with an internal result (no event: the user acts next), read from
        // the export. Its answer: the exported payload, with each field the screen's rules read set to null and each other
        // string field set to a test marker, so the rules and the bindings both show.
        val bundle = Fixture.bundle()
        val steps = ExportWalk.mainPath(bundle)
        val wait = steps.firstOrNull { it.service != null && it.to == it.from && it.event == null }
            ?: return println("the export's main path has no internal result")
        val state = bundle.state(wait.from)
        val tree = ExportWalk.tree(bundle, wait.from)
        val service = wait.service!!
        val result = wait.result!!
        val invoke = state.invoke!!
        val mapping = invoke.on.getValue(result.type)
        val ruled = bundle.screens.getValue(tree.id).rules.flatMap { paths(it.condition) }.toSet()
        val markers = mutableMapOf<String, String>()
        val payload = JsonObject(
            result.payload + mapping.assign.mapNotNull { op ->
                val set = op as? eu.europa.ec.ux.runtime.bundle.AssignOp.Set ?: return@mapNotNull null
                val field = (set.from as? eu.europa.ec.ux.runtime.bundle.ValueSource.Result)?.field ?: return@mapNotNull null
                val value: JsonElement = if (set.path in ruled && bundle.context[set.path]?.nullable == true) {
                    JsonNull
                } else {
                    JsonPrimitive("_test-$field").also { markers[set.path] = it.content }
                }
                field to value
            },
        )
        val answer = ServiceResult(result.type, payload)
        val context = ExportWalk.mainPath(bundle, overrides = mapOf(service to answer)).first { it.from == wait.from && it.service != null }.context
        val event = ExportWalk.mainEvent(bundle, state)
        runComposeUiTest {
            val port = FakePort().apply { auto += ExportWalk.mainResults(bundle) - service }
            host(Fixture.root, port)
            walkTo(bundle, steps, wait.from)
            val go = event?.let { ExportWalk.emitter(tree, it) }
            // The main event is not accepted while the call is pending (unless the bundle says so), so its node is drawn disabled.
            waitUntil(timeoutMillis = TIMEOUT) { port.waiting(service) == 1 }
            val pendingOk = invoke.acceptsWhilePending?.contains(event) != false
            if (go != null && !pendingOk) onNodeWithTag(go.id, useUnmergedTree = true).assertIsNotEnabled()
            runOnIdle { port.complete(service, answer) }
            waitForIdle()
            go?.let { onNodeWithTag(it.id, useUnmergedTree = true).assertIsEnabled() }
            assertDrawnFromBundle(Fixture.root, bundle, wait.from, context = context)
            // A path bound by a drawn node shows its marker.
            val hidden = ruleHidden(bundle, tree, context) + ExportWalk.undrawn(Fixture.root, tree)
            val bound = tree.nodes.filter { it.id !in hidden }.flatMap { n -> n.props.values.mapNotNull { ((it as? JsonObject)?.get("bind") as? JsonPrimitive)?.content } }.toSet()
            markers.filterKeys { it in bound }.values.forEach { assertTrue(count(hasText(it, substring = true)) > 0, "the bound value $it is drawn") }
        }
    }

    private fun paths(c: eu.europa.ec.ux.runtime.bundle.Condition): List<String> = when (c) {
        is eu.europa.ec.ux.runtime.bundle.Condition.Leaf -> listOfNotNull(c.path)
        is eu.europa.ec.ux.runtime.bundle.Condition.All -> c.items.flatMap(::paths)
        is eu.europa.ec.ux.runtime.bundle.Condition.AnyOf -> c.items.flatMap(::paths)
        is eu.europa.ec.ux.runtime.bundle.Condition.Not -> paths(c.item)
    }

    /** The first state of the main path that waits for the user, and the step that leaves it. */
    private fun firstChoice(bundle: FlowBundle): ExportWalk.Step? = ExportWalk.mainPath(bundle).firstOrNull { it.service == null }

    /** The service answers of the main path before [step], so the flow stops where [step] starts (later calls wait). */
    private fun resultsBefore(step: ExportWalk.Step): Map<String, ServiceResult> =
        ExportWalk.mainPath().takeWhile { it != step }.mapNotNull { s -> s.service?.let { it to s.result!! } }.toMap()

    /** The engine's context when the main path reaches [state] (null when it starts there). */
    private fun contextAt(state: String): JsonObject? = ExportWalk.mainPath().lastOrNull { it.to == state }?.context

    @Test
    fun aHandWrittenScreenGetsTheSnapshotAndSendsIntentsOnly() {
        val choice = firstChoice(Fixture.bundle()) ?: return println("the export's main path has no user step")
        val consent = Fixture.bundle().state(choice.from).screen ?: return println("${choice.from} has no screen")
        val context = contextAt(choice.from) ?: return println("the main path starts at ${choice.from}")
        runComposeUiTest {
            val port = FakePort().apply { auto += resultsBefore(choice) }
            var seen: JsonObject? = null
            val bundle = host(
                Fixture.root,
                port,
                custom = mapOf(
                    consent to { scope ->
                        seen = scope.snapshot
                        BasicText("custom", Modifier.testTag("custom").clickable { scope.send(choice.event!!) })
                    },
                ),
            )
            awaitState(choice.from)
            onNodeWithTag("custom").performClick()
            awaitState(choice.to)
            val snapshot = assertNotNull(seen)
            // The snapshot is the context paths the bundle declares for that screen, with the engine's values.
            val declared = (Fixture.root.getValue("context").jsonObject).filterValues { f ->
                ((f as JsonObject)["declared_by"] as? JsonArray).orEmpty().any { it.jsonPrimitive.content == consent }
            }.keys
            assertTrue(declared.isNotEmpty(), "the bundle declares the screen's data")
            assertEquals(declared.map { it.trim('/').substringBefore('/') }.toSet(), snapshot.keys)
            declared.forEach { p -> assertEquals(FlowContext.get(context, p), FlowContext.get(snapshot, p), p) }
            assertTrue(lines.any { it.startsWith("screen $consent: hand-written (hook)") })
            assertTrue(lines.any { it.startsWith("intent from $consent: ${choice.event}") })
            assertTrue(bundle.screens.containsKey(consent))
        }
    }

    @Test
    fun aHandWrittenScreenDrawsItsBundleNodesThroughTheInterpreter() {
        // RULING-W1 / week-0 F3 (M8r): ScreenScope.Node draws a node of the screen's tree by id, with bindings, copy and
        // the screen rules. Node ids are read from the bundle (every top-level node of the consent tree); a prop planted
        // in the bundle (the `logo` the registry's EL-rp-identity takes) is drawn with no change to the hand-written screen.
        val choice = firstChoice(Fixture.bundle()) ?: return println("the export's main path has no user step")
        val consent = Fixture.bundle().state(choice.from).screen ?: return println("${choice.from} has no screen")
        val context = contextAt(choice.from) ?: return println("the main path starts at ${choice.from}")
        val sites = ExportWalk.sites(screen = consent).filter {
            it.node.element == RP_IDENTITY && UxElementRegistry.elementSchemas[RP_IDENTITY]?.props?.containsKey("logo") == true
        }
        if (sites.isEmpty()) return println("$consent has no $RP_IDENTITY node")
        var root = Fixture.root
        val logoSites = sites
        logoSites.forEach { root = root.with("${it.pointer}/props/logo", JsonPrimitive(LOGO)) }
        val bundle0 = Fixture.bundle(root)
        val tree = ScreenCatalog(bundle0).screens.getValue(consent)
        val expected = sites.map { it.node.id }.toSet() - ruleHidden(bundle0, tree, context)
        runComposeUiTest {
            host(
                root,
                FakePort().apply { auto += resultsBefore(choice) },
                custom = mapOf(
                    consent to { scope ->
                        Column {
                            sites.forEach { scope.Node(it.node.id) }
                            scope.Node("_test-no-such-node")
                        }
                    },
                ),
            )
            awaitState(choice.from)
            waitForIdle()
            // The rules decide which nodes show in this context, exactly as the interpreter's own drawing would.
            val drawn = sites.map { it.node.id }.filter { count(hasTestTag(it)) > 0 }.toSet()
            assertEquals(expected, drawn)
            logoSites.filter { it.node.id in drawn }.forEach { site ->
                val name = ((site.node.props["name"] as? JsonObject)?.get("bind") as? JsonPrimitive)?.content
                val value = name?.let { FlowContext.get(context, it) as? JsonPrimitive }?.takeIf { it.isString }?.content
                value?.let { onNodeWithTag("${site.node.id}.name", useUnmergedTree = true).assert(hasText(it)) }
                val logo = onNodeWithTag("${site.node.id}.logo", useUnmergedTree = true).fetchSemanticsNode().config
                assertTrue(SemanticsProperties.HideFromAccessibility in logo, "the logo is decorative")
                assertEquals(null, logo.getOrNull(SemanticsProperties.ContentDescription))
            }
            assertTrue(lines.any { it.contains("has no node '_test-no-such-node'") }, "$lines")
            assertEquals(0, count(SemanticsMatcher("error node") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(InterpreterTags.ERROR_PREFIX) == true }))
        }
    }

    /** The entry state's screen and its first top-level node without slots, for the planted-error tests. */
    private fun entrySite(): Pair<String, ExportWalk.Site> {
        val screen = Fixture.bundle().state(ExportWalk.entryState()).screen!!
        return screen to ExportWalk.sites(screen = screen).first { it.node.slots.isEmpty() }
    }

    @Test
    fun anUnknownElementIsAVisiblePlaceholder() = runComposeUiTest {
        val (_, site) = entrySite()
        val root = Fixture.root.with("${site.pointer}/element", JsonPrimitive("EL-not-there"))
        host(root, FakePort())
        awaitState(ExportWalk.entryState())
        waitForIdle()
        assertEquals(1, count(hasTestTag(InterpreterTags.UNKNOWN_ELEMENT_PREFIX + site.node.id)))
    }

    @Test
    fun anUnknownVariantDrawsATaggedErrorNodeAndLogsIt() = runComposeUiTest {
        val (screen, _) = entrySite()
        val (site, prop, _) = ExportWalk.variantSite(screen = screen)
        val root = Fixture.root.with("${site.pointer}/props/$prop", JsonPrimitive("bogus"))
        host(root, FakePort())
        awaitState(ExportWalk.entryState())
        waitForIdle()
        assertEquals(1, count(hasTestTag(InterpreterTags.ERROR_PREFIX + site.node.id)))
        assertEquals(0, count(hasTestTag(site.node.id)), "the element must not be drawn with a silent default")
        assertTrue(lines.any { it.contains("${site.node.element} ${site.node.id}") && it.contains("unknown variant 'bogus'") }, "$lines")
        assertTrue(lines.any { it.contains("1 schema problems") }, "the state-entry log names the problem")
    }

    @Test
    fun anUnknownPropDrawsATaggedErrorNode() = runComposeUiTest {
        val (screen, site) = entrySite()
        val other = ExportWalk.sites(screen = screen).firstOrNull { it.node.id != site.node.id }
        val root = Fixture.root.with("${site.pointer}/props/_test_colour", JsonPrimitive("red"))
        host(root, FakePort())
        awaitState(ExportWalk.entryState())
        waitForIdle()
        assertEquals(1, count(hasTestTag(InterpreterTags.ERROR_PREFIX + site.node.id)))
        assertTrue(lines.any { it.contains("unknown prop '_test_colour'") }, "$lines")
        other?.let { assertEquals(1, count(hasTestTag(it.node.id)), "the other nodes of the screen still draw") }
    }

    @Test
    fun anUnknownRegionOfATemplateDrawsATaggedErrorNode() = runComposeUiTest {
        val (screen, site) = entrySite()
        val nodes = ((Fixture.root["screens"] as JsonObject)[screen] as JsonObject)["regions"]!!.jsonObject.getValue(site.region)
        val root = Fixture.root.with("/screens/$screen/regions/_test-region", nodes)
        host(root, FakePort())
        awaitState(ExportWalk.entryState())
        waitForIdle()
        assertEquals(1, count(hasTestTag(InterpreterTags.ERROR_PREFIX + screen)))
        assertTrue(lines.any { it.contains("unknown region '_test-region'") }, "$lines")
    }

    @Test
    fun everyScreenOfTheBundleUsesOnlyRegisteredParts() {
        val catalog = ScreenCatalog(Fixture.bundle())
        assertEquals(Fixture.bundle().screens.keys, catalog.screens.keys)
        assertTrue(catalog.problems.isEmpty(), "${catalog.problems}")
        catalog.screens.values.forEach { tree ->
            assertTrue(tree.template in UxElementRegistry.templates, "${tree.id}: template ${tree.template}")
            tree.nodes.forEach { assertTrue(it.element in UxElementRegistry.elements, "${tree.id}/${it.id}: ${it.element}") }
        }
    }

    private companion object {
        const val TIMEOUT = 5_000L
        const val LOGO = "https://www.example.sk/rp/logo"

        /** The registry element whose `logo` prop the F3 route draws (an element API name of :ux-elements, not a spec value). */
        const val RP_IDENTITY = "EL-rp-identity"
    }
}
