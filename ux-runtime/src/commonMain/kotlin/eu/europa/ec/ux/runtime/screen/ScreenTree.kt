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

import eu.europa.ec.ux.runtime.bundle.FlowBundle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One node of a screen's tree (boundary §1.7): element id, raw props, nested slots, events and node a11y. */
data class ScreenNode(
    val id: String,
    val element: String,
    val props: JsonObject,
    val slots: Map<String, List<ScreenNode>>,
    val events: List<NodeEvent>,
    val a11y: NodeA11y,
) {
    /** This node and every node below it, depth first. */
    fun walk(): Sequence<ScreenNode> = sequenceOf(this) + slots.values.asSequence().flatten().flatMap { it.walk() }
}

/** `{on: <element event>, emits: <flow event>}`. */
data class NodeEvent(val on: String, val emits: String)

data class NodeA11y(val heading: Int?, val live: String?, val inert: Boolean)

/** The screen-level a11y block (boundary §1.7, W0). */
data class ScreenA11y(
    val heading: String?,
    val onEnter: String?,
    val liveRegion: String?,
    val focusOrder: List<String>,
    val dialogLabelledBy: String?,
) {
    companion object {
        const val FOCUS_HEADING: String = "focus-heading"
        const val ANNOUNCE_LIVE: String = "announce-live"
    }
}

/** A screen's node tree, regions in template order. */
data class ScreenTree(
    val id: String,
    val template: String?,
    val regions: Map<String, List<ScreenNode>>,
    val a11y: ScreenA11y,
) {
    val nodes: List<ScreenNode> get() = regions.values.flatten().flatMap { it.walk().toList() }

    fun node(id: String): ScreenNode? = nodes.firstOrNull { it.id == id }
}

/**
 * The node trees of a bundle, read from its parsed JSON (the loader validated the semantic parts; screens are
 * descriptive, so a malformed node is skipped and reported in [problems] instead of refusing the bundle).
 */
class ScreenCatalog(bundle: FlowBundle) {
    private val raw = bundle.raw

    val problems: MutableList<String> = mutableListOf()

    val screens: Map<String, ScreenTree> = (raw["screens"] as? JsonObject).orEmpty().mapNotNull { (id, s) ->
        (s as? JsonObject)?.let { id to tree(id, it) }
    }.toMap()

    /** Context paths per screen, from each context field's `declared_by` (the screen's `data_model`). */
    val declaredPaths: Map<String, List<String>> = (raw["context"] as? JsonObject).orEmpty().entries
        .flatMap { (path, field) -> ((field as? JsonObject)?.get("declared_by") as? JsonArray).orEmpty().mapNotNull { it.str() }.map { it to path } }
        .groupBy({ it.first }, { it.second })

    /** `theme.default_brand` and `theme.default_mode` (boundary §1.9). */
    val defaultBrand: String? = (raw["theme"] as? JsonObject)?.get("default_brand").str()
    val defaultMode: String? = (raw["theme"] as? JsonObject)?.get("default_mode").str()

    private fun tree(id: String, s: JsonObject): ScreenTree {
        val regions = (s["regions"] as? JsonObject).orEmpty().mapValues { (region, nodes) -> nodes(nodes, "$id/$region") }
        val a = s["a11y"] as? JsonObject
        val a11y = ScreenA11y(
            heading = a?.get("heading").str(),
            onEnter = a?.get("on_enter").str(),
            liveRegion = a?.get("live_region").str(),
            focusOrder = (a?.get("focus_order") as? JsonArray).orEmpty().mapNotNull { it.str() },
            dialogLabelledBy = ((a?.get("dialog") as? JsonObject)?.get("labelled_by")).str(),
        )
        return ScreenTree(id, s["template"].str(), regions, a11y)
    }

    private fun nodes(e: JsonElement?, where: String): List<ScreenNode> = (e as? JsonArray).orEmpty().mapNotNull { n ->
        val o = n as? JsonObject
        val id = o?.get("id").str()
        val element = o?.get("element").str()
        if (o == null || id == null || element == null) {
            problems += "node without id or element at $where"
            return@mapNotNull null
        }
        val a = o["a11y"] as? JsonObject
        ScreenNode(
            id = id,
            element = element,
            props = (o["props"] as? JsonObject) ?: JsonObject(emptyMap()),
            slots = (o["slots"] as? JsonObject).orEmpty().mapValues { (slot, kids) -> nodes(kids, "$where/$id/$slot") },
            events = (o["events"] as? JsonArray).orEmpty().mapNotNull { ev ->
                val eo = ev as? JsonObject
                val on = eo?.get("on").str()
                val emits = eo?.get("emits").str()
                if (on != null && emits != null) NodeEvent(on, emits) else null
            },
            a11y = NodeA11y(
                heading = (a?.get("heading") as? JsonPrimitive)?.content?.toIntOrNull(),
                live = a?.get("live").str(),
                inert = (a?.get("inert") as? JsonPrimitive)?.content == "true",
            ),
        )
    }
}

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
