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

package eu.europa.ec.ux.elements.registry

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester

/**
 * What an element adapter sees of one bundle node (boundary §1.7), after the interpreter resolved it: literals,
 * `{copy}` and `{bind}` props arrive as plain values, rule effects are already applied, and [enabled] is false when
 * the flow does not accept the node's events now (boundary §1.6). The adapter knows prop and event names of its
 * element only; it never sees a screen or a state.
 */
interface UxNodeScope {
    val id: String

    /** False when a rule disabled the node or the current state does not accept one of its events. */
    val enabled: Boolean

    /** The screen's heading focus target, when this node is the screen's `a11y.heading`; otherwise null. */
    val focusRequester: FocusRequester?

    /** A resolved prop as text (copy, bound text, or a literal); null when absent or bound to null. */
    fun text(prop: String): String?

    /** A resolved boolean prop; null when absent or not a boolean. */
    fun flag(prop: String): Boolean?

    /** A resolved list-of-objects prop (a bound data list); empty when absent. */
    fun records(prop: String): List<UxRecord>

    /** Resolves a copy id found in data (`label_copy` of a list item, boundary §1.8). */
    fun copy(key: String): String

    fun hasSlot(slot: String): Boolean

    /** Renders the nodes of [slot] in place. */
    @Composable
    fun Slot(slot: String)

    /** The element raised its event [event] (`press`, `toggle`, …); the interpreter maps it to the node's `emits`. */
    fun emit(event: String, payload: Map<String, String> = emptyMap())
}

/** One object of a bound data list. */
interface UxRecord {
    /** The field names the record carries (checked against the element's schema). */
    val keys: Set<String>

    fun text(field: String): String?

    fun flag(field: String): Boolean?
}

/** What a template adapter sees of one screen: its regions, in the bundle's order, rendered by the interpreter. */
interface UxTemplateScope {
    /** The screen's test-tag root. */
    val id: String

    /** The text of the node the screen's `a11y.dialog.labelled_by` names (TPL-sheet), or null. */
    val dialogLabel: String?

    fun hasRegion(region: String): Boolean

    @Composable
    fun Region(region: String)
}

typealias UxElementAdapter = @Composable (UxNodeScope) -> Unit

typealias UxTemplateAdapter = @Composable (UxTemplateScope) -> Unit

/**
 * Maps a prop's wire value (`icon-primary`, `pending`) onto an enum entry by name. [default] only when the prop is
 * absent (null); a value that names no entry is an error ([UxUnknownVariantException]), never a silent default
 * (M5c, OR-2). The interpreter checks every node against its [UxElementSchema] first, so this is the backstop.
 */
fun <T : Enum<T>> wireEnum(value: String?, entries: List<T>, default: T): T {
    if (value == null) return default
    val key = wireKey(value)
    return entries.firstOrNull { wireKey(it.name) == key }
        ?: throw UxUnknownVariantException(value, entries.map { it.name })
}

/** A wire value that matches no variant of the enum it was read for. */
class UxUnknownVariantException(val value: String, val allowed: List<String>) :
    IllegalArgumentException("unknown variant '$value' (allowed: ${allowed.joinToString()})")
