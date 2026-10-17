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


package eu.europa.ec.ux.elements

import eu.europa.ec.ux.elements.fixture.UxLibrarySnapshot
import eu.europa.ec.ux.elements.registry.UxElementRegistry
import eu.europa.ec.ux.elements.registry.UxUnknownVariantException
import eu.europa.ec.ux.elements.registry.wireEnum
import eu.europa.ec.ux.elements.registry.wireKey
import eu.europa.ec.ux.theme.UxMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M5c (OR-2 (b), (c)): `wireEnum` and unknown variants or props fail loudly, and the registry's prop names, enum
 * variants, events, slots and template regions equal the library (since M6c the export's `ux-library.json`, its spec
 * commit inside; was a snapshot of `ux/library`). Every difference is either a failure or an entry of
 * [KNOWN_DEVIATIONS] or [KNOWN_GAPS] with its reason; a stale entry fails too.
 */
class RegistrySchemaTest {

    private val snapshot: JsonObject = Json.parseToJsonElement(UxLibrarySnapshot.JSON).jsonObject

    @Test
    fun wireEnumDefaultsOnlyWhenTheValueIsAbsentAndOtherwiseFailsLoudly() {
        assertEquals(UxButtonVariant.Primary, wireEnum(null, UxButtonVariant.entries, UxButtonVariant.Primary))
        assertEquals(UxButtonVariant.IconPrimary, wireEnum("icon-primary", UxButtonVariant.entries, UxButtonVariant.Primary))
        assertEquals(UxButtonVariant.Search, wireEnum("search", UxButtonVariant.entries, UxButtonVariant.Primary))
        val e = assertFailsWith<UxUnknownVariantException> { wireEnum("tertiary", UxButtonVariant.entries, UxButtonVariant.Primary) }
        assertTrue("tertiary" in e.message.orEmpty() && "Secondary" in e.message.orEmpty(), e.message)
    }

    @Test
    fun theBundlesThemeModeNamesMapOntoTheFourModes() {
        assertEquals(UxMode.HighContrast, wireEnum("high-contrast", UxMode.entries, UxMode.Light))
        assertEquals(UxMode.DarkHighContrast, wireEnum("dark-high-contrast", UxMode.entries, UxMode.Light))
        assertEquals(UxMode.Dark, wireEnum("dark", UxMode.entries, UxMode.Light))
        assertFailsWith<UxUnknownVariantException> { wireEnum("sepia", UxMode.entries, UxMode.Light) }
    }

    @Test
    fun theSchemaReportsUnknownPropsVariantsEventsSlotsAndItemFields() {
        val schema = UxElementRegistry.elementSchemas.getValue("EL-button")
        assertEquals(emptyList(), schema.violations(listOf("label", "variant"), listOf("press"), emptyList(), { if (it == "variant") "secondary" else "x" }))
        val problems = schema.violations(listOf("label", "colour", "variant"), listOf("hover"), listOf("extra"), { if (it == "variant") "ghost" else null })
        assertEquals(
            listOf(
                "unknown prop 'colour'",
                "unknown event 'hover'",
                "unknown slot 'extra'",
                "prop 'variant' has unknown variant 'ghost' (allowed: icon, iconprimary, primary, search, secondary)",
            ),
            problems,
        )
        val list = UxElementRegistry.elementSchemas.getValue("EL-attribute-list")
        val items = listOf(mapOf("id" to "a", "requirement" to "required", "weight" to "1"))
        assertEquals(
            listOf(
                "unknown field 'weight' in item 0 of 'items'",
                "field 'requirement' of item 0 has unknown variant 'required' (allowed: mandatory, optional)",
            ),
            list.violations(listOf("items"), emptyList(), emptyList(), { null }, items),
        )
        assertEquals(listOf("unknown region 'footer'"), UxElementRegistry.templateSchemas.getValue("TPL-sheet").violations(listOf("body", "footer")))
    }

    @Test
    fun theRegistryCoversExactlyTheElementsAndTemplatesOfTheLibrary() {
        // EL-attribute-item is no node of its own: its props are the record fields of EL-attribute-list `items`.
        val parts = snapshot.section("elements").keys + snapshot.section("templates").keys
        assertEquals(emptySet(), KNOWN_GAPS.keys - parts, "stale KNOWN_GAPS")
        assertEquals(snapshot.section("elements").keys - "EL-attribute-item" - KNOWN_GAPS.keys, UxElementRegistry.elements.keys)
        assertEquals(UxElementRegistry.elements.keys, UxElementRegistry.elementSchemas.keys)
        assertEquals(snapshot.section("templates").keys - KNOWN_GAPS.keys, UxElementRegistry.templates.keys)
        assertEquals(UxElementRegistry.templates.keys, UxElementRegistry.templateSchemas.keys)
    }

