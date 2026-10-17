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

// M4 test (was ProvisionalFakeScriptsTest), re-run at M6a on the exported scripts and bundle (ux-spec/); M9r (spike rule
// T1): no state, event, service or result of the spec is named here. Each exported script is played through the real
// engine, the user taking the bundle's main step wherever the flow waits, and the run is checked against what the bundle
// itself declares. Host only: it reads the files from disk.
package eu.europa.ec.uxspike

import eu.europa.ec.ux.contract.pres.PresCrossDeviceInvoker
import eu.europa.ec.ux.contract.pres.PresCrossDeviceSpec
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.LoadResult
import eu.europa.ec.ux.runtime.engine.FlowEngine
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowStatus
import eu.europa.ec.ux.runtime.engine.ServiceResult
import eu.europa.ec.ux.theme.generated.UxThemeSpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Plays every exported script through the real engine on the exported bundle, behind the ServicePort seam. */
class FakeScriptsTest {

    /** The export the build embeds (`ux.spec.dir`, set by the Gradle test task; M9r regen-smoke points it at a scratch copy). */
    private val spec = File(System.getProperty("ux.spec.dir") ?: error("ux.spec.dir is not set"))
    private val scriptsDir = File(spec, "fixtures/scripts")

    private val bundle: FlowBundle by lazy {
        val bytes = File(spec, "resources/${PresCrossDeviceSpec.BUNDLE_PATH}").readBytes()
        val r = FlowBundle.load(bytes, PresCrossDeviceSpec.FLOW_ID, PresCrossDeviceSpec.SPEC_HASH, UxThemeSpec.SPEC_HASH)
        (r as? LoadResult.Loaded)?.bundle ?: fail("bundle refused: $r")
    }

    private val root: JsonObject by lazy { Json.parseToJsonElement(File(spec, "resources/${PresCrossDeviceSpec.BUNDLE_PATH}").readText()).jsonObject }

