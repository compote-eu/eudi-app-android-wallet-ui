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

/**
 * What the registry accepts of one element (M5c, OR-2): the prop names, the wire values of its enum props, the events
 * and slots. An unknown prop, variant, event or slot is an error, never a silent default: [violations] lists them and
 * the interpreter draws a tagged error node. [props] maps a prop name to the normalised wire keys of its enum
 * (see [wireKey]), or to null when the prop is free (text, boolean, list, ...).
 */
class UxElementSchema(
    val props: Map<String, Set<String>?>,
    val events: Set<String> = emptySet(),
    val slots: Set<String> = emptySet(),
    /** The list prop whose records are checked against [itemProps] (EL-attribute-list `items`), or null. */
    val itemsProp: String? = null,
    val itemProps: Map<String, Set<String>?> = emptyMap(),
) {
    /**
     * Problems of one node: [propNames] and [nodeEvents] and [nodeSlots] are what the bundle node carries, [value] its
     * resolved text of a prop, [items] the records of the [itemsProp] list (field name to resolved text).
     */
    fun violations(
        propNames: Collection<String>,
        nodeEvents: Collection<String>,
        nodeSlots: Collection<String>,
        value: (String) -> String?,
        items: List<Map<String, String?>> = emptyList(),
    ): List<String> = buildList {
        propNames.filter { it !in props }.forEach { add("unknown prop '$it'") }
        nodeEvents.filter { it !in events }.forEach { add("unknown event '$it'") }
        nodeSlots.filter { it !in slots }.forEach { add("unknown slot '$it'") }
        props.forEach { (name, keys) -> if (name in propNames) addAll(variantProblem("prop '$name'", keys, value(name))) }
        items.forEachIndexed { i, record ->
            record.keys.filter { it !in itemProps }.forEach { add("unknown field '$it' in item $i of '$itemsProp'") }
            itemProps.forEach { (field, keys) -> addAll(variantProblem("field '$field' of item $i", keys, record[field])) }
        }
    }

    private fun variantProblem(what: String, keys: Set<String>?, value: String?): List<String> =
        if (keys == null || value == null || wireKey(value) in keys) {
            emptyList()
        } else {
            listOf("$what has unknown variant '$value' (allowed: ${keys.sorted().joinToString()})")
        }
}

/** What the registry accepts of one template: its region ids. */
class UxTemplateSchema(val regions: Set<String>) {
    fun violations(regionNames: Collection<String>): List<String> =
        regionNames.filter { it !in regions }.map { "unknown region '$it'" }
}

/** A wire value as compared: `icon-primary`, `icon_primary` and `IconPrimary` are one key. */
fun wireKey(value: String): String = value.replace("-", "").replace("_", "").lowercase()

/** The enum keys of a prop, from the Kotlin enum itself, so the schema cannot drift from the variants drawn. */
fun <T : Enum<T>> enumKeys(entries: List<T>): Set<String> = entries.map { wireKey(it.name) }.toSet()
