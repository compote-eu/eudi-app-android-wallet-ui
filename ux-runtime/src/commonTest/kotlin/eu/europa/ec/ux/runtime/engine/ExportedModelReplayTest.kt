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

package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.bundle.BundleParser
import eu.europa.ec.ux.runtime.bundle.Condition
import eu.europa.ec.ux.runtime.bundle.Node
import eu.europa.ec.ux.runtime.fixture.ExportedPresModel
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.stateId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * V6 / R5 (M6a): the exporter's model fixture (`ux-spec/fixtures/model`, its bounded check's first shortest sequence
 * per transition taken) replayed through this engine. Every step must leave the engine in the state the exporter's
 * engine reached, and the plain invariants must hold in each state they name. An `accepts` invariant (V30) is a
 * property of every reachable node, which a replay cannot show: the sequences that cover its event are replayed.
 */
class ExportedModelReplayTest {

    private val model = Json.parseToJsonElement(ExportedPresModel.JSON).jsonObject
    private val sequences = model.getValue("sequences").jsonArray.map { it.jsonObject }

    private class Invariant(val id: String, val states: Set<String>, val holds: Condition, val accepts: String?)

    private val invariants = model.getValue("invariants").jsonArray.map { it.jsonObject }.map { i ->
        Invariant(
            id = i.str("id"),
            states = i.getValue("in").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            holds = BundleParser(Fixture.root).condition(Node("/invariants/${i.str("id")}/when", i.getValue("when"))),
            accepts = (i["accepts"] as? JsonPrimitive)?.content,
        )
    }

    @Test
    fun theFixtureIsForThisExport() {
        assertEquals(Fixture.specHash, model.str("spec_hash"))
        assertEquals(Fixture.flowId, model.str("flow_id"))
        assertTrue(sequences.isNotEmpty(), "the model fixture has no sequence")
    }

    @Test
    fun everySequenceReplaysToTheSameStates() = runTest {
        sequences.forEach { replay(it) }
    }

    @Test
    fun theAcceptsInvariantsEventsAreCovered() {
        val covered = sequences.flatMap { s -> s.getValue("steps").jsonArray.map { it.jsonObject.str("event") } }.toSet()
        invariants.mapNotNull { it.accepts }.forEach { assertTrue(it in covered, "no sequence fires $it") }
    }

    private fun TestScope.replay(sequence: JsonObject) {
        val name = sequence.str("covers")
        val port = FakePort()
        val engine = FlowEngine(Fixture.bundle(), port, backgroundScope)
        engine.start(sequence.str("entry"), sequence.getValue("launch").jsonObject, unlocked = true)
        runCurrent()
        sequence.getValue("steps").jsonArray.map { it.jsonObject }.forEachIndexed { i, step ->
            val at = "$name, step $i"
            assertEquals(step.str("from"), engine.stateId(), "$at: from")
            val result = step["result"] as? JsonObject
            if (result != null) {
                port.complete(result.str("service"), ServiceResult(result.str("name"), result.getValue("payload").jsonObject))
            } else {
                val active = engine.current.value as? FlowStatus.Active ?: fail("$at: the flow is not active")
                engine.dispatch(step.str("event"), step.getValue("payload").jsonObject, active.gen)
            }
            runCurrent()
            assertEquals(step.str("to"), reached(engine), "$at: ${step.str("event")} -> ${step.str("to")}")
            checkInvariants(engine, at)
        }
    }

    private fun reached(engine: FlowEngine): String? = when (val s = engine.current.value) {
        is FlowStatus.Active -> s.state
        is FlowStatus.Exited -> s.exit?.state
        FlowStatus.Idle -> null
    }

    private fun checkInvariants(engine: FlowEngine, at: String) {
        val active = engine.current.value as? FlowStatus.Active ?: return
        invariants.filter { it.accepts == null && active.state in it.states }.forEach {
            assertTrue(Conditions.eval(it.holds, active.context), "$at: invariant ${it.id} in ${active.state}")
        }
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
}
