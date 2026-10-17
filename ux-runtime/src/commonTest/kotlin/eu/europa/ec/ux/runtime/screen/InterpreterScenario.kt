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

// M5b, re-run at M6a on the real export (was provisional on the boundary fixture): the interpreter on the exported
// bundle and its added-state test copy (built from ux-spec/ at test time since M8r).
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
import eu.europa.ec.ux.runtime.engine.FlowExit
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.engine.ServiceResult
import eu.europa.ec.ux.runtime.host.FlowHost
import eu.europa.ec.ux.runtime.host.PlaceholderTags
import eu.europa.ec.ux.runtime.support.AddedStateFixture
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.QR_URI
import eu.europa.ec.ux.runtime.support.Results
import eu.europa.ec.ux.runtime.support.TestCopy
import eu.europa.ec.ux.runtime.support.expectedTexts
import eu.europa.ec.ux.runtime.support.obj
import eu.europa.ec.ux.runtime.support.unlinked
import eu.europa.ec.ux.runtime.support.visibleNodeIds
import eu.europa.ec.ux.runtime.support.with
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The screen interpreter (SPIKE-PLAN §1.4), asserted on the semantics tree. Screen ids, node ids and texts are read
 * from the bundle, so no test here names a screen. Test (i): non-consent screens drawn from bundle data alone;
 * test (ii): a state that exists only in a test copy of the bundle renders and transitions with no Kotlin change.
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

    /** Every visible node of the state's screen is drawn under its id, with its copy, and nothing is a placeholder. */
    private fun ComposeUiTest.assertDrawnFromBundle(root: JsonObject, bundle: FlowBundle, state: String, hidden: Set<String> = emptySet()) {
        val tree = assertNotNull(ScreenCatalog(bundle).screens[bundle.state(state).screen], "no tree for $state")
        val absent = hidden + unlinked(root, tree)
        waitForIdle()
        visibleNodeIds(tree, absent).forEach { id -> assertTrue(count(hasTestTag(id)) > 0, "node $id of ${tree.id} not drawn") }
        absent.forEach { id -> assertEquals(0, count(hasTestTag(id)), "node $id of ${tree.id} should be absent") }
        val texts = expectedTexts(root, tree, absent)
        assertTrue(texts.isNotEmpty(), "${tree.id} has copy")
        texts.forEach { (id, text) ->
            assertTrue(count(hasText(text) or hasContentDescription(text)) > 0, "copy of $id ('$text') not drawn on ${tree.id}")
        }
        assertEquals(0, count(SemanticsMatcher("unknown part") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("ux-unknown-") == true }))
        assertEquals(0, count(hasTestTag(PlaceholderTags.STATE)), "the M4 placeholder must not be drawn")
        assertTrue(lines.any { it.startsWith("screen ${tree.id}: interpreted from the bundle") }, "interpreter log for ${tree.id}")
    }

    @Test
    fun connectingIsDrawnFromTheBundle() = runComposeUiTest {
        val port = FakePort() // fetchRequest stays pending
        val bundle = host(Fixture.root, port)
        awaitState("connecting")
        assertDrawnFromBundle(Fixture.root, bundle, "connecting")
        val tree = ScreenCatalog(bundle).screens.getValue(bundle.state("connecting").screen!!)
        // The live region and the announce-live hook come from the bundle's a11y fields.
        onNodeWithTag(tree.a11y.liveRegion!!, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        waitUntil(timeoutMillis = TIMEOUT) { lines.any { it.startsWith("a11y: ${tree.id} announce-live") } }
        // Its one action emits the event the node declares; the engine moves on.
        val action = tree.regions.getValue("actions").single()
        onNodeWithTag(action.id, useUnmergedTree = true).assertIsEnabled().performClick()
        awaitState(bundle.state("connecting").on.getValue(action.events.single().emits).first().target!!)
        assertEquals(1, port.calls("cancel"))
    }

    @Test
    fun outcomeExpiredIsDrawnFromTheBundleAndFocusesItsHeading() = runComposeUiTest {
        val port = FakePort().apply { auto["fetchRequest"] = ServiceResult("Expired") }
        val bundle = host(Fixture.root, port)
        val state = bundle.state("connecting").on.getValue("SYS_EXPIRED").first().target!!
        awaitState(state)
        assertDrawnFromBundle(Fixture.root, bundle, state)
        val tree = ScreenCatalog(bundle).screens.getValue(bundle.state(state).screen!!)
        val title = TestCopy.sk(Fixture.root, (tree.node(tree.a11y.heading!!)!!.props.getValue("title") as JsonObject).getValue("copy").jsonPrimitive.content)!!
        assertTrue(count(isHeading() and hasText(title)) > 0, "the heading node is a heading")
        waitUntil(timeoutMillis = TIMEOUT) { lines.any { it.startsWith("a11y: ${tree.id} focus-heading") } }
        waitForIdle()
        val focused = onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().map { it.config.toString() }
        assertTrue(count(isFocused() and hasText(title, substring = true)) > 0, "focus is on the heading after the state settled: ${lines.filter { it.startsWith("a11y") }} focused=$focused")
        val close = tree.regions.getValue("actions").first { n -> bundle.state(state).on.getValue(n.events.single().emits).first().target in bundle.states.filterValues { it.isFinal }.keys }
        onNodeWithTag(close.id, useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = TIMEOUT) { exits.isNotEmpty() }
        assertEquals(bundle.abandonState, exits.single()?.state)
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
        port.auto[AddedStateFixture.service] = Results.requestReady
        onNodeWithTag(leave.id, useUnmergedTree = true).performClick()
        awaitState("request")
        assertEquals(2, port.calls(AddedStateFixture.service))
    }

    @Test
    fun rulesAndBindingsFollowTheContext() = runComposeUiTest {
        val noUrl = ServiceResult("Ready", obj("""{"code":"4711","portal_url":null}"""))
        val port = FakePort().apply {
            auto["fetchRequest"] = Results.requestReady
            auto["authenticate"] = Results.authenticated
        }
        val bundle = host(Fixture.root, port)
        awaitState("request")
        onNodeWithTag("continue", useUnmergedTree = true).performClick()
        awaitState("verification-code")
        val tree = ScreenCatalog(bundle).screens.getValue(bundle.state("verification-code").screen!!)
        val confirm = tree.regions.getValue("actions").first { it.events.single().emits == "CONFIRM" }
        // CONFIRM is not accepted while verificationCode() is pending, so its node is drawn disabled.
        waitUntil(timeoutMillis = TIMEOUT) { port.waiting("verificationCode") == 1 }
        onNodeWithTag(confirm.id, useUnmergedTree = true).assertIsNotEnabled()
        runOnIdle { port.complete("verificationCode", noUrl) }
        waitForIdle()
        onNodeWithTag(confirm.id, useUnmergedTree = true).assertIsEnabled()
        // RUL-…-url-check-visible hides the URL check when /verifier/portal_url is empty; the code is bound data.
        val roots = bundle.screens.getValue(tree.id).rules.flatMap { r -> r.effects.mapNotNull { it.raw["node"]?.jsonPrimitive?.content } }
        val hidden = roots.flatMap { id -> tree.node(id)!!.walk().map(ScreenNode::id).toList() }.toSet()
        assertTrue(hidden.isNotEmpty())
        assertDrawnFromBundle(Fixture.root, bundle, "verification-code", hidden = hidden)
        assertTrue(count(hasText("4711")) > 0, "the bound code is drawn")
    }

    @Test
    fun aHandWrittenScreenGetsTheSnapshotAndSendsIntentsOnly() = runComposeUiTest {
        val port = FakePort().apply { auto["fetchRequest"] = Results.requestReady }
        val consent = Fixture.bundle().state("request").screen!!
        var seen: JsonObject? = null
        val bundle = host(
            Fixture.root,
            port,
            custom = mapOf(
                consent to { scope ->
                    seen = scope.snapshot
                    BasicText("custom", Modifier.testTag("custom").clickable { scope.send("CONTINUE") })
                },
            ),
        )
        awaitState("request")
        onNodeWithTag("custom").performClick()
        awaitState("auth")
        val snapshot = assertNotNull(seen)
        assertEquals(setOf("verifier", "request"), snapshot.keys)
        assertEquals("Verifier", (snapshot["verifier"] as JsonObject)["name"]?.jsonPrimitive?.content)
        assertEquals("Doc", (snapshot["request"] as JsonObject)["document_name"]?.jsonPrimitive?.content)
        assertTrue(lines.any { it.startsWith("screen $consent: hand-written (hook)") })
        assertTrue(lines.any { it.startsWith("intent from $consent: CONTINUE") })
        assertTrue(bundle.screens.containsKey(consent))
    }

    @Test
    fun aHandWrittenScreenDrawsItsBundleNodesThroughTheInterpreter() = runComposeUiTest {
        // RULING-W1 / week-0 F3 (M8r): ScreenScope.Node draws a node of the screen's tree by id, with bindings, copy and
        // the screen rules. Node ids are read from the bundle (every node of the consent tree); a prop planted in the
        // bundle (EL-rp-identity `logo`) is drawn with no change to the hand-written screen.
        val port = FakePort().apply { auto["fetchRequest"] = Results.requestReady }
        val consent = Fixture.bundle().state("request").screen!!
        val consentTree = ScreenCatalog(Fixture.bundle()).screens.getValue(consent)
        val identities = consentTree.nodes.filter { it.element == "EL-rp-identity" }
        assertTrue(identities.size >= 2, "the consent tree carries its EL-rp-identity nodes")
        var root = Fixture.root
        val body = ((Fixture.root["screens"] as JsonObject)[consent] as JsonObject)["regions"] as JsonObject
        body.forEach { (region, nodes) ->
            (nodes as JsonArray).forEachIndexed { i, n ->
                if ((n as JsonObject)["element"]?.jsonPrimitive?.content == "EL-rp-identity") {
                    root = root.with("/screens/$consent/regions/$region/$i/props/logo", JsonPrimitive(LOGO))
                }
            }
        }
        host(
            root,
            port,
            custom = mapOf(
                consent to { scope ->
                    Column {
                        identities.forEach { scope.Node(it.id) }
                        scope.Node("_test-no-such-node")
                    }
                },
            ),
        )
        awaitState("request")
        waitForIdle()
        // The rules show exactly the identity that matches /verifier/trust (trusted here); the other is hidden.
        val drawn = identities.filter { count(hasTestTag(it.id)) > 0 }
        assertEquals(1, drawn.size, "drawn: ${drawn.map { it.id }}")
        val id = drawn.single().id
        onNodeWithTag("$id.name", useUnmergedTree = true).assert(hasText("Verifier"))
        val logo = onNodeWithTag("$id.logo", useUnmergedTree = true).fetchSemanticsNode().config
        assertTrue(SemanticsProperties.HideFromAccessibility in logo, "the logo is decorative")
        assertEquals(null, logo.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(lines.any { it.contains("has no node '_test-no-such-node'") }, "$lines")
        assertEquals(0, count(SemanticsMatcher("error node") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(InterpreterTags.ERROR_PREFIX) == true }))
    }

    @Test
    fun anUnknownElementIsAVisiblePlaceholder() = runComposeUiTest {
        val screen = Fixture.bundle().state("connecting").screen!!
        val root = Fixture.root.with("/screens/$screen/regions/hero/0/element", JsonPrimitive("EL-not-there"))
        host(root, FakePort())
        awaitState("connecting")
        waitForIdle()
        assertEquals(1, count(hasTestTag(InterpreterTags.UNKNOWN_ELEMENT_PREFIX + "hero")))
    }

    @Test
    fun anUnknownVariantDrawsATaggedErrorNodeAndLogsIt() = runComposeUiTest {
        val screen = Fixture.bundle().state("connecting").screen!!
        val root = Fixture.root.with("/screens/$screen/regions/hero/0/props/tone", JsonPrimitive("bogus"))
        host(root, FakePort())
        awaitState("connecting")
        waitForIdle()
        assertEquals(1, count(hasTestTag(InterpreterTags.ERROR_PREFIX + "hero")))
        assertEquals(0, count(hasTestTag("hero")), "the element must not be drawn with a silent default")
        assertTrue(lines.any { it.contains("EL-status-hero hero") && it.contains("unknown variant 'bogus'") }, "$lines")
        assertTrue(lines.any { it.contains("1 schema problems") }, "the state-entry log names the problem")
    }

    @Test
    fun anUnknownPropDrawsATaggedErrorNode() = runComposeUiTest {
        val screen = Fixture.bundle().state("connecting").screen!!
        val root = Fixture.root.with("/screens/$screen/regions/actions/0/props/colour", JsonPrimitive("red"))
        host(root, FakePort())
        awaitState("connecting")
        waitForIdle()
        assertEquals(1, count(hasTestTag(InterpreterTags.ERROR_PREFIX + "cancel")))
        assertTrue(lines.any { it.contains("unknown prop 'colour'") }, "$lines")
        assertEquals(1, count(hasTestTag("hero")), "the other nodes of the screen still draw")
    }

    @Test
    fun anUnknownRegionOfATemplateDrawsATaggedErrorNode() = runComposeUiTest {
        val screen = Fixture.bundle().state("connecting").screen!!
        val regions = (Fixture.root["screens"] as JsonObject)[screen] as JsonObject
        val extra = (regions["regions"] as JsonObject)["hero"]!!
        val root = Fixture.root.with("/screens/$screen/regions/footer", extra)
        host(root, FakePort())
        awaitState("connecting")
        waitForIdle()
        assertEquals(1, count(hasTestTag(InterpreterTags.ERROR_PREFIX + screen)))
        assertTrue(lines.any { it.contains("unknown region 'footer'") }, "$lines")
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
    }
}