    /** The script names UX_SPEC.lock lists under fixtures/scripts/ (M8r: read from the export, not pinned here). */
    private val exportedScripts: Set<String> by lazy {
        val lock = Json.parseToJsonElement(File(spec, "UX_SPEC.lock").readText()).jsonObject
        lock.getValue("files").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }
            .filter { it.startsWith(SCRIPTS_PREFIX) && it.endsWith(".json") }
            .map { it.removePrefix(SCRIPTS_PREFIX).removeSuffix(".json") }
            .toSet()
    }

    private fun script(name: String) = FakeScript.parse(File(scriptsDir, "$name.json").readText())

    /** The entry the drills open: the first entry whose state invokes a service (the link), with a sample launch. */
    private val entry by lazy { bundle.entries.values.first { bundle.state(it.state).invoke != null } }

    /** The bundle's main step where the flow waits for the user: a payload-free event with a `main` branch. */
    private fun mainEvent(state: String): String? {
        val s = bundle.state(state)
        val produced = s.invoke?.on?.values?.mapNotNull { it.event }.orEmpty().toSet()
        return s.on.keys.firstOrNull { e ->
            e !in produced && bundle.events[e]?.payload.isNullOrEmpty() && s.on.getValue(e).any { it.kind == "main" && it.target != null }
        }
    }

    private class Run(val visited: List<String>, val end: FlowStatus, val player: FakeScriptPlayer, val log: List<String>)

    private fun TestScope.play(name: String, s: FakeScript = script(name)): Run {
        val log = mutableListOf<String>()
        val services = FakePresCrossDeviceServices(FakeScriptPlayer(s))
        val engine = FlowEngine(bundle, services.port(), backgroundScope, FlowLog { log += it })
        val visited = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.current.collect { st -> if (st is FlowStatus.Active && visited.lastOrNull() != st.state) visited += st.state }
        }
        val launch = JsonObject(entry.assign.mapNotNull { a -> (a as? eu.europa.ec.ux.runtime.bundle.AssignOp.Set)?.from }
            .filterIsInstance<eu.europa.ec.ux.runtime.bundle.ValueSource.Launch>().associate { it.arg to JsonPrimitive("test://launch") })
        engine.start(entry.name, launch, unlocked = true)
        repeat(MAX_STEPS) {
            settle()
            val st = engine.current.value as? FlowStatus.Active ?: return Run(visited, engine.current.value, services.player, log)
            if (st.pending) return Run(visited, st, services.player, log)
            val event = mainEvent(st.state) ?: return Run(visited, st, services.player, log)
            engine.dispatch(event, JsonObject(emptyMap()), st.gen)
        }
        fail("$name did not settle: $visited")
    }

    /** The engine runs in backgroundScope, which advanceUntilIdle does not wait for: move virtual time instead. */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MS)
        runCurrent()
    }

    @Test
    fun theExportedScriptsExistForThisFlowAndNameOnlyItsServicesAndResults() = runTest {
        val names = scriptsDir.listFiles().orEmpty().map { it.nameWithoutExtension }.toSet()
        assertTrue(exportedScripts.isNotEmpty(), "UX_SPEC.lock lists no script")
        assertEquals(exportedScripts, names)
        names.forEach { name ->
            val s = script(name)
            assertEquals(name, s.script)
            assertEquals(PresCrossDeviceSpec.FLOW_ID, s.flowId)
            assertEquals(bundle.specHash, s.specHash, "$name is from another export")
            s.calls.forEach { (service, steps) ->
                val svc = bundle.services[service] ?: fail("$name names a service the bundle lacks: $service")
                steps.mapNotNull { it.result?.get("type")?.jsonPrimitive?.content }.forEach {
                    assertTrue(it in svc.results, "$name: $service has no result $it")
                }
            }
        }
    }

    @Test
    fun everyScriptSettlesWhereTheBundleLetsItAndCallsNoNonIdempotentServiceTwice() = runTest {
        exportedScripts.sorted().forEach { name ->
            val run = play(name)
            assertEquals(entry.state, run.visited.firstOrNull(), "$name starts at the entry state")
            when (val end = run.end) {
                is FlowStatus.Exited -> assertTrue(bundle.state(end.exit!!.state).isFinal, "$name exits in a final state")
                is FlowStatus.Active -> assertTrue(
                    end.pending || mainEvent(end.state) == null,
                    "$name stops in ${end.state}, which still has a main step",
                )
                FlowStatus.Idle -> fail("$name never started")
            }
            // A step answered at once only goes along the bundle's own edges.
            run.visited.zipWithNext().forEach { (a, b) ->
                val s = bundle.state(a)
                val targets = s.on.values.flatten().mapNotNull { it.target }
                assertTrue(b in targets, "$name: $a -> $b is not an edge of the bundle")
            }
            bundle.services.values.filterNot { it.idempotent }.forEach { svc ->
                assertTrue(run.player.calls(svc.name) <= 1, "$name calls the non-idempotent ${svc.name} ${run.player.calls(svc.name)} times")
            }
            assertTrue(run.log.none { it.startsWith("contract error") }, "$name: ${run.log.filter { it.startsWith("contract error") }}")
            // Every service called is one the script scripts (an unscripted call would throw into the error branch).
            run.player.script.calls.keys.let { scripted ->
                bundle.services.keys.filter { run.player.calls(it) > 0 }.forEach { assertTrue(it in scripted, "$name calls the unscripted $it") }
            }
        }
    }

    @Test
    fun aFailingStepFollowsTheErrorBranch() = runTest {
        // RULING-2 through the fake: a scripted throw in the entry state's service lands where its Error result leads.
        val inv = bundle.state(entry.state).invoke!!
        val event = inv.on.getValue(ServiceResult.UNEXPECTED_ERROR_TYPE).event!!
        val target = bundle.state(entry.state).on.getValue(event).first().target
        val base = script(exportedScripts.sorted().first())
        val s = base.copy(calls = base.calls + (inv.service to listOf(FakeStep(fail = "io"))))
        val run = play("planted", s)
        assertTrue(target in run.visited || (run.end as? FlowStatus.Exited)?.exit?.state == target, "${run.visited} reaches $target")
    }

    /**
     * M4 open issue (was :ux-contract InvokerDefaultsTest, which named the contract's types): the generated invoker sends
     * every field the bundle declares for a result, defaults included, so the engine never has to guess them. Checked for
     * every result an exported script answers with.
     */
    @Test
    fun theInvokerEncodesEveryFieldTheBundleDeclares() = runTest {
        val services = root.getValue("services").jsonObject
        var checked = 0
        exportedScripts.sorted().forEach { name ->
            val s = script(name)
            s.calls.forEach { (service, steps) ->
                steps.forEachIndexed { i, step ->
                    val type = step.result?.get("type")?.jsonPrimitive?.content ?: return@forEachIndexed
                    val one = s.copy(calls = mapOf(service to listOf(step)))
                    val args = JsonObject(bundle.services.getValue(service).args.mapValues { (_, t) -> if ("list" in t) JsonArray(emptyList()) else JsonPrimitive("test://arg") })
                    val encoded = PresCrossDeviceInvoker(FakePresCrossDeviceServices(FakeScriptPlayer(one))).invoke(service, args)
                    assertEquals(JsonPrimitive(type), encoded["type"], "$name $service#$i")
                    val schema = services.getValue(service).jsonObject.getValue("results").jsonObject.getValue(type).jsonObject
                    assertFields("$name $service#$i $type", schema, encoded)
                    checked++
                }
            }
        }
        assertTrue(checked > 0)
    }

    /** Every field of [schema] (a result's or an object's `fields`) is a key of [value]; objects and list items recurse. */
    private fun assertFields(at: String, schema: JsonObject, value: JsonObject) {
        schema.forEach { (field, spec) ->
            assertTrue(field in value, "$at: field '$field' is not encoded: $value")
            val o = spec as? JsonObject ?: return@forEach
            val v: JsonElement = value.getValue(field)
            (o["fields"] as? JsonObject)?.let { f -> (v as? JsonObject)?.let { assertFields("$at.$field", f, it) } }
            (o["item"] as? JsonObject)?.let { item -> (v as? JsonArray)?.forEach { e -> (e as? JsonObject)?.let { assertFields("$at.$field[]", item, it) } } }
        }
    }

    private companion object {
        const val MAX_STEPS = 40
        const val SETTLE_MS = 120_000L
        const val SCRIPTS_PREFIX = "fixtures/scripts/"
    }
}
