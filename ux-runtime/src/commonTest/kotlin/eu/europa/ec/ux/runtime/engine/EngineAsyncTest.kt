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


// M1b, re-run at M6a on the real export; M9r (spike rule T1): the async rules of SPIKE-PLAN §1.3 on the synthetic
// test bundle (support/SyntheticFlow.kt), whose names are test-owned, so no spec change can turn them red.
package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.support.SynthDriver
import eu.europa.ec.ux.runtime.support.SynthResults
import eu.europa.ec.ux.runtime.support.Synthetic
import eu.europa.ec.ux.runtime.support.active
import eu.europa.ec.ux.runtime.support.ctx
import eu.europa.ec.ux.runtime.support.pick
import eu.europa.ec.ux.runtime.support.send
import eu.europa.ec.ux.runtime.support.stateId
import eu.europa.ec.ux.runtime.support.strings
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineAsyncTest {

    @Test
    fun leavingAStateCancelsItsInvoke() = runTest {
        val d = SynthDriver(this).choose().check()
        assertEquals(Synthetic.CHECK, d.engine.stateId())
        assertTrue(d.engine.active().pending)
        assertEquals(1, d.port.waiting(Synthetic.SVC_CHECK))

        d.engine.back()
        runCurrent()

        assertEquals(Synthetic.CHOOSE, d.engine.stateId())
        assertEquals(1, d.port.cancelled[Synthetic.SVC_CHECK])
        assertEquals(0, d.port.waiting(Synthetic.SVC_CHECK))
        assertFalse(d.engine.active().pending)
    }

    @Test
    fun anEventThatLeavesAPendingStateCancelsItsCall() = runTest {
        val d = SynthDriver(this)
        d.engine.start()
        d.engine.send(Synthetic.SCANNED, buildJsonObject { put("uri", Synthetic.URI) })
        runCurrent()
        assertEquals(1, d.port.waiting(Synthetic.SVC_LOAD))

        d.engine.send(Synthetic.ABORT)
        runCurrent()

        assertEquals(Synthetic.ABORTED, d.engine.stateId())
        assertEquals(1, d.port.cancelled[Synthetic.SVC_LOAD])
    }

    @Test
    fun aLateCompletionIsIgnoredEvenInALaterEntryOfTheSameState() = runTest {
        val d = SynthDriver(this).choose()
        d.port.cancellable = false
        d.check()
        val firstGen = d.engine.active().gen
        d.engine.back()
        d.check()
        assertEquals(Synthetic.CHECK, d.engine.stateId())
        assertTrue(d.engine.active().gen > firstGen)
        assertEquals(2, d.port.waiting(Synthetic.SVC_CHECK))

        d.port.complete(Synthetic.SVC_CHECK, ServiceResult(ServiceResult.UNEXPECTED_ERROR_TYPE))
        runCurrent()

        assertEquals(Synthetic.CHECK, d.engine.stateId(), "the first entry's result must not move the second entry")
        assertTrue(d.log.lines.any { it.startsWith("late result ${ServiceResult.UNEXPECTED_ERROR_TYPE}") }, d.log.lines.joinToString("\n"))

        d.port.complete(Synthetic.SVC_CHECK, SynthResults.passed)
        runCurrent()
        assertEquals(Synthetic.CODE, d.engine.stateId())
    }

    @Test
    fun aDoubleTapSendsOnce() = runTest {
        val d = SynthDriver(this).choose()
        val gen = d.engine.active().gen
        assertEquals(EventResult.Transitioned(Synthetic.CHECK), d.engine.dispatch(Synthetic.GO, gen = gen))
        assertEquals(EventResult.Dropped(DropReason.STALE_GENERATION), d.engine.dispatch(Synthetic.GO, gen = gen))
        runCurrent()
        assertEquals(1, d.port.calls(Synthetic.SVC_CHECK))

        d.port.complete(Synthetic.SVC_CHECK, SynthResults.passed)
        d.port.auto[Synthetic.SVC_PRODUCE] = SynthResults.made
        runCurrent()
        assertEquals(Synthetic.CODE, d.engine.stateId())
        val confirmGen = d.engine.active().gen
        assertEquals(EventResult.Transitioned(Synthetic.COMMIT), d.engine.dispatch(Synthetic.CONFIRM, gen = confirmGen))
        assertEquals(EventResult.Dropped(DropReason.STALE_GENERATION), d.engine.dispatch(Synthetic.CONFIRM, gen = confirmGen))
        assertEquals(EventResult.Dropped(DropReason.NOT_ACCEPTED), d.engine.send(Synthetic.CONFIRM))
        runCurrent()

        assertEquals(1, d.port.calls(Synthetic.SVC_COMMIT))
    }

    @Test
    fun internalEventsMayRepeatInOneGeneration() = runTest {
        val d = SynthDriver(this).choose()
        val gen = d.engine.active().gen
        repeat(3) { assertEquals(EventResult.Internal, d.engine.pick("o1")) }
        assertEquals(gen, d.engine.active().gen)
        assertEquals(listOf("m1", "o1"), d.engine.ctx("/picked").jsonArray.strings())
    }

    @Test
    fun onlyAcceptedEventsPassWhileAnInvokeIsPending() = runTest {
        val d = SynthDriver(this).choose()
        d.port.auto[Synthetic.SVC_CHECK] = SynthResults.passed
        d.check()
        assertEquals(Synthetic.CODE, d.engine.stateId())
        assertTrue(d.engine.active().pending)

        assertFalse(d.engine.isAccepted(Synthetic.CONFIRM))
        assertEquals(EventResult.Dropped(DropReason.PENDING), d.engine.send(Synthetic.CONFIRM))
        assertTrue(d.engine.isAccepted(Synthetic.ABORT))

        val gen = d.engine.active().gen
        d.port.complete(Synthetic.SVC_PRODUCE, SynthResults.made)
        runCurrent()

        assertEquals(Synthetic.CODE, d.engine.stateId())
        assertEquals(gen, d.engine.active().gen, "an internal result keeps the generation")
        assertEquals(JsonPrimitive("1234"), d.engine.ctx("/code"))
        assertEquals(Synthetic.DEFAULT_UI, d.engine.active().uiState)
        assertTrue(d.engine.isAccepted(Synthetic.CONFIRM))
    }

    @Test
    fun uiStateWhenPicksTheFirstMatch() = runTest {
        val d = SynthDriver(this).choose()
        d.port.auto[Synthetic.SVC_CHECK] = SynthResults.passed
        d.port.auto[Synthetic.SVC_PRODUCE] = SynthResults.madeNoUrl
        d.check()
        assertEquals(Synthetic.NO_URL, d.engine.active().uiState)
    }

    @Test
    fun anUnknownResultIsAContractErrorAndMovesNothing() = runTest {
        val d = SynthDriver(this).choose().check()
        d.port.complete(Synthetic.SVC_CHECK, ServiceResult("Teleported"))
        runCurrent()
        assertEquals(Synthetic.CHECK, d.engine.stateId())
        assertTrue(d.log.lines.any { it.startsWith("contract error") })
    }
}
