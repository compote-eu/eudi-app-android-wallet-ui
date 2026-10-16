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

import eu.europa.ec.ux.runtime.bundle.BackPolicy
import eu.europa.ec.ux.runtime.bundle.Condition
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.InvokeScope
import eu.europa.ec.ux.runtime.bundle.StateDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A bounded model check over a bundle (SPIKE-PLAN §3 D5; analysis spike-plan §5.4), running the engine's own
 * [FlowMachine]. Breadth-first over (state × context abstraction), to [depth] inputs.
 *
 * - The abstraction projects the context onto the paths that guards, `ui_state_when` and `derive` read, plus the last
 *   visited `to_history` candidate, so two configurations that no guard or back policy can tell apart are one node.
 * - Inputs per node: every event of the state's `on`, except those the state's own invoke produces; every result of a
 *   state-scoped invoke (with its assigns, from [resultPayloads]); and Back by the state's policy.
 * - Event payload fields of type `id` range over every `id` in the context's lists; any other type gets [sampleValue].
 *
 * Nothing here names a state or an event. The property is the caller's.
 */
internal class FlowModelChecker(
    private val bundle: FlowBundle,
    private val resultPayloads: Map<String, Map<String, JsonObject>>,
    private val depth: Int,
    private val sampleValue: JsonElement = JsonPrimitive("sample:value"),
) {
    private val machine = FlowMachine(bundle, FlowLog.NONE)
    private val relevantPaths: Set<String> = buildSet {
        bundle.states.values.forEach { s ->
            s.on.values.flatten().forEach { b -> b.guard?.let { collect(it.condition, this) } }
            s.uiStateWhen.forEach { collect(it.condition, this) }
        }
        bundle.context.values.forEach { f -> f.derive?.let { add(it.list) } }
    }

    /** `to_history` reads the history, so the last visited candidate is part of a node. */
    private val historyCandidates: Set<String> = bundle.states.values.flatMap { s ->
        (s.back as? BackPolicy.ToHistory)?.candidates.orEmpty()
    }.toSet()

    data class Counterexample(val trace: List<String>, val state: String, val context: JsonObject)

    data class Outcome(val counterexample: Counterexample?, val explored: Int)

    fun check(starts: Map<String, Config>, property: (Config) -> Boolean): Outcome {
        val seen = mutableSetOf<Triple<String, JsonObject, String?>>()
        var frontier = starts.map { (label, cfg) -> cfg to listOf(label) }
        for (level in 0..depth) {
            val next = mutableListOf<Pair<Config, List<String>>>()
            for ((cfg, trace) in frontier) {
                if (!seen.add(Triple(cfg.state, abstraction(cfg.context), cfg.history.lastOrNull { it in historyCandidates }))) continue
                if (!property(cfg)) return Outcome(Counterexample(trace, cfg.state, cfg.context), seen.size)
                if (level < depth) successors(cfg).forEach { (label, succ) -> next += succ to (trace + label) }
            }
            frontier = next
        }
        return Outcome(null, seen.size)
    }

    private fun successors(cfg: Config): List<Pair<String, Config>> {
        val state = bundle.state(cfg.state)
        if (state.isFinal) return emptyList()
        val out = mutableListOf<Pair<String, Config>>()
        val produced = state.invoke?.on?.values?.mapNotNull { it.event }.orEmpty().toSet()
        state.on.keys.filter { it !in produced }.forEach { event ->
            payloads(event, cfg.context).forEach { payload ->
                fire(cfg, state, event, AssignInputs(event = payload))?.let { out += "$event$payload" to it }
            }
        }
        state.invoke?.takeIf { it.scope == InvokeScope.STATE }?.let { inv ->
            inv.on.forEach { (result, mapping) ->
                val payload = resultPayloads[inv.service]?.get(result) ?: JsonObject(emptyMap())
                val inputs = AssignInputs(event = payload, result = payload)
                val ctx = FlowContext.assign(cfg.context, mapping.assign, inputs, bundle.context, FlowLog.NONE)
                val event = mapping.event
                val succ = if (event == null) cfg.copy(context = ctx) else fire(cfg.copy(context = ctx), state, event, inputs)
                succ?.let { out += "${inv.service}->$result" to it }
            }
        }
        back(cfg, state)?.let { out += "BACK" to it }
        return out
    }

    private fun fire(cfg: Config, state: StateDef, event: String, inputs: AssignInputs): Config? =
        when (val f = machine.fire(state, cfg.context, event, inputs)) {
            is Fired.Internal -> cfg.copy(context = f.context)
            is Fired.Moved -> machine.enter(cfg, f.target, f.context, f.errorClass)
            Fired.NoBranch, Fired.NotAccepted -> null
        }

    private fun back(cfg: Config, state: StateDef): Config? = when (val p = state.back) {
        is BackPolicy.Event -> fire(cfg, state, p.event, AssignInputs())
        is BackPolicy.To -> machine.enter(cfg, p.target, cfg.context, null)
        is BackPolicy.ToHistory -> machine.enter(cfg, machine.historyTarget(cfg.history, p.candidates, p.fallback), cfg.context, null)
        BackPolicy.Blocked, BackPolicy.Pass, null -> null
    }

    private fun payloads(event: String, ctx: JsonObject): List<JsonObject> {
        val fields = bundle.events[event]?.payload.orEmpty()
        if (fields.isEmpty()) return listOf(JsonObject(emptyMap()))
        return fields.entries.fold(listOf(JsonObject(emptyMap()))) { acc, (name, type) ->
            val values = if (type == "id") ids(ctx) else listOf(sampleValue)
            acc.flatMap { partial -> values.map { JsonObject(partial + (name to it)) } }
        }
    }

    private fun ids(ctx: JsonObject): List<JsonElement> = bundle.context.values
        .filter { it.type == "list" }
        .flatMap { (FlowContext.get(ctx, it.path) as? JsonArray).orEmpty() }
        .mapNotNull { (it as? JsonObject)?.get("id") }
        .distinct()

    private fun abstraction(ctx: JsonObject): JsonObject = buildJsonObject {
        relevantPaths.sorted().forEach { put(it, FlowContext.get(ctx, it)) }
    }

    private fun collect(c: Condition, into: MutableSet<String>) {
        when (c) {
            is Condition.Leaf -> c.path?.let { into += it }
            is Condition.All -> c.items.forEach { collect(it, into) }
            is Condition.AnyOf -> c.items.forEach { collect(it, into) }
            is Condition.Not -> collect(c.item, into)
        }
    }

    companion object {
        /** One start configuration per bundle entry, entered as [FlowEngine.start] does with `unlocked = true`. */
        fun starts(bundle: FlowBundle, launch: JsonObject): Map<String, Config> {
            val machine = FlowMachine(bundle, FlowLog.NONE)
            val initial = FlowContext.initial(bundle.context)
            return bundle.entries.values.associate { e ->
                val ctx = FlowContext.assign(initial, e.assign, AssignInputs(launch = launch), bundle.context, FlowLog.NONE)
                "entry:${e.name}" to machine.enter(Config(e.state, ctx, emptyList(), 0), e.state, ctx, null)
            }
        }
    }
}
