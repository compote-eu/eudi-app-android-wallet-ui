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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ux-assets.json, ux-library.json and ux-tokens.json (V32) through the parsers the elements read them with. */
class ExportedSpecResourcesTest {

    private val module = listOf(File("."), File("ux-spike")).first { File(it, "src/commonMain/composeResources").isDirectory }
    private val resources = File(module, "../ux-spec/resources")
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

    @Test
    fun theLibraryNamesTheAssetOfEachPart() {
        assertEquals("icon.info", library.assetId("EL-notice", "tone icon", "info"))
        assertEquals("illustration.wait", library.assetId("EL-status-hero", "art", "pending"))
        assertEquals("icon.open-in-new", library.assetId("EL-link", "marker", "true"))
        assertEquals("icon.check", library.assetId("EL-attribute-item", "tick"))
        assertEquals("icon.qr-code", library.assetId("EL-button", "leading icon", "qr-code"))
        assertNull(library.assetId("EL-status-chip", "tone glyph", "upcoming"))
        assertNull(library.assetId("EL-notice", "no such part", "info"))
    }

    @Test
    fun componentTokensAreReadPerBrandAndModeWithTheResolverFallback() {
        val brand = "eudiw_v1"
        assertEquals(22.dp, tokens.dp(brand, UxMode.Light, "comp.notice.icon-size"))
        assertEquals(tokens.dp(brand, UxMode.Light, "comp.notice.icon-size"), tokens.dp(brand, UxMode.Dark, "comp.notice.icon-size"))
        assertEquals(tokens.dp(brand, UxMode.HighContrast, "comp.status-hero.icon-size"), tokens.dp(brand, UxMode.DarkHighContrast, "comp.status-hero.icon-size"))
        assertNull(tokens.dp(brand, UxMode.Light, "comp.notice.icon-info"), "a colour is not a dimension")
        assertNull(tokens.dp("no-brand", UxMode.Light, "comp.notice.icon-size"))
    }
}
