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

// M9r (spike rule T1, SPIKE-PLAN §0.2b): what the tests of the real export expect, read from ux-spec/ at test time. The
// entry and its launch, the main path, which result and which event lead on, and the service payloads all come from the
// exported bundle, its model fixture and its fake-engine scripts; no state, event, service, result or node is named here.
package eu.europa.ec.ux.runtime.support

import eu.europa.ec.ux.runtime.bundle.BackPolicy
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.InvokeScope
import eu.europa.ec.ux.runtime.bundle.StateDef
import eu.europa.ec.ux.runtime.engine.AssignInputs
import eu.europa.ec.ux.runtime.engine.Config
import eu.europa.ec.ux.runtime.engine.Fired
import eu.europa.ec.ux.runtime.engine.FlowContext
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowMachine
import eu.europa.ec.ux.runtime.engine.ServiceResult
import eu.europa.ec.ux.runtime.fixture.ExportedPresModel
import eu.europa.ec.ux.runtime.fixture.ExportedPresScripts
import eu.europa.ec.ux.runtime.screen.ScreenCatalog
import eu.europa.ec.ux.runtime.screen.ScreenNode
import eu.europa.ec.ux.runtime.screen.ScreenTree
import eu.europa.ec.ux.theme.generated.UxBrandConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object ExportWalk {
    private val model: JsonObject by lazy { Json.parseToJsonElement(ExportedPresModel.JSON).jsonObject }
    private val sequences: List<JsonObject> by lazy { model.getValue("sequences").jsonArray.map { it.jsonObject } }

    /** The exported fake-engine scripts: per script, per service, the result objects in call order. */
    private val scripts: Map<String, Map<String, List<JsonObject>>> by lazy {
        ExportedPresScripts.BY_NAME.mapValues { (_, text) ->
            Json.parseToJsonElement(text).jsonObject.getValue("calls").jsonObject.mapValues { (_, steps) ->
                steps.jsonArray.mapNotNull { it.jsonObject["result"] as? JsonObject }
            }
        }
    }

    /**
     * The payload of [result] of [service]: the first one an exported script answers with (real copy keys and data),
     * else the one the model fixture replays, else none.
     */
    fun payload(service: String, result: String): JsonObject {
        scripts.keys.sorted().forEach { name ->
            scripts.getValue(name)[service]?.firstOrNull { it["type"]?.jsonPrimitive?.content == result }
                ?.let { return JsonObject(it - "type") }
        }
        sequences.flatMap { it.getValue("steps").jsonArray }.map { it.jsonObject }.forEach { step ->
            val r = step["result"] as? JsonObject ?: return@forEach
            if (r.str("service") == service && r.str("name") == result) return r.getValue("payload").jsonObject
        }
        return JsonObject(emptyMap())
    }

    fun result(service: String, result: String): ServiceResult = ServiceResult(result, payload(service, result))

    /** Per service, per result name, the payload (for the bounded model check). */
    fun payloads(bundle: FlowBundle = Fixture.bundle()): Map<String, Map<String, JsonObject>> =
        bundle.services.mapValues { (name, svc) -> svc.results.associateWith { payload(name, it) } }

    /** The entry the tests open the flow with: the first entry whose state invokes a service (the link entry). */
    fun entry(bundle: FlowBundle = Fixture.bundle()): String =
        bundle.entries.values.first { bundle.state(it.state).invoke != null }.name

    /** The launch inputs of [entry]: the model fixture's, for a sequence that starts there. */
    fun launch(entry: String = entry()): JsonObject =
        sequences.firstOrNull { it.str("entry") == entry }?.getValue("launch")?.jsonObject ?: JsonObject(emptyMap())

    /** The state [entry] starts in. */
    fun entryState(bundle: FlowBundle = Fixture.bundle(), entry: String = entry(bundle)): String = bundle.entries.getValue(entry).state

    private fun hasMain(state: StateDef, event: String?): Boolean =
        event != null && state.on[event].orEmpty().any { it.kind == "main" && it.target != null }

    /**
     * The result of [state]'s state-scoped invoke that leads on: one whose event has a main branch, else one with no
     * event (an internal result, the state then waits for the user), else null.
     */
    fun mainResult(state: StateDef): String? {
        val inv = state.invoke?.takeIf { it.scope == InvokeScope.STATE } ?: return null
        return inv.on.entries.firstOrNull { hasMain(state, it.value.event) }?.key
            ?: inv.on.entries.firstOrNull { it.value.event == null }?.key
    }

    /** The event the user sends in [state] to go on: one with a main branch that the state's own invoke does not produce. */
    fun mainEvent(bundle: FlowBundle, state: StateDef): String? {
        val produced = state.invoke?.on?.values?.mapNotNull { it.event }.orEmpty().toSet()
        return state.on.keys.firstOrNull { it !in produced && hasMain(state, it) && bundle.events[it]?.payload.isNullOrEmpty() }
    }

    /** One step of the main path: a service answer or a user event, and the state it leaves the flow in. */
    data class Step(val from: String, val service: String?, val result: ServiceResult?, val event: String?, val to: String, val context: JsonObject)

    /**
     * The main path from [entry]: the bundle's own main branches, walked with the engine's [FlowMachine] (so guards and
     * assigns apply), each service answered with [mainResult] and its exported payload, each waiting state left by its
     * [mainEvent]. Ends at a final state, or where no main step is left.
     */
    fun mainPath(
        bundle: FlowBundle = Fixture.bundle(),
        entry: String = entry(bundle),
        overrides: Map<String, ServiceResult> = emptyMap(),
    ): List<Step> {
        val machine = FlowMachine(bundle, FlowLog.NONE)
        val def = bundle.entries.getValue(entry)
        val launch = launch(entry)
        val initial = FlowContext.assign(FlowContext.initial(bundle.context), def.assign, AssignInputs(launch = launch), bundle.context, FlowLog.NONE)
        var cfg = machine.enter(Config(def.state, initial, emptyList(), 0), def.state, initial, null)
        var answered = false
        val steps = mutableListOf<Step>()
        repeat(MAX_STEPS) {
            val state = bundle.state(cfg.state)
            if (state.isFinal) return steps
            val result = if (!answered) mainResult(state) else null
            val next: Config?
            if (result != null) {
                val inv = state.invoke!!
                val r = overrides[inv.service]?.takeIf { it.type == result } ?: result(inv.service, result)
                val mapping = inv.on.getValue(result)
                val inputs = AssignInputs(event = r.payload, result = r.payload, launch = launch)
                val ctx = FlowContext.assign(cfg.context, mapping.assign, inputs, bundle.context, FlowLog.NONE)
                next = mapping.event?.let { fire(machine, cfg.copy(context = ctx), state, it, inputs) } ?: cfg.copy(context = ctx)
                answered = mapping.event == null
                steps += Step(cfg.state, inv.service, r, mapping.event, next?.state ?: return steps, next.context)
            } else {
                val event = mainEvent(bundle, state) ?: return steps
                next = fire(machine, cfg, state, event, AssignInputs(launch = launch)) ?: return steps
                answered = false
                steps += Step(cfg.state, null, null, event, next.state, next.context)
            }
            if (next.state != cfg.state) answered = false
            cfg = next
        }
        error("the main path does not end within $MAX_STEPS steps: ${steps.map { it.to }}")
    }

    /** The states of the main path in order, the entry state first (a final state last when the path reaches one). */
    fun mainStates(bundle: FlowBundle = Fixture.bundle()): List<String> {
        val steps = mainPath(bundle)
        return (listOf(entryState(bundle)) + steps.map { it.to }).fold(emptyList()) { acc, s -> if (acc.lastOrNull() == s) acc else acc + s }
    }

    private fun fire(machine: FlowMachine, cfg: Config, state: StateDef, event: String, inputs: AssignInputs): Config? =
        when (val f = machine.fire(state, cfg.context, event, inputs)) {
            is Fired.Internal -> cfg.copy(context = f.context)
            is Fired.Moved -> machine.enter(cfg, f.target, f.context, f.errorClass)
            Fired.NoBranch, Fired.NotAccepted -> null
        }

    /** The service results of the main path, for a port that answers at once (each service with its main result). */
    fun mainResults(bundle: FlowBundle = Fixture.bundle()): Map<String, ServiceResult> =
        mainPath(bundle).mapNotNull { s -> s.service?.let { it to s.result!! } }.toMap()

    /** Whether Back in [state] returns to [to] when [to] was visited just before (the bundle's back policy). */
    fun backReturnsTo(state: StateDef, to: String): Boolean = when (val p = state.back) {
        is BackPolicy.To -> p.target == to
        is BackPolicy.ToHistory -> to in p.candidates
        else -> false
    }

    /** The screen tree a state draws. */
    fun tree(bundle: FlowBundle, state: String): ScreenTree = ScreenCatalog(bundle).screens.getValue(bundle.state(state).screen!!)

    /** The node of [tree] that emits [event] (the node a user presses), searching slots too. */
    fun emitter(tree: ScreenTree, event: String): ScreenNode? = tree.nodes.firstOrNull { n -> n.events.any { it.emits == event } }

    /**
     * The regions of [tree] its template draws for the default brand's layout. A template the bundle gives `layouts` for
     * (`TPL-sheet` `decision-steps`) draws, for the brand's selected option, the regions that option's `arranges` names;
     * every other template draws every region. Read from the bundle and the generated brand config, so a layout change
     * in the spec changes the expectation, not the test.
     */
    fun drawnRegions(root: JsonObject, tree: ScreenTree): Set<String> {
        val axes = ((root["layouts"] as? JsonObject)?.get(tree.template) as? JsonObject) ?: return tree.regions.keys
        val selected = UxBrandConfig.defaultBrand.layouts.decisionSteps.name.lowercase()
        val options = axes.values.mapNotNull { ((it as? JsonObject)?.get("options") as? JsonObject)?.get(selected) as? JsonObject }
        val arranges = options.firstOrNull()?.get("arranges")?.jsonPrimitive?.content ?: return tree.regions.keys
        return tree.regions.keys.filter { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(arranges) }.toSet()
    }

    /** The nodes (subtrees included) in regions [drawnRegions] leaves out. */
    fun undrawn(root: JsonObject, tree: ScreenTree): Set<String> {
        val drawn = drawnRegions(root, tree)
        return tree.regions.filterKeys { it !in drawn }.values.flatten().flatMap { it.walk().map(ScreenNode::id).toList() }.toSet()
    }


    /** A top-level node of an exported screen, addressed as the bundle's JSON pointer does (`/screens/<id>/regions/<r>/<i>`). */
    data class Site(val screen: String, val region: String, val index: Int, val node: ScreenNode) {
        val pointer: String get() = "/screens/$screen/regions/$region/$index"
    }

    /** Every top-level node of [screen] (or of every screen), in bundle order. */
    fun sites(bundle: FlowBundle = Fixture.bundle(), screen: String? = null): List<Site> =
        ScreenCatalog(bundle).screens.values.filter { screen == null || it.id == screen }.flatMap { t ->
            t.regions.flatMap { (region, nodes) -> nodes.mapIndexed { i, n -> Site(t.id, region, i, n) } }
        }

    /**
     * A node whose element takes [prop] as an enum (the registry's schema) and carries it as a plain string, with the
     * registry's allowed keys: where a planted unknown variant must be reported.
     */
    fun variantSite(bundle: FlowBundle = Fixture.bundle(), screen: String? = null): Triple<Site, String, Set<String>> =
        sites(bundle, screen).firstNotNullOf { site ->
            val schema = eu.europa.ec.ux.elements.registry.UxElementRegistry.elementSchemas[site.node.element] ?: return@firstNotNullOf null
            schema.props.entries.firstOrNull { (name, keys) -> keys != null && (site.node.props[name] as? JsonPrimitive)?.isString == true }
                ?.let { (name, keys) -> Triple(site, name, keys!!) }
        }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private const val MAX_STEPS = 40
}

/** A JSON array of strings, for asserting list values. */
internal fun JsonArray.strings(): List<String> = map { (it as JsonPrimitive).content }
