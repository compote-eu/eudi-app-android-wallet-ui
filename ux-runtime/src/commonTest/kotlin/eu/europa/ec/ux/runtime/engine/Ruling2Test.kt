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

// M1b, re-run at M6a on the real export (was provisional on the boundary fixture): RULING-2 on the exported bundle.
package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.bundle.LoadResult
import eu.europa.ec.ux.runtime.bundle.RefusalReason
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.ctx
import eu.europa.ec.ux.runtime.support.json
import eu.europa.ec.ux.runtime.support.qrPayload
import eu.europa.ec.ux.runtime.support.send
import eu.europa.ec.ux.runtime.support.stateId
import eu.europa.ec.ux.runtime.support.with
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RULING-2: a service that throws, or a coroutine failure other than the state job's own cancellation, becomes the
 * `Error` result with payload `{class: unexpected}` and follows the state's declared error branch. A state-scoped
 * invoke without an error branch refuses the bundle at load.
 */
class Ruling2Test {

    private val error = ServiceResult.UNEXPECTED_ERROR_TYPE

    @Test
    fun aThrowingFetchTakesTheErrorBranch() = runTest {
        val d = Driver(this)
        d.port.throwing["fetchRequest"] = IllegalStateException("boom")
        d.engine.start()
        d.engine.send("SYS_QR_SCANNED", qrPayload)
        runCurrent()

        assertEquals("error", d.engine.stateId())
        assertEquals(JsonPrimitive("technical-error"), d.engine.ctx("/flow/error_class"))
        assertEquals(1, d.port.calls("fetchRequest"))
        assertTrue(d.log.lines.any { "fetchRequest failed" in it && "IllegalStateException" in it }, d.log.lines.toString())
    }

    @Test
    fun aThrowingSendTakesTheErrorBranchAndIsNotCalledAgain() = runTest {
        val d = Driver(this)
        d.port.throwing["send"] = IllegalArgumentException("io")
        d.request().sending()

        assertEquals("error", d.engine.stateId())
        assertEquals(1, d.port.calls("send"))
    }

    @Test
    fun aForeignCancellationIsAFailureNotACancellation() = runTest {
        // A service that leaks a CancellationException of its own (a timeout inside it) while the state's job is
        // still active: RULING-2 treats it as a failure, so the state does not hang pending.
        val d = Driver(this).request()
        d.port.throwing["authenticate"] = CancellationException("timeout inside the service")
        d.auth()

        assertEquals("error", d.engine.stateId())
    }

    @Test
    fun leavingTheStateIsStillACancellationNotAnError() = runTest {
        val d = Driver(this).request().auth()
        d.engine.back()
        runCurrent()

        assertEquals("request", d.engine.stateId())
        assertEquals(1, d.port.cancelled["authenticate"])
        assertTrue(d.log.lines.none { "mapped to $error" in it }, d.log.lines.toString())
    }

    @Test
    fun theConvertedResultCarriesClassUnexpected() = runTest {
        // A bundle variant whose connecting error branch copies the payload's class into the context.
        val root = Fixture.root.with(
            "/states/connecting/invoke/on/$error/assign",
            JsonArray(listOf(json("""{"op":"set","path":"/document/name","from":{"result":"class"}}"""))),
        )
        val d = Driver(this, bundle = Fixture.bundle(root))
        d.port.throwing["fetchRequest"] = IllegalStateException("boom")
        d.engine.start()
        d.engine.send("SYS_QR_SCANNED", qrPayload)
        runCurrent()

        assertEquals("error", d.engine.stateId())
        assertEquals(JsonPrimitive(ServiceResult.UNEXPECTED_CLASS), d.engine.ctx("/document/name"))
        assertEquals(ServiceResult(error, ServiceResult.unexpected().payload), ServiceResult.unexpected())
        assertEquals(ServiceResult.UNEXPECTED_CLASS, ServiceResult.unexpected().payload["class"]?.jsonPrimitive?.content)
    }

    @Test
    fun aThrowingDetachedInvokeIsLoggedAndIgnored() = runTest {
        val d = Driver(this).request()
        d.port.throwing["cancel"] = IllegalStateException("gone")
        d.engine.send("REJECT")
        runCurrent()

        assertEquals("cancelled", d.engine.stateId())
        assertEquals(1, d.port.calls("cancel"))
        assertTrue(d.log.lines.any { "detached cancel finished ($error)" in it }, d.log.lines.toString())
    }

    @Test
    fun aServiceWithoutAnErrorResultIsRefused() {
        val services = Fixture.root.getValue("services").jsonObject
        val results = services.getValue("authenticate").jsonObject.getValue("results").jsonObject
        val root = Fixture.root
            .with("/services/authenticate/results", JsonObject(results - error))
            .with("/states/auth/invoke/on/$error", null)

        val reason = assertIs<RefusalReason.InvalidBundle>(assertIs<LoadResult.Refused>(Fixture.load(root)).reason)
        assertEquals("/services/authenticate/results", reason.path)
    }

    @Test
    fun anInternalErrorMappingIsNotAnErrorBranch() {
        val root = Fixture.root.with("/states/sending/invoke/on/$error/event", JsonNull)

        val reason = assertIs<RefusalReason.InvalidBundle>(assertIs<LoadResult.Refused>(Fixture.load(root)).reason)
        assertEquals("/states/sending/invoke/on/$error/event", reason.path)
    }

    @Test
    fun theFixtureDeclaresAnErrorBranchOnEveryStateScopedInvoke() {
        val states = Fixture.root.getValue("states").jsonObject
        val invokes = states.values.mapNotNull { it.jsonObject["invoke"]?.jsonObject }
            .filter { (it["scope"] as? JsonPrimitive)?.content != "detached" }
        assertTrue(invokes.size >= 4, "found ${invokes.size} state-scoped invokes")
        invokes.forEach { assertTrue(error in it.getValue("on").jsonObject, "${it["service"]} has no $error branch") }
    }
}
