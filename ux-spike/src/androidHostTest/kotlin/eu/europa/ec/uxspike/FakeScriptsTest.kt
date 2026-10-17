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

// M4 test (was ProvisionalFakeScriptsTest), re-run at M6a on the exported scripts and bundle (ux-spec/). Host only: it
// reads the files from disk.
package eu.europa.ec.uxspike

import eu.europa.ec.ux.contract.pres.AuthenticateResult
import eu.europa.ec.ux.contract.pres.PresCrossDeviceSpec
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.LoadResult
import eu.europa.ec.ux.runtime.engine.FlowEngine
import eu.europa.ec.ux.runtime.engine.FlowStatus
import eu.europa.ec.ux.theme.generated.UxThemeSpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Plays every exported script through the real engine on the exported bundle, behind the ServicePort seam. */
class FakeScriptsTest {

    private val module = listOf(File("."), File("ux-spike")).first { File(it, "src/commonMain/composeResources").isDirectory }
    private val scriptsDir = File(module, "../ux-spec/fixtures/scripts")

    private val bundle: FlowBundle by lazy {
        val bytes = File(module, "../ux-spec/resources/${PresCrossDeviceSpec.BUNDLE_PATH}").readBytes()
        val r = FlowBundle.load(bytes, PresCrossDeviceSpec.FLOW_ID, PresCrossDeviceSpec.SPEC_HASH, UxThemeSpec.SPEC_HASH)
        (r as? LoadResult.Loaded)?.bundle ?: fail("bundle refused: $r")
    }

    /** The script names UX_SPEC.lock lists under fixtures/scripts/ (M8r: read from the export, not pinned here). */
    private val exportedScripts: Set<String> by lazy {
        val lock = Json.parseToJsonElement(File(module, "../ux-spec/UX_SPEC.lock").readText()).jsonObject
        lock.getValue("files").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }
            .filter { it.startsWith(SCRIPTS_PREFIX) && it.endsWith(".json") }
            .map { it.removePrefix(SCRIPTS_PREFIX).removeSuffix(".json") }
            .toSet()
    }

    private fun script(name: String) = FakeScript.parse(File(scriptsDir, "$name.json").readText())

    /** The UI choice the drill takes in each state that waits for the user. */
    private val choice = mapOf(
        "request" to "CONTINUE",
        "request-untrusted" to "CONTINUE",
        "untrusted-confirm" to "APPROVE",
        "verification-code" to "CONFIRM",
        "success" to "CLOSE",
    )

    private class Run(val visited: List<String>, val end: FlowStatus, val player: FakeScriptPlayer)

    private fun TestScope.play(name: String): Run {
        val services = FakePresCrossDeviceServices(FakeScriptPlayer(script(name)))
        val engine = FlowEngine(bundle, services.port(), backgroundScope)
        val visited = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.current.collect { s -> if (s is FlowStatus.Active && visited.lastOrNull() != s.state) visited += s.state }
        }
        engine.start("deeplink", JsonObject(mapOf("uri" to JsonPrimitive("openid4vp://?client_id=spike"))), unlocked = true)
        repeat(MAX_STEPS) {
            settle()
            val s = engine.current.value as? FlowStatus.Active ?: return Run(visited, engine.current.value, services.player)
            val event = choice[s.state] ?: return Run(visited, s, services.player)
            engine.dispatch(event, JsonObject(emptyMap()), s.gen)
        }
        fail("$name did not settle: $visited")
    }

    /** The engine runs in backgroundScope, which advanceUntilIdle does not wait for: move virtual time instead. */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MS)
        runCurrent()
    }

    private fun Run.endState(): String? = when (val e = end) {
        is FlowStatus.Active -> e.state
        is FlowStatus.Exited -> e.exit?.state
        FlowStatus.Idle -> null
    }

    @Test
    fun theExportedScriptsExistForThisFlowAndAuthenticationAlwaysSucceeds() = runTest {
        val names = scriptsDir.listFiles().orEmpty().map { it.nameWithoutExtension }.toSet()
        assertTrue(exportedScripts.isNotEmpty(), "UX_SPEC.lock lists no script")
        assertEquals(exportedScripts, names)
        names.forEach { name ->
            val s = script(name)
            assertEquals(name, s.script)
            assertEquals(PresCrossDeviceSpec.FLOW_ID, s.flowId)
            assertEquals(bundle.specHash, s.specHash, "$name is from another export")
            assertTrue(s.calls.keys.all { it in bundle.services }, "$name names a service the bundle lacks: ${s.calls.keys}")
            // V23: a script leaves out a service it never reaches (doc-missing has fetchRequest only); where it scripts
            // authentication, authentication succeeds.
            if ("authenticate" in s.calls) {
                assertIs<AuthenticateResult.Authenticated>(FakeScriptPlayer(s).play("authenticate", AuthenticateResult.serializer()))
            }
        }
        assertEquals(setOf("fetchRequest"), script("doc-missing").calls.keys)
    }

    @Test
    fun happyPresentsOnceAndEndsDone() = runTest {
        val run = play("happy")
        assertEquals(listOf("connecting", "request", "auth", "verification-code", "sending", "success"), run.visited)
        assertEquals("done", run.endState())
        assertEquals(1, run.player.calls("send"))
        assertEquals(1, run.player.calls("fetchRequest"))
    }

    @Test
    fun slowEndsLikeHappy() = runTest {
        val run = play("slow")
        assertEquals("done", run.endState())
        assertEquals(1, run.player.calls("send"))
    }

    @Test
    fun expiredAtRequestEndsInErrorExpiredWithoutSending() = runTest {
        val run = play("expired-at-request")
        assertEquals("error-expired", run.endState())
        assertEquals(0, run.player.calls("send"))
    }

    @Test
    fun expiredAtSendingEndsInErrorExpiredAfterOneSend() = runTest {
        val run = play("expired-at-sending")
        assertEquals("error-expired", run.endState())
        assertTrue("sending" in run.visited)
        assertEquals(1, run.player.calls("send"))
    }

    @Test
    fun untrustedNeedsTheExplicitApproval() = runTest {
        val run = play("untrusted")
        assertEquals(listOf("connecting", "request-untrusted", "untrusted-confirm", "auth", "verification-code", "sending", "success"), run.visited)
        assertEquals("done", run.endState())
    }

    @Test
    fun docMissingStopsAtDocumentMissing() = runTest {
        val run = play("doc-missing")
        assertEquals("document-missing", run.endState())
        assertEquals(0, run.player.calls("authenticate"))
    }

    @Test
    fun aFailingStepFollowsTheErrorBranch() = runTest {
        // RULING-2 through the fake: a scripted throw in fetchRequest lands in the error state.
        val happy = script("happy")
        val s = happy.copy(calls = happy.calls + ("fetchRequest" to listOf(FakeStep(fail = "io"))))
        val services = FakePresCrossDeviceServices(FakeScriptPlayer(s))
        val engine = FlowEngine(bundle, services.port(), backgroundScope)
        engine.start("deeplink", JsonObject(mapOf("uri" to JsonPrimitive("x"))), unlocked = true)
        settle()
        assertEquals("error", (engine.current.value as FlowStatus.Active).state)
    }

    private companion object {
        const val MAX_STEPS = 40
        const val SETTLE_MS = 120_000L
        const val SCRIPTS_PREFIX = "fixtures/scripts/"
    }
}
