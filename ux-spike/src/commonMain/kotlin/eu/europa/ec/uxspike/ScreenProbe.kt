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

package eu.europa.ec.uxspike

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a probe script asks for: the ui state, the brand and the mode (`gallery-<probe>[-<ui state>][.<brand>][.<mode>]`). */
data class ProbeSelection(val uiState: String, val brand: String, val mode: String) {
    companion object {
        /** `hc` is the short form of `high-contrast`; any other mode name is passed on and checked by the bundle loader. */
        private val MODE_ALIASES = mapOf("hc" to "high-contrast", "dhc" to "dark-high-contrast")

        /**
         * Reads the part of the script after the probe's own prefix. [rest] is `-<ui state>` plus dotted suffixes; a suffix that
         * names one of [brands] is the brand, any other is a mode. Nothing is named here: the ids come from the probe.
         */
        fun parse(rest: String, probe: ScreenProbe, brands: Set<String>): ProbeSelection {
            val parts = rest.split('.')
            val ui = parts.first().trimStart('-').ifEmpty { probe.uiStates.first() }
            val suffixes = parts.drop(1)
            val brand = suffixes.firstOrNull { it in brands } ?: probe.defaultBrand
            val mode = suffixes.firstOrNull { it !in brands }?.let { MODE_ALIASES[it] ?: it } ?: "light"
            return ProbeSelection(ui, brand, mode)
        }
    }
}

/**
 * A screen probe of the export (`<screen id>.probe.json`, `kind: screen-probe`): one screen, its context examples, its
 * `ui_states`, its rules, the per-brand gating the exporter applied and the visibility oracle (`expected`). The probe is data;
 * [bundle] turns one (brand, ui state) of it into a one-state flow bundle for the generic runtime, so the screen is drawn by
 * the interpreter from exporter output (was: the M5d fragment bundles, written by a script from the YAML spec).
 *
 * Profile gating and variants (what the M5d generator did itself): a node the brand's `gating.hidden` names is not in the
 * bundle. The export carries gating for the brands it names in `brands`; for any other brand the nodes the library's variant
 * axes omit for that brand's selections (`ux-library.json`: `variant_axes` options `omits`, `brand_selections`) are dropped,
 * so the screen is that brand's variant of the default brand's screen (the probe cannot bring back a node the default brand
 * dropped). Nothing here names a screen, an element beyond the library's own `omits` data, or a ui state.
 */
class ScreenProbe(private val raw: JsonObject, private val library: JsonObject? = null) {
    val screenId: String = raw.text("screen_id")
    val specHash: String = raw.text("spec_hash")
    val defaultBrand: String = raw.text("default_brand")
    val brands: List<String> = raw.array("brands").map { (it as JsonPrimitive).content }
    private val screen: JsonObject = raw.obj("screen")
    val uiStates: List<String> = raw.array("ui_states").map { (it as JsonObject).text("id") }

    /** The ids of the nodes of [brand]'s screen, hidden by its gating or its variants. */
    fun hidden(brand: String): Set<String> {
        val gating = raw.obj("gating")[brand] as? JsonObject
        if (gating != null) return gating.array("hidden").map { (it as JsonObject).text("node") }.toSet()
        return omitted(brand)
    }

    /** The node ids the oracle expects visible for [brand] in [uiState]; null when the export has none for that brand. */
    fun expectedVisible(brand: String, uiState: String): Set<String>? =
        ((raw.obj("expected")[brand] as? JsonObject)?.get(uiState) as? JsonObject)?.array("visible")?.map { (it as JsonPrimitive).content }?.toSet()

    /** Every node of the probe's screen (before any brand gating), with its region. */
    fun nodes(): List<Pair<String, JsonObject>> =
        screen.obj("regions").flatMap { (region, nodes) -> (nodes as JsonArray).map { region to it as JsonObject } }

    private fun omitted(brand: String): Set<String> {
        val lib = library ?: return emptySet()
        val selections = lib.obj("brand_selections")[brand] as? JsonObject ?: return emptySet()
        val axes = lib.obj("variant_axes")
        val template = screen.text("template")
        val all = nodes()
        val rules = selections.mapNotNull { (axis, option) ->
            ((axes[axis] as? JsonObject)?.get("options") as? JsonObject)?.get((option as JsonPrimitive).content) as? JsonObject
        }.flatMap { (it["omits"] as? JsonArray).orEmpty().map { rule -> rule as JsonObject } }
        return rules.flatMap { rule ->
            if (rule["template"] != null && rule.text("template") != template) return@flatMap emptyList()
            val with = rule["with"]?.let { (it as JsonPrimitive).content }
            all.filter { (region, node) ->
                node.text("element") == rule.text("element") &&
                    (rule["region"] == null || rule.text("region") == region) &&
                    (with == null || all.any { (r, other) -> r == region && other.text("element") == with })
            }.map { (_, node) -> node.text("id") }
        }.toSet()
    }

