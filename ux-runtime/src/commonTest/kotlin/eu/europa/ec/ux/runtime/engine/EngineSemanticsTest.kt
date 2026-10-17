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


// M1b, re-run at M6a on the real export; M9r (spike rule T1): guards, assign-then-guard, derive and a shallow merge,
// on the synthetic test bundle's choose family (the shape of the export's L58 fix, with test-owned names).
package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.support.SynthDriver
import eu.europa.ec.ux.runtime.support.SynthResults
import eu.europa.ec.ux.runtime.support.Synthetic
import eu.europa.ec.ux.runtime.support.active
import eu.europa.ec.ux.runtime.support.ctx
import eu.europa.ec.ux.runtime.support.int
import eu.europa.ec.ux.runtime.support.pick
import eu.europa.ec.ux.runtime.support.send
import eu.europa.ec.ux.runtime.support.stateId
import eu.europa.ec.ux.runtime.support.strings
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineSemanticsTest {

    @Test
    fun theGuardedFamilyFollowsTheDerivedSelection() = runTest {
        val d = SynthDriver(this).choose(SynthResults.loadedOpt)
        val e = d.engine
        assertEquals(Synthetic.CHOOSE_OPT, e.stateId())

        e.pick("o1")
        assertEquals(Synthetic.CHOOSE_OPT_ON, e.stateId())
        assertEquals(1, e.ctx("/picked_count").int())

        e.pick("o1")
        assertEquals(Synthetic.CHOOSE_OPT, e.stateId(), "unpicking the last item goes back (assign-then-guard)")

        assertEquals(EventResult.Transitioned(Synthetic.CHOOSE_NONE), e.send(Synthetic.GO))
        assertEquals(JsonPrimitive(true), e.ctx("/tried"))
        assertEquals(EventResult.Internal, e.send(Synthetic.GO), "pressing again changes nothing")

        e.pick("o2")
        assertEquals(Synthetic.CHOOSE_OPT_ON, e.stateId())
        e.pick("o2")
        assertEquals(Synthetic.CHOOSE_NONE, e.stateId(), "count 0 after an attempt shows the error state again")

        e.pick("o1")
        assertEquals(EventResult.Transitioned(Synthetic.CHECK), e.send(Synthetic.GO))
        assertEquals(listOf("o1"), e.ctx("/picked").jsonArray.strings())
    }

    @Test
    fun aCallGetsTheDerivedSelection() = runTest {
        val d = SynthDriver(this).choose()
        d.engine.pick("o1")
        d.commit()
        assertEquals(Synthetic.COMMIT, d.engine.stateId())
        val (_, args) = d.port.args.last()
        assertEquals(buildJsonObject { put("picked", buildJsonArray { add("m1"); add("o1") }) }, args)
    }

    @Test
    fun aResultMergesIntoTheContext() = runTest {
        val d = SynthDriver(this).choose()
        assertEquals(JsonPrimitive("Tester"), d.engine.ctx("/info/name"))
        assertEquals(JsonPrimitive("plain"), d.engine.ctx("/info/kind"), "merge is shallow and keeps defaults")
        assertEquals(1, d.engine.ctx("/picked_count").int())
        assertEquals(d.engine.active().gen, d.engine.snapshot()?.gen)
        runCurrent()
    }
}
