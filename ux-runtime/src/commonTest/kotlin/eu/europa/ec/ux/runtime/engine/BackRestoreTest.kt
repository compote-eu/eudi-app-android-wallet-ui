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

// M1b, re-run at M6a on the real export (was provisional on the boundary fixture): brief §5 back/restore, entries and
// exits on the exported bundle (D1-engine, D2-engine).
package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.bundle.ExitKind
import eu.europa.ec.ux.runtime.bundle.ExitResult
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.QR_URI
import eu.europa.ec.ux.runtime.support.Results
import eu.europa.ec.ux.runtime.support.active
import eu.europa.ec.ux.runtime.support.ctx
import eu.europa.ec.ux.runtime.support.qrPayload
import eu.europa.ec.ux.runtime.support.send
import eu.europa.ec.ux.runtime.support.stateId
import eu.europa.ec.ux.runtime.support.toggle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackRestoreTest {

    /** Process death: the snapshot goes through JSON, and a new engine with a new port restores it. */
    private fun TestScope.reborn(snapshot: FlowSnapshot, platform: FlowPlatform = FlowPlatform.ANDROID): Triple<FlowEngine, FakePort, RestoreResult> {
        val port = FakePort()
        val engine = FlowEngine(Fixture.bundle(), port, backgroundScope)
        val result = engine.restore(FlowSnapshot.fromJson(snapshot.toJson()), platform)
        runCurrent()
        return Triple(engine, port, result)
    }

    private fun selection(engine: FlowEngine) = engine.ctx("/request/selection").jsonArray.map { it.jsonPrimitive.content }

    // --- Back (brief §5) -------------------------------------------------------------------------------------------

    @Test
    fun backInRequestCancelsAsDeclined() = runTest {
        val d = Driver(this).request()
        d.port.auto["cancel"] = Results.cancelled

        val result = d.engine.back()
        runCurrent()

        assertEquals(BackResult.Fired("REJECT", EventResult.Transitioned("cancelled")), result)
        assertEquals("cancelled", d.engine.stateId())
        assertEquals(JsonPrimitive("user-refusal"), d.engine.ctx("/flow/error_class"))
        assertEquals(1, d.port.calls("cancel"))
    }

    @Test
    fun backInAuthReturnsToRequest() = runTest {
        val d = Driver(this).request().auth()
        assertEquals(BackResult.Moved("request"), d.engine.back())
        runCurrent()
        assertEquals("request", d.engine.stateId())
        assertEquals(1, d.port.cancelled["authenticate"])
    }

    @Test
    fun backInAuthAfterTheUntrustedPathReturnsToTheUntrustedRequest() = runTest {
        val d = Driver(this).request(Results.rpUntrusted)
        assertEquals("request-untrusted", d.engine.stateId())
        d.engine.send("CONTINUE")
        d.engine.send("APPROVE")
        assertEquals("auth", d.engine.stateId())
        assertEquals(BackResult.Moved("request-untrusted"), d.engine.back())
    }

    @Test
    fun backInSendingIsBlocked() = runTest {
        val d = Driver(this).request().sending()
        assertEquals("sending", d.engine.stateId())
        val gen = d.engine.active().gen

        assertEquals(BackResult.Blocked, d.engine.back())
        runCurrent()

        assertEquals("sending", d.engine.stateId())
        assertEquals(gen, d.engine.active().gen)
        assertTrue(d.engine.active().backEnabled, "blocked keeps the platform handler enabled")
        assertEquals(1, d.port.calls("send"))
        assertEquals(0, d.port.cancelled["send"] ?: 0)
    }

    // --- Restore (Android process death, brief §5) ------------------------------------------------------------------

    @Test
    fun restoreFromRequestComesBackFromTheSnapshot() = runTest {
        val d = Driver(this).request()
        d.engine.toggle("o1")
        val snapshot = d.engine.snapshot()!!

        val (engine, port, result) = reborn(snapshot)

        assertEquals(RestoreResult.Restored("request", reinvoked = false), result)
        assertEquals("request", engine.stateId())
        assertEquals(listOf("m1", "o1"), selection(engine))
        assertEquals(snapshot.context, engine.active().context)
        assertEquals(0, port.total)
    }

    @Test
    fun restoreFromAuthGoesToRequestAndAuthenticatesAgain() = runTest {
        val d = Driver(this).request().auth()
        val (engine, port, result) = reborn(d.engine.snapshot()!!)

        assertEquals(RestoreResult.Restored("request", reinvoked = false), result)
        assertEquals(0, port.calls("authenticate"))

        engine.send("CONTINUE")
        runCurrent()
        assertEquals("auth", engine.stateId())
        assertEquals(1, port.calls("authenticate"))
    }

    @Test
    fun restoreFromSendingGoesToErrorWithoutSendingAgain() = runTest {
        val d = Driver(this).request().sending()
        assertEquals(1, d.port.calls("send"))

        val (engine, port, result) = reborn(d.engine.snapshot()!!)

        assertEquals(RestoreResult.Restored("error", reinvoked = false), result)
        assertEquals("error", engine.stateId())
        assertEquals(0, port.calls("send"))
        assertEquals(0, port.total)
    }

    @Test
    fun restoreFromVerificationCodeGoesBackToTheRequest() = runTest {
        val d = Driver(this).request(Results.optionalOnly)
        d.engine.toggle("o2")
        d.verification()
        assertEquals("verification-code", d.engine.stateId())
        val (engine, port, _) = reborn(d.engine.snapshot()!!)
        assertEquals("request-optional-selected", engine.stateId())
        assertEquals(0, port.total)
    }

    @Test
    fun iosColdStartRestoresNothing() = runTest {
        listOf(Driver(this).request(), Driver(this).request().auth(), Driver(this).request().sending()).forEach { d ->
            val (engine, port, result) = reborn(d.engine.snapshot()!!, FlowPlatform.IOS)
            assertEquals(RestoreResult.Abandoned(AbandonReason.PLATFORM_POLICY), result)
            val exited = assertIs<FlowStatus.Exited>(engine.current.value)
            assertEquals(ExitReason.ABANDONED, exited.reason)
            assertEquals(ExitKind.HOME, exited.exit?.kind)
            assertEquals(0, port.total)
        }
    }

    // --- Entries and exits -----------------------------------------------------------------------------------------

    @Test
    fun deeplinkEntryGoesToConnecting() = runTest {
        val port = FakePort()
        val engine = FlowEngine(Fixture.bundle(), port, backgroundScope)
        val launch = buildJsonObject { put("uri", QR_URI) }

        assertEquals(StartResult.Started("connecting"), engine.start("deeplink", launch, unlocked = true))
        runCurrent()

        assertEquals("connecting", engine.stateId())
        assertEquals(JsonPrimitive(QR_URI), engine.ctx("/launch/uri"))
        assertEquals("fetchRequest" to buildJsonObject { put("uri", QR_URI) }, port.args.single())
    }

    @Test
    fun deeplinkEntryNeedsTheUnlockGate() = runTest {
        val port = FakePort()
        val engine = FlowEngine(Fixture.bundle(), port, backgroundScope)
        assertEquals(StartResult.Locked("deeplink"), engine.start("deeplink", buildJsonObject { put("uri", QR_URI) }))
        runCurrent()
        assertEquals(FlowStatus.Idle, engine.current.value)
        assertEquals(0, port.total)
    }

    @Test
    fun defaultEntryStartsAtScan() = runTest {
        val engine = FlowEngine(Fixture.bundle(), FakePort(), backgroundScope)
        assertEquals(StartResult.Started("scan"), engine.start())
        assertEquals(StartResult.AlreadyStarted, engine.start())
    }

    @Test
    fun successExitsHomePresented() = runTest {
        val d = Driver(this).request().sending()
        d.port.complete("send", Results.sent)
        runCurrent()
        assertEquals("success", d.engine.stateId())
        d.engine.send("CLOSE")
        val exited = assertIs<FlowStatus.Exited>(d.engine.current.value)
        assertEquals(FlowExit("done", ExitKind.HOME, null, ExitResult.PRESENTED, emptyMap()), exited.exit)
        assertEquals(ExitReason.FINAL_STATE, exited.reason)
        assertEquals(BackResult.Inactive, d.engine.back())
    }

    @Test
    fun helpExitCarriesTheErrorClass() = runTest {
        val d = Driver(this).request(ServiceResult("Error"))
        assertEquals("error", d.engine.stateId())
        d.engine.send("HELP")
        val exit = assertIs<FlowStatus.Exited>(d.engine.current.value).exit!!
        assertEquals("to-help", exit.state)
        assertEquals(ExitKind.FLOW, exit.kind)
        assertEquals(mapOf("/flow/error_class" to JsonPrimitive("technical-error")), exit.carries)
    }

    @Test
    fun qrScanFeedsTheFetchArgument() = runTest {
        val d = Driver(this)
        d.engine.start()
        d.engine.send("SYS_QR_SCANNED", qrPayload)
        runCurrent()
        assertEquals("fetchRequest" to qrPayload, d.port.args.single())
    }
}
