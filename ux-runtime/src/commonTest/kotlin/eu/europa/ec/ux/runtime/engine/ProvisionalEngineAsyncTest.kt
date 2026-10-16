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

// PROVISIONAL (M1b): the async rules of SPIKE-PLAN §1.3 on the boundary fixture; re-run on the real export in M6.
package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.RecordingLog
import eu.europa.ec.ux.runtime.support.Results
import eu.europa.ec.ux.runtime.support.active
import eu.europa.ec.ux.runtime.support.ctx
import eu.europa.ec.ux.runtime.support.qrPayload
import eu.europa.ec.ux.runtime.support.send
import eu.europa.ec.ux.runtime.support.stateId
import eu.europa.ec.ux.runtime.support.toggle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives the provisional flow to a named state through the engine's public API only. */
internal class Driver(val scope: TestScope, val port: FakePort = FakePort(), engineScope: CoroutineScope = scope.backgroundScope) {
    val log = RecordingLog()
    val engine = FlowEngine(Fixture.bundle(), port, engineScope, log)

    fun request(result: ServiceResult = Results.requestReady): Driver = apply {
        port.auto["fetchRequest"] = result
        engine.start()
        engine.send("SYS_QR_SCANNED", qrPayload)
        scope.runCurrent()
    }

    fun auth(): Driver = apply {
        engine.send("CONTINUE")
        scope.runCurrent()
    }

    fun verification(): Driver = apply {
        port.auto["authenticate"] = Results.authenticated
        port.auto["verificationCode"] = Results.codeReady
        auth()
    }

    fun sending(): Driver = apply {
        verification()
        engine.send("CONFIRM")
        scope.runCurrent()
    }
}

class ProvisionalEngineAsyncTest {

    @Test
    fun leavingAStateCancelsItsInvoke() = runTest {
        val d = Driver(this).request().auth()
        assertEquals("auth", d.engine.stateId())
        assertTrue(d.engine.active().pending)
        assertEquals(1, d.port.waiting("authenticate"))

        d.engine.back()
        runCurrent()

        assertEquals("request", d.engine.stateId())
        assertEquals(1, d.port.cancelled["authenticate"])
        assertEquals(0, d.port.waiting("authenticate"))
        assertFalse(d.engine.active().pending)
    }

    @Test
    fun cancelInConnectingCancelsTheFetch() = runTest {
        val d = Driver(this)
        d.engine.start()
        d.engine.send("SYS_QR_SCANNED", qrPayload)
        runCurrent()
        assertEquals(1, d.port.waiting("fetchRequest"))

        d.engine.send("CANCEL")
        runCurrent()

        assertEquals("cancelled", d.engine.stateId())
        assertEquals(1, d.port.cancelled["fetchRequest"])
    }

    @Test
    fun aLateCompletionIsIgnoredEvenInALaterEntryOfTheSameState() = runTest {
        val d = Driver(this).request()
        d.port.cancellable = false
        d.auth()
        val firstGen = d.engine.active().gen
        d.engine.back()
        d.auth()
        assertEquals("auth", d.engine.stateId())
        assertTrue(d.engine.active().gen > firstGen)
        assertEquals(2, d.port.waiting("authenticate"))

        d.port.complete("authenticate", ServiceResult("Failed"))
        runCurrent()

        assertEquals("auth", d.engine.stateId(), "the first entry's result must not move the second entry")
        assertTrue(d.log.lines.any { it.startsWith("late result Failed") }, d.log.lines.joinToString("\n"))

        d.port.complete("authenticate", Results.authenticated)
        runCurrent()
        assertEquals("verification-code", d.engine.stateId())
    }

    @Test
    fun aDoubleConsentTapSendsOnce() = runTest {
        val d = Driver(this).request()
        val gen = d.engine.active().gen
        assertEquals(EventResult.Transitioned("auth"), d.engine.dispatch("CONTINUE", gen = gen))
        assertEquals(EventResult.Dropped(DropReason.STALE_GENERATION), d.engine.dispatch("CONTINUE", gen = gen))
        runCurrent()
        assertEquals(1, d.port.calls("authenticate"))

        d.port.complete("authenticate", Results.authenticated)
        d.port.auto["verificationCode"] = Results.codeReady
        runCurrent()
        assertEquals("verification-code", d.engine.stateId())
        val confirmGen = d.engine.active().gen
        assertEquals(EventResult.Transitioned("sending"), d.engine.dispatch("CONFIRM", gen = confirmGen))
        assertEquals(EventResult.Dropped(DropReason.STALE_GENERATION), d.engine.dispatch("CONFIRM", gen = confirmGen))
        assertEquals(EventResult.Dropped(DropReason.NOT_ACCEPTED), d.engine.send("CONFIRM"))
        runCurrent()

        assertEquals(1, d.port.calls("send"))
    }

    @Test
    fun internalEventsMayRepeatInOneGeneration() = runTest {
        val d = Driver(this).request()
        val gen = d.engine.active().gen
        repeat(3) { assertEquals(EventResult.Internal, d.engine.toggle("o1")) }
        assertEquals(gen, d.engine.active().gen)
        assertEquals(listOf("m1", "o1"), d.engine.ctx("/request/selection").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun onlyAcceptedEventsPassWhileAnInvokeIsPending() = runTest {
        val d = Driver(this).request()
        d.port.auto["authenticate"] = Results.authenticated
        d.auth()
        assertEquals("verification-code", d.engine.stateId())
        assertTrue(d.engine.active().pending)

        assertFalse(d.engine.isAccepted("CONFIRM"))
        assertEquals(EventResult.Dropped(DropReason.PENDING), d.engine.send("CONFIRM"))
        assertTrue(d.engine.isAccepted("CANCEL"))

        val gen = d.engine.active().gen
        d.port.complete("verificationCode", Results.codeReady)
        runCurrent()

        assertEquals("verification-code", d.engine.stateId())
        assertEquals(gen, d.engine.active().gen, "an internal result keeps the generation")
        assertEquals(JsonPrimitive("1234"), d.engine.ctx("/verification/code"))
        assertEquals("default", d.engine.active().uiState)
        assertTrue(d.engine.isAccepted("CONFIRM"))
    }

    @Test
    fun uiStateWhenPicksTheFirstMatch() = runTest {
        val d = Driver(this).request()
        d.port.auto["authenticate"] = Results.authenticated
        d.port.auto["verificationCode"] = ServiceResult("Ready", buildJsonObject {
            put("code", JsonPrimitive("1234"))
            put("portal_url", JsonNull)
        })
        d.auth()
        assertEquals("no-url", d.engine.active().uiState)
    }

    @Test
    fun anUnknownResultIsAContractErrorAndMovesNothing() = runTest {
        val d = Driver(this).request()
        d.auth()
        d.port.complete("authenticate", ServiceResult("Teleported"))
        runCurrent()
        assertEquals("auth", d.engine.stateId())
        assertTrue(d.log.lines.any { it.startsWith("contract error") })
    }
}
