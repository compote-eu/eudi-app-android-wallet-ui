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


// M1b, re-run at M6a on the real export; M9r (spike rule T1): back/restore, entries and exits as engine semantics, on
// the synthetic test bundle (every policy kind once). The real export's own policies are checked state by state, read
// from ux-spec/, in ExportedPoliciesTest (D1-engine, D2-engine).
package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.bundle.ExitKind
import eu.europa.ec.ux.runtime.bundle.ExitResult
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.SynthDriver
import eu.europa.ec.ux.runtime.support.SynthResults
import eu.europa.ec.ux.runtime.support.Synthetic
import eu.europa.ec.ux.runtime.support.active
import eu.europa.ec.ux.runtime.support.ctx
import eu.europa.ec.ux.runtime.support.pick
import eu.europa.ec.ux.runtime.support.send
import eu.europa.ec.ux.runtime.support.stateId
import eu.europa.ec.ux.runtime.support.strings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackRestoreTest {

    /** Process death: the snapshot goes through JSON, and a new engine with a new port restores it. */
    private fun TestScope.reborn(snapshot: FlowSnapshot, platform: FlowPlatform = FlowPlatform.ANDROID): Triple<FlowEngine, FakePort, RestoreResult> {
        val port = FakePort()
        val engine = FlowEngine(Synthetic.bundle(), port, backgroundScope)
        val result = engine.restore(FlowSnapshot.fromJson(snapshot.toJson()), platform)
        runCurrent()
        return Triple(engine, port, result)
    }

    private fun picked(engine: FlowEngine) = engine.ctx("/picked").jsonArray.strings()

    // --- Back: one state per policy kind ---------------------------------------------------------------------------

    @Test
    fun anEventPolicyFiresItsEvent() = runTest {
        val d = SynthDriver(this).choose()

        val result = d.engine.back()
        runCurrent()

        assertEquals(BackResult.Fired(Synthetic.ABORT, EventResult.Transitioned(Synthetic.ABORTED)), result)
        assertEquals(Synthetic.ABORTED, d.engine.stateId())
        assertEquals(JsonPrimitive(Synthetic.REFUSAL), d.engine.ctx(FlowMachine.ERROR_CLASS_PATH))
        assertEquals(1, d.port.calls(Synthetic.SVC_STOP), "the detached invoke of the target runs")
    }

    @Test
    fun aToHistoryPolicyReturnsToTheLastVisitedCandidateAndCancelsTheCall() = runTest {
        val d = SynthDriver(this).choose().check()
        assertEquals(BackResult.Moved(Synthetic.CHOOSE), d.engine.back())
        runCurrent()
        assertEquals(Synthetic.CHOOSE, d.engine.stateId())
        assertEquals(1, d.port.cancelled[Synthetic.SVC_CHECK])
    }

    @Test
    fun aToHistoryPolicyAfterTheAlternativePathReturnsThereAndAToPolicyMovesToItsTarget() = runTest {
        val d = SynthDriver(this).choose(SynthResults.loadedAlt)
        assertEquals(Synthetic.CHOOSE_ALT, d.engine.stateId())
        d.engine.send(Synthetic.GO)
        assertEquals(Synthetic.CONFIRM_ALT, d.engine.stateId())
        assertEquals(BackResult.Moved(Synthetic.CHOOSE_ALT), d.engine.back())
        d.engine.send(Synthetic.GO)
        d.engine.send(Synthetic.GO)
        assertEquals(Synthetic.CHECK, d.engine.stateId())
        assertEquals(BackResult.Moved(Synthetic.CHOOSE_ALT), d.engine.back())
    }

    @Test
    fun aBlockedPolicyKeepsTheStateTheCallAndTheHandler() = runTest {
        val d = SynthDriver(this).choose().commit()
        assertEquals(Synthetic.COMMIT, d.engine.stateId())
        val gen = d.engine.active().gen

        assertEquals(BackResult.Blocked, d.engine.back())
        runCurrent()

        assertEquals(Synthetic.COMMIT, d.engine.stateId())
        assertEquals(gen, d.engine.active().gen)
        assertTrue(d.engine.active().backEnabled, "blocked keeps the platform handler enabled")
        assertEquals(1, d.port.calls(Synthetic.SVC_COMMIT))
        assertEquals(0, d.port.cancelled[Synthetic.SVC_COMMIT] ?: 0)
    }

    @Test
    fun aPassPolicyLetsThePlatformPopAndDisablesTheHandler() = runTest {
        val engine = FlowEngine(Synthetic.bundle(), FakePort(), backgroundScope)
        engine.start()
        assertEquals(BackResult.Pass, engine.back())
        assertEquals(false, engine.active().backEnabled)
    }

    // --- Restore (Android process death) ---------------------------------------------------------------------------

    @Test
    fun aSnapshotPolicyRestoresTheContext() = runTest {
        val d = SynthDriver(this).choose()
        d.engine.pick("o1")
        val snapshot = d.engine.snapshot()!!

        val (engine, port, result) = reborn(snapshot)

        assertEquals(RestoreResult.Restored(Synthetic.CHOOSE, reinvoked = false), result)
        assertEquals(Synthetic.CHOOSE, engine.stateId())
        assertEquals(listOf("m1", "o1"), picked(engine))
        assertEquals(snapshot.context, engine.active().context)
        assertEquals(0, port.total)
    }

    @Test
    fun aToHistoryRestoreGoesBackAndCallsAgainOnlyWhenEnteredAgain() = runTest {
        val d = SynthDriver(this).choose().check()
        val (engine, port, result) = reborn(d.engine.snapshot()!!)

        assertEquals(RestoreResult.Restored(Synthetic.CHOOSE, reinvoked = false), result)
        assertEquals(0, port.calls(Synthetic.SVC_CHECK))

        engine.send(Synthetic.GO)
        runCurrent()
        assertEquals(Synthetic.CHECK, engine.stateId())
        assertEquals(1, port.calls(Synthetic.SVC_CHECK))
    }

    @Test
    fun aToRestoreNeverCallsANonIdempotentServiceAgain() = runTest {
        val d = SynthDriver(this).choose().commit()
        assertEquals(1, d.port.calls(Synthetic.SVC_COMMIT))

        val (engine, port, result) = reborn(d.engine.snapshot()!!)

        assertEquals(RestoreResult.Restored(Synthetic.FAILED, reinvoked = false), result)
        assertEquals(Synthetic.FAILED, engine.stateId())
        assertEquals(0, port.calls(Synthetic.SVC_COMMIT))
        assertEquals(0, port.total)
    }

    @Test
    fun aToHistoryRestoreFindsTheCandidateTheUserLeftFrom() = runTest {
        val d = SynthDriver(this).choose(SynthResults.loadedOpt)
        d.engine.pick("o2")
        assertEquals(Synthetic.CHOOSE_OPT_ON, d.engine.stateId())
        d.code()
        assertEquals(Synthetic.CODE, d.engine.stateId())
        val (engine, port, _) = reborn(d.engine.snapshot()!!)
        assertEquals(Synthetic.CHOOSE_OPT_ON, engine.stateId())
        assertEquals(0, port.total)
    }

    @Test
    fun anAbandonPlatformRestoresNothing() = runTest {
        listOf(SynthDriver(this).choose(), SynthDriver(this).choose().check(), SynthDriver(this).choose().commit()).forEach { d ->
            val (engine, port, result) = reborn(d.engine.snapshot()!!, FlowPlatform.IOS)
            assertEquals(RestoreResult.Abandoned(AbandonReason.PLATFORM_POLICY), result)
            val exited = assertIs<FlowStatus.Exited>(engine.current.value)
            assertEquals(ExitReason.ABANDONED, exited.reason)
            assertEquals(Synthetic.CLOSED, exited.exit?.state)
            assertEquals(ExitKind.HOME, exited.exit?.kind)
            assertEquals(0, port.total)
        }
    }

    // --- Entries and exits -----------------------------------------------------------------------------------------

    @Test
    fun aNamedEntryAssignsItsLaunchAndStartsItsState() = runTest {
        val port = FakePort()
        val engine = FlowEngine(Synthetic.bundle(), port, backgroundScope)

        assertEquals(StartResult.Started(Synthetic.LOADING), engine.start(Synthetic.LINK, Synthetic.launch, unlocked = true))
        runCurrent()

        assertEquals(Synthetic.LOADING, engine.stateId())
        assertEquals(JsonPrimitive(Synthetic.URI), engine.ctx("/in/uri"))
        assertEquals(Synthetic.SVC_LOAD to Synthetic.launch, port.args.single())
    }

    @Test
    fun anEntryThatRequiresUnlockedNeedsTheGate() = runTest {
        val port = FakePort()
        val engine = FlowEngine(Synthetic.bundle(), port, backgroundScope)
        assertEquals(StartResult.Locked(Synthetic.LINK), engine.start(Synthetic.LINK, Synthetic.launch))
        runCurrent()
        assertEquals(FlowStatus.Idle, engine.current.value)
        assertEquals(0, port.total)
    }

    @Test
    fun theDefaultEntryStartsAtTheInitialStateOnce() = runTest {
        val engine = FlowEngine(Synthetic.bundle(), FakePort(), backgroundScope)
        assertEquals(StartResult.Started(Synthetic.START), engine.start())
        assertEquals(StartResult.AlreadyStarted, engine.start())
        assertEquals(StartResult.UnknownEntry("_test-none"), FlowEngine(Synthetic.bundle(), FakePort(), backgroundScope).start("_test-none"))
    }

    @Test
    fun aFinalStateExitsWithItsDeclaredExit() = runTest {
        val d = SynthDriver(this).choose().commit()
        d.port.complete(Synthetic.SVC_COMMIT, SynthResults.committed)
        runCurrent()
        assertEquals(Synthetic.OK, d.engine.stateId())
        d.engine.send(Synthetic.CLOSE)
        val exited = assertIs<FlowStatus.Exited>(d.engine.current.value)
        assertEquals(FlowExit(Synthetic.FINISHED, ExitKind.HOME, null, ExitResult.PRESENTED, emptyMap()), exited.exit)
        assertEquals(ExitReason.FINAL_STATE, exited.reason)
        assertEquals(BackResult.Inactive, d.engine.back())
    }

    @Test
    fun aFlowExitCarriesTheErrorClass() = runTest {
        val d = SynthDriver(this).choose(ServiceResult(ServiceResult.UNEXPECTED_ERROR_TYPE))
        assertEquals(Synthetic.FAILED, d.engine.stateId())
        d.engine.send(Synthetic.HELP)
        val exit = assertIs<FlowStatus.Exited>(d.engine.current.value).exit!!
        assertEquals(Synthetic.HELPED, exit.state)
        assertEquals(ExitKind.FLOW, exit.kind)
        assertEquals(mapOf(FlowMachine.ERROR_CLASS_PATH to JsonPrimitive(Synthetic.TECHNICAL)), exit.carries)
    }

    @Test
    fun anEventPayloadFeedsTheNextStatesCall() = runTest {
        val d = SynthDriver(this)
        d.engine.start()
        val payload = buildJsonObject { put("uri", Synthetic.URI) }
        d.engine.send(Synthetic.SCANNED, payload)
        runCurrent()
        assertEquals(Synthetic.SVC_LOAD to payload, d.port.args.single())
    }
}
