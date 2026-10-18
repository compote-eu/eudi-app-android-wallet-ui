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

// M6c: the export's data files as the spike reads them. Host only: it reads ux-spec/ from disk.
package eu.europa.ec.uxspike

import androidx.compose.ui.unit.dp
import eu.europa.ec.ux.theme.UxAssetPaint
import eu.europa.ec.ux.theme.UxAssets
import eu.europa.ec.ux.theme.UxComponentTokenTable
import eu.europa.ec.ux.theme.UxLibraryAssets
import eu.europa.ec.ux.theme.UxMode
import eu.europa.ec.ux.theme.generated.UxBrandConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ux-assets.json, ux-library.json and ux-tokens.json (V32) through the parsers the elements read them with. */
class ExportedSpecResourcesTest {

    /** The export the build embeds (`ux.spec.dir`, set by the Gradle test task; M9r regen-smoke points it at a scratch copy). */
    private val spec = File(System.getProperty("ux.spec.dir") ?: error("ux.spec.dir is not set"))
    private val resources = File(spec, "resources")
    private fun text(name: String) = File(resources, "files/$name").readText()

    private val raw = Json.parseToJsonElement(text("ux-assets.json")).jsonObject.getValue("brands").jsonObject
    private val assets = UxAssets.parse(text("ux-assets.json"))
    private val library = UxLibraryAssets.parse(text("ux-library.json"))
    private val tokens = UxComponentTokenTable.parse(text("ux-tokens.json"))

    @Test
    fun everyBoundAssetIsAShippedDrawableLookedUpByItsResourceName() {
        var checked = 0
        raw.forEach { (brand, ids) ->
            ids.jsonObject.forEach { (id, a) ->
                val file = a.jsonObject.getValue("file").jsonPrimitive.content
                assertTrue(File(resources, file).isFile, "$brand $id: $file is not shipped")
                val asset = assets.asset(brand, id)
                if (file.endsWith(".svg")) {
                    assertNull(asset, "$brand $id: an SVG drawable is not offered on Android")
                } else {
                    assertEquals(file.substringAfter('/').substringBeforeLast('.'), asset!!.resource)
                    assertEquals(id.startsWith("icon."), asset.paint == UxAssetPaint.Mask, "$brand $id paint")
                }
                checked++
            }
        }
        assertEquals(File(resources, "drawable").list()!!.size, checked)
    }

    /** M9r (spike rule T1): every asset rule of the library, read from ux-library.json, answers through the parser. */
    @Test
    fun theLibraryNamesTheAssetOfEachPart() {
        val elements = Json.parseToJsonElement(text("ux-library.json")).jsonObject.getValue("elements").jsonObject
        var checked = 0
        elements.forEach { (element, e) ->
            (e.jsonObject["assets"] as? JsonArray).orEmpty().map { it.jsonObject }.forEach { rule ->
                val part = rule.getValue("part").jsonPrimitive.content
                val id = (rule["id"] as? JsonPrimitive)?.content
                val map = rule["map"] as? JsonObject
                val kind = (rule["kind"] as? JsonPrimitive)?.content
                when {
                    id != null -> assertEquals(id, library.assetId(element, part), "$element $part")
                    map != null -> map.forEach { (value, v) -> assertEquals((v as? JsonPrimitive)?.takeIf { it.isString }?.content, library.assetId(element, part, value), "$element $part $value") }
                    kind != null -> assertEquals("$kind._test-value", library.assetId(element, part, "_test-value"), "$element $part")
                }
                assertNull(library.assetId(element, "_test-no-such-part", "x"))
                checked++
            }
        }
        assertTrue(checked > 0, "the library has asset rules")
    }

    /** M9r (spike rule T1): the default brand and its tokens are read from the export, not named here. */
    @Test
    fun componentTokensAreReadPerBrandAndModeWithTheResolverFallback() {
        val brand = UxBrandConfig.defaultBrand.id
        val values = Json.parseToJsonElement(text("ux-tokens.json")).jsonObject.getValue("values").jsonObject.getValue(brand).jsonObject
        val light = values.getValue("light").jsonObject
        fun firstOf(type: String) = light.entries.first { (_, t) -> t.jsonObject["type"]?.jsonPrimitive?.content == type }
        val (dimension, token) = firstOf("dimension")
        val px = token.jsonObject.getValue("value").jsonObject.getValue("value").jsonPrimitive.content.toFloat()
        assertEquals(px.dp, tokens.dp(brand, UxMode.Light, dimension))
        if ("dark" !in values) assertEquals(tokens.dp(brand, UxMode.Light, dimension), tokens.dp(brand, UxMode.Dark, dimension), "dark falls back to light")
        if ("dark-high-contrast" !in values && "high-contrast" in values) {
            assertEquals(tokens.dp(brand, UxMode.HighContrast, dimension), tokens.dp(brand, UxMode.DarkHighContrast, dimension), "dark high contrast falls back")
        }
        assertNull(tokens.dp(brand, UxMode.Light, firstOf("color").key), "a colour is not a dimension")
        assertNull(tokens.dp("_test-no-brand", UxMode.Light, dimension))
    }
}
