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

package eu.europa.ec.ux.runtime.bundle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Reads a bundle tree into the model. Every semantic enum (boundary §1.1) is closed: an unknown value refuses
 * the load. Unknown keys are ignored (version 1 may add optional keys). Unknown screen effects are warnings.
 */
internal class BundleParser(private val root: JsonObject) {

    private val warnings = mutableListOf<String>()

    fun parse(): FlowBundle {
        val r = Node("", root)
        return FlowBundle(
            schemaVersion = FlowBundle.SUPPORTED_SCHEMA_VERSION,
            flowId = r.child("flow_id").str(),
            specHash = r.child("spec_hash").str(),
            provisional = r.opt("provisional")?.bool() ?: false,
            initial = r.child("initial").str(),
            entries = r.opt("entries")?.entries()?.associate { (name, n) -> name to entry(name, n) }.orEmpty(),
            processDeath = processDeath(r.child("process_death")),
            context = r.opt("context")?.entries()?.associate { (path, n) -> path to contextField(path, n) }.orEmpty(),
            events = r.child("events").entries().associate { (name, n) -> name to event(name, n) },
            services = r.opt("services")?.entries()?.associate { (name, n) -> name to service(name, n) }.orEmpty(),
            states = r.child("states").entries().associate { (id, n) -> id to state(id, n) },
            screens = r.opt("screens")?.entries()?.associate { (id, n) -> id to screen(id, n) }.orEmpty(),
            warnings = warnings,
            raw = root,
        )
    }

    private fun entry(name: String, n: Node) = EntryDef(
        name = name,
        state = n.child("state").str(),
        schemes = n.strList("schemes"),
        requires = n.opt("requires")?.arr()?.map { it.enumOf(EntryRequirement.entries.toTypedArray()) { e -> e.wire } }
            .orEmpty().toSet(),
        assign = assignList(n.opt("assign")),
    )

    private fun processDeath(n: Node) = ProcessDeath(
        android = n.child("android").enumOf(ProcessDeathPolicy.entries.toTypedArray()) { it.wire },
        ios = n.child("ios").enumOf(ProcessDeathPolicy.entries.toTypedArray()) { it.wire },
    )

    private fun contextField(path: String, n: Node) = ContextField(
        path = path,
        type = n.child("type").str(),
        nullable = n.opt("nullable")?.bool() ?: false,
        default = n.obj()["default"],
        derive = n.opt("derive")?.let { derive(it) },
    )

    private fun derive(n: Node): Derive {
        val list = n.child("list").str()
        val where = condition(n.child("where"))
        return when (n.child("op").enumOf(DeriveOp.entries.toTypedArray()) { it.wire }) {
            DeriveOp.COUNT_WHERE -> Derive.CountWhere(list, where)
            DeriveOp.IDS_WHERE -> Derive.IdsWhere(list, n.child("key").str(), where)
        }
    }

    private fun event(name: String, n: Node) = EventDef(name, n.optStr("source"), n.strMap("payload"))

    private fun service(name: String, n: Node) = ServiceDef(
        name = name,
        ref = n.optStr("ref"),
        args = n.strMap("args"),
        idempotent = n.child("idempotent").bool(),
        results = n.child("results").obj().keys,
    )

    private fun state(id: String, n: Node) = StateDef(
        id = id,
        kind = n.opt("kind")?.enumOf(StateKind.entries.toTypedArray()) { it.wire } ?: StateKind.STEP,
        screen = n.optStr("screen"),
        uiState = n.optStr("ui_state"),
        uiStateWhen = n.opt("ui_state_when")?.arr()?.map { UiStateWhen(condition(it.child("when")), it.child("ui_state").str()) }
            .orEmpty(),
        on = n.opt("on")?.entries()?.associate { (event, list) -> event to list.arr().map { branch(it) } }.orEmpty(),
        invoke = n.opt("invoke")?.let { invoke(it) },
        back = n.opt("back")?.let { back(it) },
        restore = n.opt("restore")?.let { restore(it) },
        exit = n.opt("exit")?.let { exit(it) },
    )

    private fun branch(n: Node) = Branch(
        target = n.opt("target")?.str(),
        kind = n.optStr("kind"),
        guard = n.opt("guard")?.let { Guard(it.optStr("rule"), condition(it.child("when"))) },
        assign = assignList(n.opt("assign")),
        errorClass = n.optStr("error_class"),
    )

    private fun invoke(n: Node) = Invoke(
        service = n.child("service").str(),
        args = n.opt("args")?.entries()?.associate { (k, v) -> k to valueSource(v) }.orEmpty(),
        scope = n.opt("scope")?.enumOf(InvokeScope.entries.toTypedArray()) { it.wire } ?: InvokeScope.STATE,
        acceptsWhilePending = n.opt("accepts_while_pending")?.arr()?.map { it.str() }?.toSet(),
        on = n.child("on").entries().associate { (result, m) ->
            result to ResultMapping(m.opt("event")?.str(), assignList(m.opt("assign")))
        },
    )

    private fun back(n: Node): BackPolicy = when (n.child("policy").enumOf(BackPolicyName.entries.toTypedArray()) { it.wire }) {
        BackPolicyName.EVENT -> BackPolicy.Event(n.child("event").str())
        BackPolicyName.TO -> BackPolicy.To(n.child("target").str())
        BackPolicyName.TO_HISTORY -> BackPolicy.ToHistory(n.child("candidates").arr().map { it.str() }, n.child("fallback").str())
        BackPolicyName.BLOCKED -> BackPolicy.Blocked
        BackPolicyName.PASS -> BackPolicy.Pass
    }

