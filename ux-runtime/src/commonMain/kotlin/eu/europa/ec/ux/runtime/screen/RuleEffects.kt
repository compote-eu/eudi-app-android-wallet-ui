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

package eu.europa.ec.ux.runtime.screen

import eu.europa.ec.ux.runtime.bundle.ScreenRule
import eu.europa.ec.ux.runtime.engine.Conditions
import eu.europa.ec.ux.runtime.engine.FlowContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The screen rules' effects on the current context (boundary §1.7): rules apply in order, and a later effect on the
 * same node wins. `allow_transition` and `message` are flow concerns and ignored here (boundary Q12); an unknown
 * effect was already reported at load.
 */
internal class NodeEffects(
    private val visible: Map<String, Boolean>,
    private val enabled: Map<String, Boolean>,
    private val copy: Map<Pair<String, String>, String>,
) {
    fun isVisible(node: String): Boolean = visible[node] ?: true

    /** Null: no rule says anything, so the flow's acceptance alone decides. */
    fun enabledByRule(node: String): Boolean? = enabled[node]

    /** The copy key a `set_copy` effect put on [prop] of [node], if any. */
    fun copyOverride(node: String, prop: String): String? = copy[node to prop]

    companion object {
        fun of(rules: List<ScreenRule>, context: JsonObject): NodeEffects {
            val visible = mutableMapOf<String, Boolean>()
            val enabled = mutableMapOf<String, Boolean>()
            val copy = mutableMapOf<Pair<String, String>, String>()
            rules.filter { Conditions.eval(it.condition, context) }.forEach { rule ->
                rule.effects.forEach { e ->
                    val node = e.raw["node"].str() ?: return@forEach
                    when (e.effect) {
                        HIDE -> visible[node] = false
                        SHOW -> visible[node] = true
                        DISABLE -> enabled[node] = false
                        ENABLE -> enabled[node] = true
                        SET_COPY -> {
                            val prop = e.raw["prop"].str()
                            val key = e.raw["copy"].str()
                            if (prop != null && key != null) copy[node to prop] = key
                        }
                    }
                }
            }
            return NodeEffects(visible, enabled, copy)
        }

        const val HIDE: String = "hide"
        const val SHOW: String = "show"
        const val ENABLE: String = "enable"
        const val DISABLE: String = "disable"
        const val SET_COPY: String = "set_copy"
    }
}

/** Resolves prop values (boundary §1.7): a literal, `{copy, args?: {name: path}}` or `{bind: path}`. */
internal class PropResolver(private val context: JsonObject, private val copy: CopyResolver) {

    fun resolve(value: JsonElement?): JsonElement? {
        val o = value as? JsonObject ?: return value
        o[COPY].str()?.let { key -> return JsonPrimitive(copyText(key, o[ARGS] as? JsonObject)) }
        o[BIND].str()?.let { path -> return FlowContext.get(context, path) }
        return value
    }

    fun copyText(key: String, args: JsonObject? = null): String =
        copy.text(key, args.orEmpty().mapValues { (_, path) -> path.str()?.let { displayText(FlowContext.get(context, it)) } })

    /** The value at [path] of the context. */
    fun at(path: String): JsonElement = FlowContext.get(context, path)

    private companion object {
        const val COPY = "copy"
        const val ARGS = "args"
        const val BIND = "bind"
    }
}

/** A JSON value as display text: strings and numbers as written, null for JSON null or a structure. */
internal fun displayText(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
