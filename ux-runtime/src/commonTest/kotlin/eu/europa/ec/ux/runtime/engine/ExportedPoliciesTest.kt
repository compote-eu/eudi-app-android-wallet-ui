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


// M9r (spike rule T1): D1-engine and D2-engine on the real export, with no state named. Every non-final state of the
// exported bundle is entered and pressed Back / killed, and the outcome must be the one its own `back`, `restore` and the
// bundle's `process_death` declare. A spec that changes a policy changes the expectation, not this test.
package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.bundle.BackPolicy
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.InvokeScope
import eu.europa.ec.ux.runtime.bundle.ProcessDeathPolicy
import eu.europa.ec.ux.runtime.bundle.RestorePolicy
import eu.europa.ec.ux.runtime.bundle.StateDef
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.stateId
import eu.europa.ec.ux.runtime.support.with
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class ExportedPoliciesTest {

    private val bundle = Fixture.bundle()
    private val steps: List<StateDef> = bundle.states.values.filterNot { it.isFinal }

    private fun nonIdempotentCalls(port: FakePort): Int =
        bundle.services.values.filterNot { it.idempotent }.sumOf { port.calls(it.name) }

    private fun snapshotAt(b: FlowBundle, state: String, history: List<String> = listOf(state)) =
        FlowSnapshot(b.schemaVersion, b.specHash, state, eu.europa.ec.ux.runtime.engine.FlowContext.initial(b.context), history, 7)

    private fun isStateScoped(s: StateDef) = s.invoke?.scope == InvokeScope.STATE

    @Test
    fun everyStateRestoresAsItsRestorePolicySaysAndNeverRecallsANonIdempotentService() = runTest {
        assertTrue(steps.isNotEmpty())
        steps.forEach { s ->
            FlowPlatform.entries.forEach { platform ->
                val port = FakePort()
                val engine = FlowEngine(bundle, port, backgroundScope)
                val result = engine.restore(FlowSnapshot.fromJson(snapshotAt(bundle, s.id).toJson()), platform)
                runCurrent()
                val policy = if (platform == FlowPlatform.IOS) bundle.processDeath.ios else bundle.processDeath.android
                val at = "${s.id} on $platform"
                if (policy == ProcessDeathPolicy.ABANDON) {
                    assertEquals(RestoreResult.Abandoned(AbandonReason.PLATFORM_POLICY), result, at)
                    assertEquals(bundle.abandonState, assertIs<FlowStatus.Exited>(engine.current.value).exit?.state, at)
                    assertEquals(0, port.total, at)
                    return@forEach
                }
                val expected = when (val r = s.restore) {
                    RestorePolicy.Snapshot, null -> RestoreResult.Restored(s.id, reinvoked = isStateScoped(s))
                    is RestorePolicy.To -> RestoreResult.Restored(r.target, reinvoked = false)
                    is RestorePolicy.ToHistory -> RestoreResult.Restored(r.fallback, reinvoked = false)
                }
                assertEquals(expected, result, at)
                assertEquals(0, nonIdempotentCalls(port), "$at: a non-idempotent service was called again")
            }
        }
    }

    @Test
    fun aToHistoryRestoreTakesTheLastVisitedCandidate() = runTest {
        steps.forEach { s ->
            val r = s.restore as? RestorePolicy.ToHistory ?: return@forEach
            r.candidates.forEach { c ->
                val engine = FlowEngine(bundle, FakePort(), backgroundScope)
                val result = engine.restore(snapshotAt(bundle, s.id, listOf(c, s.id)), FlowPlatform.ANDROID)
                if (bundle.processDeath.android == ProcessDeathPolicy.ABANDON) return@forEach
                assertEquals(RestoreResult.Restored(c, reinvoked = false), result, "${s.id} after $c")
            }
        }
    }

    /**
     * The export with every state restorable by snapshot (and so every service idempotent, which the loader then
     * requires), so the test can put the engine in any state; the back policies are the export's own.
     */
    private val anyState: FlowBundle by lazy {
        var root = Fixture.root
        bundle.states.values.filterNot { it.isFinal }.forEach { root = root.with("/states/${it.id}/restore", buildJsonObject { put("policy", "snapshot") }) }
        bundle.services.keys.forEach { root = root.with("/services/$it/idempotent", JsonPrimitive(true)) }
        root = root.with("/process_death/android", JsonPrimitive("restore-per-state"))
        Fixture.bundle(root)
    }

    private fun TestScope.enter(state: String, history: List<String> = listOf(state)): Pair<FlowEngine, FakePort> {
        val port = FakePort()
        val engine = FlowEngine(anyState, port, backgroundScope)
        assertEquals(RestoreResult.Restored(state, reinvoked = isStateScoped(anyState.state(state))), engine.restore(snapshotAt(anyState, state, history), FlowPlatform.ANDROID))
        runCurrent()
        return engine to port
    }

    @Test
    fun everyStateTakesBackAsItsBackPolicySays() = runTest {
        steps.forEach { s ->
            val (engine, port) = enter(s.id)
            val gen = (engine.current.value as FlowStatus.Active).gen
            val pending = (engine.current.value as FlowStatus.Active).pending
            val result = engine.back()
            runCurrent()
            val at = "back in ${s.id}"
            when (val p = s.back) {
                is BackPolicy.Event -> {
                    val fired = assertIs<BackResult.Fired>(result, at)
                    assertEquals(p.event, fired.event, at)
                    val accepts = s.invoke?.acceptsWhilePending
                    when (val o = fired.outcome) {
                        is EventResult.Transitioned -> assertTrue(o.target in s.on.getValue(p.event).mapNotNull { it.target }, "$at: $o")
                        EventResult.Internal -> assertTrue(s.on.getValue(p.event).any { it.target == null }, "$at: internal")
                        is EventResult.Dropped -> assertTrue(
                            (o.reason == DropReason.PENDING && pending && accepts != null && p.event !in accepts) || o.reason == DropReason.NO_BRANCH,
                            "$at: $o",
                        )
                    }
                }
                is BackPolicy.To -> {
                    assertEquals(BackResult.Moved(p.target), result, at)
                    assertEquals(p.target, engine.stateId(), at)
                }
                is BackPolicy.ToHistory -> {
                    assertEquals(BackResult.Moved(p.fallback), result, "$at with no candidate in the history")
                    p.candidates.forEach { c ->
                        val (again, _) = enter(s.id, listOf(c, s.id))
                        assertEquals(BackResult.Moved(c), again.back(), "$at after $c")
                    }
                }
                BackPolicy.Blocked -> {
                    assertEquals(BackResult.Blocked, result, at)
                    assertEquals(s.id, engine.stateId(), at)
                    assertEquals(gen, (engine.current.value as FlowStatus.Active).gen, at)
                    assertTrue((engine.current.value as FlowStatus.Active).backEnabled, "$at: blocked keeps the handler enabled")
                    assertEquals(0, port.cancelled.values.sum(), "$at: nothing is cancelled")
                }
                BackPolicy.Pass, null -> {
                    assertEquals(BackResult.Pass, result, at)
                    assertEquals(false, (engine.current.value as FlowStatus.Active).backEnabled, at)
                }
            }
        }
    }

    @Test
    fun leavingAStateByBackCancelsItsPendingCall() = runTest {
        steps.filter { isStateScoped(it) && (it.back is BackPolicy.To || it.back is BackPolicy.ToHistory) }.forEach { s ->
            val (engine, port) = enter(s.id)
            engine.back()
            runCurrent()
            assertEquals(1, port.cancelled[s.invoke!!.service], "back in ${s.id}")
        }
    }

    @Test
    fun theExportUnderTestIsLoaded() {
        val raw: JsonObject = Fixture.root
        assertEquals(bundle.states.keys, raw.getValue("states").jsonObject.keys)
        if (steps.isEmpty()) fail("the export has no step state")
    }
}
