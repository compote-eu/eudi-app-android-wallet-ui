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

import eu.europa.ec.ux.runtime.bundle.AssignOp
import eu.europa.ec.ux.runtime.bundle.Condition
import eu.europa.ec.ux.runtime.bundle.ConditionOp
import eu.europa.ec.ux.runtime.bundle.ContextField
import eu.europa.ec.ux.runtime.bundle.Derive
import eu.europa.ec.ux.runtime.bundle.ValueSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The inputs a `from` can read (boundary §1.4.1). */
internal class AssignInputs(
    val event: JsonObject = EMPTY,
    val result: JsonObject = EMPTY,
    val launch: JsonObject = EMPTY,
) {
    companion object {
        val EMPTY = JsonObject(emptyMap())
    }
}

/** The flow context: an immutable JSON tree addressed by absolute `dataPath`s. No indexing, no wildcards. */
internal object FlowContext {

    fun initial(fields: Map<String, ContextField>): JsonObject {
        var ctx = JsonObject(emptyMap())
        fields.values.forEach { ctx = set(ctx, it.path, it.default ?: JsonNull) }
        return derive(ctx, fields)
    }

    fun get(ctx: JsonObject, path: String): JsonElement {
        var cur: JsonElement = ctx
        for (seg in segments(path)) cur = (cur as? JsonObject)?.get(seg) ?: return JsonNull
        return cur
    }

    fun set(ctx: JsonObject, path: String, value: JsonElement): JsonObject = setIn(ctx, segments(path), value)

    private fun setIn(obj: JsonObject, segs: List<String>, value: JsonElement): JsonObject {
        val head = segs.first()
        val next = if (segs.size == 1) value else setIn(obj[head] as? JsonObject ?: JsonObject(emptyMap()), segs.drop(1), value)
        return JsonObject(obj + (head to next))
    }

    private fun segments(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }

    /** Applies the ops in order, then recomputes every `derive` (boundary §1.3). Unusable inputs are logged no-ops. */
    fun assign(
        ctx: JsonObject,
        ops: List<AssignOp>,
        inputs: AssignInputs,
        fields: Map<String, ContextField>,
        log: FlowLog,
    ): JsonObject {
        if (ops.isEmpty()) return ctx
        var out = ctx
        ops.forEach { op -> out = apply(out, op, inputs, log) }
        return derive(out, fields)
    }

    private fun apply(ctx: JsonObject, op: AssignOp, inputs: AssignInputs, log: FlowLog): JsonObject = when (op) {
        is AssignOp.Set -> set(ctx, op.path, op.from?.let { read(ctx, it, inputs) } ?: op.value ?: JsonNull)
        is AssignOp.Merge -> when (val from = read(ctx, op.from, inputs)) {
            is JsonObject -> set(ctx, op.path, JsonObject((get(ctx, op.path) as? JsonObject ?: JsonObject(emptyMap())) + from))
            else -> ctx.also { log.log("merge into ${op.path} skipped: the source is not an object") }
        }
        is AssignOp.ToggleItem -> toggle(ctx, op, read(ctx, op.keyFrom, inputs), log)
    }

    private fun toggle(ctx: JsonObject, op: AssignOp.ToggleItem, key: JsonElement, log: FlowLog): JsonObject {
        val list = get(ctx, op.list) as? JsonArray ?: return ctx.also { log.log("toggle_item: ${op.list} is not a list") }
        var hit = false
        val items = list.map { item ->
            val o = item as? JsonObject
            if (o != null && jsonEquals(o[op.key] ?: JsonNull, key)) {
                hit = true
                val now = (o[op.field] as? JsonPrimitive)?.content == "true"
                JsonObject(o + (op.field to JsonPrimitive(!now)))
            } else {
                item
            }
        }
        if (!hit) log.log("toggle_item: no item of ${op.list} has ${op.key} = $key")
        return set(ctx, op.list, JsonArray(items))
    }

    fun read(ctx: JsonObject, src: ValueSource, inputs: AssignInputs): JsonElement = when (src) {
        is ValueSource.Event -> inputs.event[src.field] ?: JsonNull
        is ValueSource.Result -> inputs.result[src.field] ?: JsonNull
        is ValueSource.Launch -> inputs.launch[src.arg] ?: JsonNull
        is ValueSource.Context -> get(ctx, src.path)
    }

    fun derive(ctx: JsonObject, fields: Map<String, ContextField>): JsonObject {
        var out = ctx
        fields.values.forEach { f ->
            val d = f.derive ?: return@forEach
            val items = (get(out, d.list) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .filter { Conditions.eval(d.where, item = it, ctx = out) }
            val value: JsonElement = when (d) {
                is Derive.CountWhere -> JsonPrimitive(items.size)
                is Derive.IdsWhere -> JsonArray(items.map { it[d.key] ?: JsonNull })
            }
            out = set(out, f.path, value)
        }
        return out
    }
}

/** Evaluates the condition language with the semantics of boundary §1.4.1. */
internal object Conditions {

    fun eval(c: Condition, ctx: JsonObject, item: JsonObject? = null): Boolean = when (c) {
        is Condition.All -> c.items.all { eval(it, ctx, item) }
        is Condition.AnyOf -> c.items.any { eval(it, ctx, item) }
        is Condition.Not -> !eval(c.item, ctx, item)
        is Condition.Leaf -> leaf(c, if (c.field != null) item?.get(c.field) ?: JsonNull else FlowContext.get(ctx, c.path.orEmpty()))
    }

    private fun leaf(c: Condition.Leaf, v: JsonElement): Boolean = when (c.op) {
        ConditionOp.IS_EMPTY -> isEmpty(v)
        ConditionOp.NOT_EMPTY -> !isEmpty(v)
        ConditionOp.IS_TRUE -> v is JsonPrimitive && !v.isString && v.content == "true"
        ConditionOp.IS_FALSE -> v is JsonPrimitive && !v.isString && v.content == "false"
        ConditionOp.EQ -> jsonEquals(v, c.value ?: JsonNull)
        ConditionOp.NEQ -> !jsonEquals(v, c.value ?: JsonNull)
        ConditionOp.IN -> (c.value as? JsonArray).orEmpty().any { jsonEquals(v, it) }
    }

    /** True for null, a missing value, `""` and `[]`. */
    private fun isEmpty(v: JsonElement): Boolean = when (v) {
        is JsonNull -> true
        is JsonPrimitive -> v.isString && v.content.isEmpty()
        is JsonArray -> v.isEmpty()
        is JsonObject -> false
    }
}

/** JSON equality with no coercion: the number `0` is not the string `"0"`; `1` equals `1.0`. */
internal fun jsonEquals(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
    a is JsonPrimitive && b is JsonPrimitive -> when {
        a.isString != b.isString -> false
        a.isString -> a.content == b.content
        else -> {
            val x = a.content.toDoubleOrNull()
            val y = b.content.toDoubleOrNull()
            if (x != null && y != null) x == y else a.content == b.content
        }
    }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEquals(a[it], b[it]) }
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { jsonEquals(a.getValue(it), b.getValue(it)) }
    else -> false
}
