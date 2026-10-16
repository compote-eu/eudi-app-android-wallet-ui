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

import kotlinx.serialization.json.JsonElement

/**
 * The guard and rule condition language: exactly `rule.schema.json#/$defs/condition` (boundary §1.4.1).
 * A leaf addresses a context `path`, or, inside a `derive.where`, a list item `field`.
 */
sealed interface Condition {
    data class Leaf(val path: String?, val field: String?, val op: ConditionOp, val value: JsonElement?) : Condition
    data class All(val items: List<Condition>) : Condition
    data class AnyOf(val items: List<Condition>) : Condition
    data class Not(val item: Condition) : Condition
}

enum class ConditionOp(val wire: String) {
    IS_EMPTY("is_empty"),
    NOT_EMPTY("not_empty"),
    IS_TRUE("is_true"),
    IS_FALSE("is_false"),
    EQ("eq"),
    NEQ("neq"),
    IN("in"),
}

/** Where an assigned value comes from. There are no `$` sigils (boundary §1.4.1). */
sealed interface ValueSource {
    data class Event(val field: String) : ValueSource
    data class Result(val field: String) : ValueSource
    data class Launch(val arg: String) : ValueSource
    data class Context(val path: String) : ValueSource
}

/** The minimal assign set: `set`, `merge` (shallow), `toggle_item`. */
sealed interface AssignOp {
    data class Set(val path: String, val value: JsonElement?, val from: ValueSource?) : AssignOp
    data class Merge(val path: String, val from: ValueSource) : AssignOp
    data class ToggleItem(val list: String, val key: String, val keyFrom: ValueSource, val field: String) : AssignOp
}

/** Recomputed after every assign. */
sealed interface Derive {
    val list: String
    val where: Condition

    data class CountWhere(override val list: String, override val where: Condition) : Derive
    data class IdsWhere(override val list: String, val key: String, override val where: Condition) : Derive
}