    /**
     * The one-state flow bundle of [brand] in [uiState] and [mode], as JSON text: the context is the probe's examples
     * overridden by the ui state's data, every event a node emits stays in place (so each node is enabled), no service. The
     * spec hash is the probe's, so the loader's handshake (flow id, spec hash, theme hash) applies as to any bundle.
     */
    fun bundle(brand: String, uiState: String, mode: String, flowId: String): String {
        val state = raw.array("ui_states").map { it as JsonObject }.firstOrNull { it.text("id") == uiState }
            ?: error("the probe has no ui state '$uiState' (has: ${uiStates.joinToString()})")
        val data = state["data"] as? JsonObject ?: JsonObject(emptyMap())
        val hidden = hidden(brand)
        val regions = JsonObject(
            screen.obj("regions").mapValues { (_, nodes) -> JsonArray((nodes as JsonArray).filter { (it as JsonObject).text("id") !in hidden }) }
                .filterValues { it.isNotEmpty() },
        )
        val kept = regions.values.flatMap { (it as JsonArray).map { n -> (n as JsonObject).text("id") } }.toSet()
        val emitted = regions.values.flatMap { (it as JsonArray).flatMap { n -> ((n as JsonObject)["events"] as? JsonArray).orEmpty().map { e -> (e as JsonObject).text("emits") } } }
            .distinct().sorted()
        val a11y = screen.obj("a11y")
        val bundle = buildJsonObject {
            put("bundle_schema_version", 1)
            put("flow_id", flowId)
            put("spec_hash", specHash)
            put("initial", "screen")
            put("entries", buildJsonObject { put("default", buildJsonObject { put("state", "screen") }) })
            put("process_death", buildJsonObject { put("android", "restore-per-state"); put("ios", "abandon") })
            put(
                "context",
                JsonObject(
                    raw.obj("context").mapValues { (path, field) ->
                        buildJsonObject {
                            put("type", (field as JsonObject).text("type"))
                            put("nullable", false)
                            put("default", data[path] ?: field["value"] ?: JsonNull)
                            put("declared_by", buildJsonArray { add(JsonPrimitive(screenId)) })
                        }
                    },
                ),
            )
            put("events", JsonObject(emitted.associateWith { buildJsonObject { put("source", "ui") } }))
            put("services", JsonObject(emptyMap()))
            put(
                "states",
                buildJsonObject {
                    put(
                        "screen",
                        buildJsonObject {
                            put("kind", "step")
                            put("screen", screenId)
                            put("ui_state", uiState)
                            put(
                                "on",
                                JsonObject(
                                    emitted.associateWith {
                                        buildJsonArray { add(buildJsonObject { put("target", JsonNull); put("kind", "alt") }) }
                                    },
                                ),
                            )
                            put("back", buildJsonObject { put("policy", "pass") })
                            put("restore", buildJsonObject { put("policy", "snapshot") })
                        },
                    )
                },
            )
            put(
                "screens",
                buildJsonObject {
                    put(
                        screenId,
                        buildJsonObject {
                            put("name", screen.text("name"))
                            put("template", screen.text("template"))
                            put("regions", regions)
                            put("rules", screen["rules"] ?: JsonArray(emptyList()))
                            put("ui_states", buildJsonArray { uiStates.forEach { add(JsonPrimitive(it)) } })
                            put(
                                "a11y",
                                JsonObject(
                                    a11y.mapValues { (key, v) ->
                                        if (key == "focus_order") JsonArray((v as JsonArray).filter { (it as JsonPrimitive).content in kept }) else v
                                    },
                                ),
                            )
                        },
                    )
                },
            )
            put("copy", raw["copy"] ?: JsonObject(emptyMap()))
            put(
                "theme",
                buildJsonObject {
                    put("default_brand", brand)
                    put("brands", buildJsonArray { add(JsonPrimitive(brand)) })
                    put("default_mode", mode)
                },
            )
            put("waived", JsonArray(emptyList()))
        }
        return Json.encodeToString(JsonElement.serializer(), bundle)
    }

    companion object {
        fun parse(json: String, library: String? = null): ScreenProbe {
            val root = Json.parseToJsonElement(json) as JsonObject
            require(root.text("kind") == "screen-probe") { "not a screen probe: ${root["kind"]}" }
            return ScreenProbe(root, library?.let { Json.parseToJsonElement(it) as JsonObject })
        }
    }
}

private fun JsonObject.text(key: String): String = (this[key] as JsonPrimitive).content

private fun JsonObject.obj(key: String): JsonObject = this[key] as JsonObject

private fun JsonObject.array(key: String): JsonArray = this[key] as JsonArray