    private fun restore(n: Node): RestorePolicy =
        when (n.child("policy").enumOf(RestorePolicyName.entries.toTypedArray()) { it.wire }) {
            RestorePolicyName.SNAPSHOT -> RestorePolicy.Snapshot
            RestorePolicyName.TO -> RestorePolicy.To(n.child("target").str())
            RestorePolicyName.TO_HISTORY ->
                RestorePolicy.ToHistory(n.child("candidates").arr().map { it.str() }, n.child("fallback").str())
        }

    private fun exit(n: Node) = ExitDef(
        kind = n.child("kind").enumOf(ExitKind.entries.toTypedArray()) { it.wire },
        ref = n.optStr("ref"),
        result = n.child("result").enumOf(ExitResult.entries.toTypedArray()) { it.wire },
        carries = n.strList("carries"),
        note = n.optStr("note"),
    )

    private fun screen(id: String, n: Node) = ScreenDef(
        id = id,
        template = n.optStr("template"),
        uiStates = n.strList("ui_states"),
        rules = n.opt("rules")?.arr()?.map { rule(it) }.orEmpty(),
    )

    private fun rule(n: Node): ScreenRule {
        val id = n.child("id").str()
        val effects = n.opt("then")?.arr()?.map { e ->
            val name = e.child("effect").str()
            val known = name in KNOWN_EFFECTS
            if (!known) warnings += "unknown screen effect '$name' at ${e.path} (logged and ignored, boundary §1.1)"
            ScreenEffect(name, e.obj(), known)
        }.orEmpty()
        return ScreenRule(id, condition(n.child("when")), effects)
    }

    private fun assignList(n: Node?): List<AssignOp> = n?.arr()?.map { assign(it) }.orEmpty()

    private fun assign(n: Node): AssignOp = when (n.child("op").enumOf(AssignOpName.entries.toTypedArray()) { it.wire }) {
        AssignOpName.SET -> {
            val from = n.opt("from")?.let { valueSource(it) }
            if (from == null && "value" !in n.obj()) n.malformed("set needs value or from")
            AssignOp.Set(n.child("path").str(), n.obj()["value"], from)
        }
        AssignOpName.MERGE -> AssignOp.Merge(n.child("path").str(), valueSource(n.child("from")))
        AssignOpName.TOGGLE_ITEM -> AssignOp.ToggleItem(
            list = n.child("list").str(),
            key = n.child("key").str(),
            keyFrom = valueSource(n.child("key_from")),
            field = n.child("field").str(),
        )
    }

    private fun valueSource(n: Node): ValueSource {
        val o = n.obj()
        val key = o.keys.singleOrNull() ?: n.malformed("a value source has exactly one key")
        val v = n.child(key).str()
        return when (key) {
            "event" -> ValueSource.Event(v)
            "result" -> ValueSource.Result(v)
            "launch" -> ValueSource.Launch(v)
            "context" -> ValueSource.Context(v)
            else -> throw RefusedException(RefusalReason.UnsupportedFeature(n.path, key))
        }
    }

    fun condition(n: Node): Condition {
        val o = n.obj()
        return when {
            "op" in o -> leaf(n, o)
            "all" in o -> Condition.All(combinands(n.child("all")))
            "any" in o -> Condition.AnyOf(combinands(n.child("any")))
            "not" in o -> Condition.Not(condition(n.child("not")))
            else -> throw RefusedException(RefusalReason.UnsupportedFeature(n.path, o.keys.joinToString(",")))
        }
    }

    private fun leaf(n: Node, o: JsonObject): Condition {
        val op = n.child("op").enumOf(ConditionOp.entries.toTypedArray()) { it.wire }
        val path = n.optStr("path")
        val field = n.optStr("field")
        if ((path == null) == (field == null)) n.malformed("a leaf has exactly one of path or field")
        val value = o["value"]
        if (op == ConditionOp.IN && value !is JsonArray) n.malformed("'in' needs an array value")
        if (op in VALUE_OPS && value == null) n.malformed("'${op.wire}' needs a value")
        return Condition.Leaf(path, field, op, value ?: JsonNull)
    }

    private fun combinands(n: Node): List<Condition> {
        val items = n.arr()
        if (items.size < 2) n.malformed("a combinator needs at least 2 conditions")
        return items.map { condition(it) }
    }

    private enum class DeriveOp(val wire: String) { COUNT_WHERE("count_where"), IDS_WHERE("ids_where") }

    private enum class AssignOpName(val wire: String) { SET("set"), MERGE("merge"), TOGGLE_ITEM("toggle_item") }

    private enum class BackPolicyName(val wire: String) {
        EVENT("event"),
        TO("to"),
        TO_HISTORY("to_history"),
        BLOCKED("blocked"),
        PASS("pass"),
    }

    private enum class RestorePolicyName(val wire: String) { SNAPSHOT("snapshot"), TO("to"), TO_HISTORY("to_history") }

    private companion object {
        /** Applied by the interpreter, or (`allow_transition`, `message`) ignored on screens by design (boundary Q12). */
        val KNOWN_EFFECTS = setOf("hide", "show", "enable", "disable", "set_copy", "allow_transition", "message")
        val VALUE_OPS = setOf(ConditionOp.EQ, ConditionOp.NEQ, ConditionOp.IN)
    }
}
