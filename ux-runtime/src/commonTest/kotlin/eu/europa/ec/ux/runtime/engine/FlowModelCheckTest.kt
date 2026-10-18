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


// M1b, re-run at M6a on the real export; M9r (spike rule T1): D5-engine with the property read from the export. The
// bounded model check runs every invariant of the exported bundle (`invariants`: a state property, or an `accepts`
// property), and a variant of the export with the guards into an invariant's states removed must fail it with a path.
package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.bundle.BundleParser
import eu.europa.ec.ux.runtime.bundle.Condition
import eu.europa.ec.ux.runtime.bundle.Node
import eu.europa.ec.ux.runtime.support.ExportWalk
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.with
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowModelCheckTest {

    private class Invariant(val id: String, val states: Set<String>, val holds: Condition, val accepts: String?)

    private val invariants: List<Invariant> = (Fixture.root["invariants"] as? JsonArray).orEmpty().map { it.jsonObject }.map { i ->
        val id = i.getValue("id").jsonPrimitive.content
        Invariant(
            id = id,
            states = i.getValue("in").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            holds = BundleParser(Fixture.root).condition(Node("/invariants/$id/when", i.getValue("when"))),
            accepts = (i["accepts"] as? JsonPrimitive)?.content,
        )
    }

    private fun check(root: JsonObject, property: (FlowMachine, Config) -> Boolean): FlowModelChecker.Outcome {
        val bundle = Fixture.bundle(root)
        val machine = FlowMachine(bundle, FlowLog.NONE)
        return FlowModelChecker(bundle, ExportWalk.payloads(bundle), DEPTH)
            .check(FlowModelChecker.starts(bundle, ExportWalk.launch())) { property(machine, it) }
    }

    /** Whether [cfg] keeps [inv]: a state property holds, or the `accepts` event is not a silent no-op where it should act. */
    private fun keeps(machine: FlowMachine, inv: Invariant, cfg: Config): Boolean {
        if (cfg.state !in inv.states) return true
        if (!Conditions.eval(inv.holds, cfg.context)) return inv.accepts != null
        val event = inv.accepts ?: return true
        return when (val f = machine.fire(machine.bundle.state(cfg.state), cfg.context, event, AssignInputs())) {
            is Fired.Moved -> true
            is Fired.Internal -> f.context != cfg.context
            Fired.NoBranch, Fired.NotAccepted -> false
        }
    }

    private fun violated(machine: FlowMachine, cfg: Config): Invariant? = invariants.firstOrNull { !keeps(machine, it, cfg) }

    @Test
    fun everyExportedInvariantHoldsWithinTheBound() {
        val outcome = check(Fixture.root) { m, cfg -> violated(m, cfg) == null }
        println("D5-engine: ${invariants.size} invariants, explored ${outcome.explored} nodes, counterexample ${outcome.counterexample}")
        assertNull(outcome.counterexample, "${outcome.counterexample?.trace}")
    }

    /** The check has reach: each invariant's states are reachable within the bound. */
    @Test
    fun theBoundReachesTheStatesOfEveryInvariant() {
        invariants.forEach { inv ->
            assertNotNull(check(Fixture.root) { _, cfg -> cfg.state !in inv.states }.counterexample, "${inv.id}: ${inv.states} not reached")
        }
    }

    /**
     * The check finds what it is for (L58): in a variant of the export where every guarded branch into an invariant's
     * states becomes the event's only branch, unguarded, the check finds a path that breaks that invariant, for at least
     * one invariant whose guards could be removed. Every counterexample found must be in the invariant's states, break
     * it, and fit the bound. (Not every guard is load-bearing; an export with no guard into any invariant's states has
     * nothing to remove, and the test then checks nothing.)
     */
    @Test
    fun aVariantWithoutTheGuardsIntoAnInvariantsStatesFailsItWithAPath() {
        val tried = mutableListOf<String>()
        val failed = mutableListOf<String>()
        invariants.filter { it.accepts == null }.forEach { inv ->
            var root = Fixture.root
            var removed = 0
            Fixture.root.getValue("states").jsonObject.forEach { (sid, s) ->
                ((s.jsonObject["on"] as? JsonObject).orEmpty()).forEach { (event, branches) ->
                    val guarded = branches.jsonArray.map { it.jsonObject }
                        .firstOrNull { b -> (b["target"] as? JsonPrimitive)?.content in inv.states && b["guard"] != null }
                    if (guarded != null) {
                        root = root.with("/states/$sid/on/$event", JsonArray(listOf(JsonObject(guarded - "guard"))))
                        removed++
                    }
                }
            }
            if (removed == 0) return@forEach println("D5-engine: ${inv.id} has no guarded branch into ${inv.states}")
            val outcome = check(root) { m, cfg -> keeps(m, inv, cfg) }
            tried += inv.id
            val cex = outcome.counterexample ?: return@forEach println("D5-engine variant ${inv.id}: no counterexample")
            failed += inv.id
            println("D5-engine variant ${inv.id}: explored ${outcome.explored} nodes, counterexample ${cex.trace}")
            assertTrue(cex.state in inv.states, "${inv.id}: ${cex.state}")
            assertFalse(Conditions.eval(inv.holds, cex.context), "${inv.id}: the counterexample breaks it")
            assertTrue(cex.trace.size - 1 <= DEPTH)
        }
        if (tried.isNotEmpty()) assertTrue(failed.isNotEmpty(), "no variant of $tried fails its invariant")
    }

    private companion object {
        const val DEPTH = 8
    }
}
