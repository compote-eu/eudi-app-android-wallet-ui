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

// M1b, re-run at M6a on the real export (was provisional on the boundary fixture): guards, assign-then-guard and derive
// on the exported bundle's request family (L58 fix).
package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.support.Results
import eu.europa.ec.ux.runtime.support.active
import eu.europa.ec.ux.runtime.support.ctx
import eu.europa.ec.ux.runtime.support.int
import eu.europa.ec.ux.runtime.support.send
import eu.europa.ec.ux.runtime.support.stateId
import eu.europa.ec.ux.runtime.support.toggle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineSemanticsTest {

    @Test
    fun theRequestFamilyFollowsTheSelection() = runTest {
        val d = Driver(this).request(Results.optionalOnly)
        val e = d.engine
        assertEquals("request-optional-only", e.stateId())

        e.toggle("o1")
        assertEquals("request-optional-selected", e.stateId())
        assertEquals(1, e.ctx("/request/selected_count").int())

        e.toggle("o1")
        assertEquals("request-optional-only", e.stateId(), "unticking the last item goes back (assign-then-guard)")

        assertEquals(EventResult.Transitioned("request-no-selection"), e.send("CONTINUE"))
        assertEquals(JsonPrimitive(true), e.ctx("/request/attempted"))
        assertEquals(EventResult.Internal, e.send("CONTINUE"), "pressing again changes nothing")

        e.toggle("o2")
        assertEquals("request-optional-selected", e.stateId())
        e.toggle("o2")
        assertEquals("request-no-selection", e.stateId(), "count 0 after an attempt shows the error again")

        e.toggle("o1")
        assertEquals(EventResult.Transitioned("auth"), e.send("CONTINUE"))
        assertEquals(listOf("o1"), e.ctx("/request/selection").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun sendGetsTheDerivedSelection() = runTest {
        val d = Driver(this).request()
        d.engine.toggle("o1")
        d.sending()
        assertEquals("sending", d.engine.stateId())
        val (_, args) = d.port.args.last()
        assertEquals(buildJsonObject { put("selection", buildJsonArray { add(JsonPrimitive("m1")); add(JsonPrimitive("o1")) }) }, args)
    }

    @Test
    fun aResultMergesIntoTheContext() = runTest {
        val d = Driver(this).request()
        assertEquals(JsonPrimitive("Verifier"), d.engine.ctx("/verifier/name"))
        assertEquals(JsonPrimitive("cross-device"), d.engine.ctx("/request/channel"), "merge is shallow and keeps defaults")
        assertEquals(1, d.engine.ctx("/request/selected_count").int())
        assertEquals(d.engine.active().gen, d.engine.snapshot()?.gen)
        runCurrent()
    }
}
