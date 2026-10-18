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


// M1b, re-run at M6a on the real export; M9r (spike rule T1): RULING-2 as engine semantics on the synthetic test bundle;
// the export is checked only for what it must declare (an error branch on every state-scoped invoke), read from it.
package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.bundle.LoadResult
import eu.europa.ec.ux.runtime.bundle.RefusalReason
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.SynthDriver
import eu.europa.ec.ux.runtime.support.Synthetic
import eu.europa.ec.ux.runtime.support.ctx
import eu.europa.ec.ux.runtime.support.json
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    private fun SynthDriver.scan() {
        engine.start()
        engine.send(Synthetic.SCANNED, buildJsonObject { put("uri", Synthetic.URI) })
        scope.runCurrent()
    }

    @Test
    fun aThrowingCallTakesTheErrorBranch() = runTest {
        val d = SynthDriver(this)
        d.port.throwing[Synthetic.SVC_LOAD] = IllegalStateException("boom")
        d.scan()

        assertEquals(Synthetic.FAILED, d.engine.stateId())
        assertEquals(JsonPrimitive(Synthetic.TECHNICAL), d.engine.ctx(FlowMachine.ERROR_CLASS_PATH))
        assertEquals(1, d.port.calls(Synthetic.SVC_LOAD))
        assertTrue(d.log.lines.any { "${Synthetic.SVC_LOAD} failed" in it && "IllegalStateException" in it }, d.log.lines.toString())
    }

    @Test
    fun aThrowingNonIdempotentCallTakesTheErrorBranchAndIsNotCalledAgain() = runTest {
        val d = SynthDriver(this)
        d.port.throwing[Synthetic.SVC_COMMIT] = IllegalArgumentException("io")
        d.choose().commit()

        assertEquals(Synthetic.FAILED, d.engine.stateId())
        assertEquals(1, d.port.calls(Synthetic.SVC_COMMIT))
    }

    @Test
    fun aForeignCancellationIsAFailureNotACancellation() = runTest {
        // A service that leaks a CancellationException of its own (a timeout inside it) while the state's job is
        // still active: RULING-2 treats it as a failure, so the state does not hang pending.
        val d = SynthDriver(this).choose()
        d.port.throwing[Synthetic.SVC_CHECK] = CancellationException("timeout inside the service")
        d.check()

        assertEquals(Synthetic.FAILED, d.engine.stateId())
    }

    @Test
    fun leavingTheStateIsStillACancellationNotAnError() = runTest {
        val d = SynthDriver(this).choose().check()
        d.engine.back()
        runCurrent()

        assertEquals(Synthetic.CHOOSE, d.engine.stateId())
        assertEquals(1, d.port.cancelled[Synthetic.SVC_CHECK])
        assertTrue(d.log.lines.none { "mapped to $error" in it }, d.log.lines.toString())
    }

    @Test
    fun theConvertedResultCarriesClassUnexpected() = runTest {
        // A variant whose load error branch copies the payload's class into the context.
        val root = Synthetic.root.with(
            "/states/${Synthetic.LOADING}/invoke/on/$error/assign",
            JsonArray(listOf(json("""{"op":"set","path":"/info/name","from":{"result":"class"}}"""))),
        )
        val d = SynthDriver(this, bundle = Synthetic.bundle(root))
        d.port.throwing[Synthetic.SVC_LOAD] = IllegalStateException("boom")
        d.scan()

        assertEquals(Synthetic.FAILED, d.engine.stateId())
        assertEquals(JsonPrimitive(ServiceResult.UNEXPECTED_CLASS), d.engine.ctx("/info/name"))
        assertEquals(ServiceResult(error, ServiceResult.unexpected().payload), ServiceResult.unexpected())
        assertEquals(ServiceResult.UNEXPECTED_CLASS, ServiceResult.unexpected().payload["class"]?.jsonPrimitive?.content)
    }

    @Test
    fun aThrowingDetachedInvokeIsLoggedAndIgnored() = runTest {
        val d = SynthDriver(this).choose()
        d.port.throwing[Synthetic.SVC_STOP] = IllegalStateException("gone")
        d.engine.send(Synthetic.ABORT)
        runCurrent()

        assertEquals(Synthetic.ABORTED, d.engine.stateId())
        assertEquals(1, d.port.calls(Synthetic.SVC_STOP))
        assertTrue(d.log.lines.any { "detached ${Synthetic.SVC_STOP} finished ($error)" in it }, d.log.lines.toString())
    }

    @Test
    fun aServiceWithoutAnErrorResultIsRefused() {
        val results = Synthetic.root.getValue("services").jsonObject.getValue(Synthetic.SVC_CHECK).jsonObject.getValue("results").jsonObject
        val root = Synthetic.root
            .with("/services/${Synthetic.SVC_CHECK}/results", JsonObject(results - error))
            .with("/states/${Synthetic.CHECK}/invoke/on/$error", null)

        val reason = assertIs<RefusalReason.InvalidBundle>(assertIs<LoadResult.Refused>(Synthetic.load(root)).reason)
        assertEquals("/services/${Synthetic.SVC_CHECK}/results", reason.path)
    }

    @Test
    fun anInternalErrorMappingIsNotAnErrorBranch() {
        val root = Synthetic.root.with("/states/${Synthetic.COMMIT}/invoke/on/$error/event", JsonNull)

        val reason = assertIs<RefusalReason.InvalidBundle>(assertIs<LoadResult.Refused>(Synthetic.load(root)).reason)
        assertEquals("/states/${Synthetic.COMMIT}/invoke/on/$error/event", reason.path)
    }

    @Test
    fun theExportDeclaresAnErrorBranchOnEveryStateScopedInvoke() {
        val states = Fixture.root.getValue("states").jsonObject
        val invokes = states.values.mapNotNull { it.jsonObject["invoke"]?.jsonObject }
            .filter { (it["scope"] as? JsonPrimitive)?.content != "detached" }
        invokes.forEach { assertTrue(error in it.getValue("on").jsonObject, "${it["service"]} has no $error branch") }
        assertIs<LoadResult.Loaded>(Fixture.load(), "the loader enforces it as well")
    }
}