    @Test
    fun registryPropsVariantsEventsSlotsAndRegionsEqualTheLibraryYaml() {
        val found = mutableSetOf<String>()
        snapshot.section("elements").filterKeys { it !in KNOWN_GAPS }.forEach { (id, yaml) ->
            // EL-attribute-item is not a node: its props are the record fields of EL-attribute-list `items`.
            val schema = if (id == "EL-attribute-item") null else UxElementRegistry.elementSchemas.getValue(id)
            val yamlProps = yaml.jsonObject.section("props")
            val registryProps = if (schema != null) schema.props else UxElementRegistry.elementSchemas.getValue("EL-attribute-list").itemProps
            val scope = if (schema != null) id else "EL-attribute-list.items"
            for (prop in yamlProps.keys - registryProps.keys) found += "$scope: library prop '$prop' is not in the registry"
            for (prop in registryProps.keys - yamlProps.keys) found += "$scope: registry prop '$prop' is not in the library"
            for ((prop, def) in yamlProps) {
                val values = (def.jsonObject["values"] as? JsonArray)?.map { it.jsonPrimitive.content }?.map(::wireKey)?.toSet()
                val keys = registryProps[prop] ?: if (prop in registryProps) null else continue
                if (values != keys) found += "$scope.$prop: library variants $values, registry variants $keys"
            }
            if (schema != null) {
                val events = yaml.jsonObject["events"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                val slots = yaml.jsonObject["slots"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                if (events != schema.events) found += "$id: library events $events, registry events ${schema.events}"
                if (slots != schema.slots) found += "$id: library slots $slots, registry slots ${schema.slots}"
            }
        }
        snapshot.section("templates").filterKeys { it !in KNOWN_GAPS }.forEach { (id, yaml) ->
            val regions = yaml.jsonObject["regions"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            val registry = UxElementRegistry.templateSchemas.getValue(id).regions
            if (regions != registry) found += "$id: library regions $regions, registry regions $registry"
        }
        val unexplained = found - KNOWN_DEVIATIONS.keys
        val stale = KNOWN_DEVIATIONS.keys - found
        assertTrue(unexplained.isEmpty(), "registry differs from the library YAML (commit ${snapshot["source_commit"]}):\n" + unexplained.sorted().joinToString("\n"))
        assertTrue(stale.isEmpty(), "KNOWN_DEVIATIONS entries that no longer apply:\n" + stale.sorted().joinToString("\n"))
    }

    @Test
    fun everyAssetPartTheElementsDrawIsInTheLibrary() {
        // The elements ask the library which asset a part draws (UxLibraryAssets); a renamed part would draw nothing.
        val declared = snapshot.section("elements").flatMap { (id, e) ->
            (e.jsonObject["assets"] as? JsonArray).orEmpty().map { id to it.jsonObject["part"]!!.jsonPrimitive.content }
        }.toSet()
        assertEquals(emptySet(), ASSET_PARTS_DRAWN - declared)
    }

    @Test
    fun theSnapshotRecordsItsSourceCommit() {
        val commit = (snapshot["source_commit"] as JsonPrimitive).content
        assertEquals(40, commit.length)
        assertEquals(false, (snapshot["library_dirty"] as JsonPrimitive).content.toBoolean(), "the snapshot was taken from a dirty library")
    }

    private fun JsonObject.section(key: String): JsonObject = this[key]!!.jsonObject

    private companion object {
        /** Library parts with no registry entry yet: the Home parts (OR-8, DEC-080), a later milestone. */
        val KNOWN_GAPS: Map<String, String> = listOf(
            "EL-bottom-bar", "EL-document-card", "EL-segmented-switch", "EL-status-chip", "EL-trust-mark", "TPL-home",
        ).associateWith { "GAP (M6c): a part of the Home screen probe (OR-8), not implemented in the registry yet" }

        /** The (element, part) pairs whose asset the elements draw (`PartIcon` / `partAsset`). */
        val ASSET_PARTS_DRAWN: Set<Pair<String, String>> = setOf(
            "EL-app-bar" to "leading control",
            "EL-app-bar" to "trailing control",
            "EL-attribute-item" to "tick",
            "EL-attribute-list" to "copy button",
            "EL-button" to "leading icon",
            "EL-link" to "marker",
            "EL-notice" to "tone icon",
            "EL-rp-identity" to "trust glyph",
            "EL-status-hero" to "art",
            "EL-status-hero" to "tone glyph",
        )

        /** Difference found by the comparison to the reason it is accepted. Anything else fails the test. */
        val KNOWN_DEVIATIONS: Map<String, String> = mapOf(
            "EL-button: registry prop 'popup_state_label' is not in the library" to
                "SPIKE-LOG M5 deviation 5: the spoken state of `popup` is a prop the spec leaves to the renderer",
            "EL-attribute-list.items: registry prop 'id' is not in the library" to
                "boundary 1.7 record field: the id the `toggle` and `copy` events carry (item_id)",
            "EL-attribute-list.items: registry prop 'label_copy' is not in the library" to
                "boundary 1.7 record field: a copy id found in data, resolved by the interpreter (M5b)",
            "EL-attribute-list.items: registry prop 'copyable' is not in the library" to
                "boundary 1.7 record field: draws the copy control and raises `copy`",
            "EL-attribute-list.items: registry prop 'media' is not in the library" to
                "boundary 1.7 record field: `photo` draws the photo placeholder",
            "EL-attribute-list.items: library prop 'requirement_label' is not in the registry" to
                "GAP: the registry uses the list-level mandatory_label/optional_label; a per-item tag text is not drawn",
        )
    }
}
