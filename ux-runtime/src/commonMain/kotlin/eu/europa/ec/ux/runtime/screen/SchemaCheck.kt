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

import eu.europa.ec.ux.elements.registry.UxElementSchema
import eu.europa.ec.ux.elements.registry.UxNodeScope
import eu.europa.ec.ux.elements.registry.UxTemplateSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Checks a bundle's node trees against what the registry accepts (M5c, OR-2): unknown props, enum values, events,
 * slots and regions. [node] checks one node as it renders (resolved values); [problems] checks a whole screen from
 * the bundle's literals, for the log line at state entry and for tests. Nothing here names a screen or an element.
 */
internal object SchemaCheck {
    fun node(schema: UxElementSchema, node: ScreenNode, scope: UxNodeScope): List<String> =
        schema.violations(
            propNames = node.props.keys,
            nodeEvents = node.events.map { it.on },
            nodeSlots = node.slots.keys,
            value = scope::text,
            items = schema.itemsProp?.let { p -> scope.records(p).map { r -> r.keys.associateWith(r::text) } }.orEmpty(),
        )

    /** Every problem of one screen, from the bundle's own values (bound and copy props are not enum-checked here). */
    fun problems(
        tree: ScreenTree,
        elements: Map<String, UxElementSchema>,
        templates: Map<String, UxTemplateSchema>,
    ): List<String> = buildList {
        val template = tree.template
        if (template == null || template !in templates) add("${tree.id}: unknown template '$template'")
        templates[template]?.violations(tree.regions.keys)?.forEach { add("${tree.id}: $it") }
        tree.nodes.forEach { n ->
            val schema = elements[n.element]
            if (schema == null) {
                add("${tree.id}/${n.id}: unknown element '${n.element}'")
            } else {
                schema.violations(
                    propNames = n.props.keys,
                    nodeEvents = n.events.map { it.on },
                    nodeSlots = n.slots.keys,
                    value = { p -> n.props[p].str() },
                    items = schema.itemsProp?.let { rawItems(n.props[it]) }.orEmpty(),
                ).forEach { add("${tree.id}/${n.id}: $it") }
            }
        }
    }

    /** The records of a literal list prop (a bound list has no literal records). */
    private fun rawItems(value: JsonElement?): List<Map<String, String?>> =
        (value as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { o -> o.mapValues { it.value.str() } }
}
