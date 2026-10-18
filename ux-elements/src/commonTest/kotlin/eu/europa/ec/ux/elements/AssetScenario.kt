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

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.europa.ec.ux.theme.UxAssets
import eu.europa.ec.ux.theme.UxLibraryAssets
import eu.europa.ec.ux.theme.UxSpecResources
import eu.europa.ec.ux.theme.UxTheme
import eu.europa.ec.ux.theme.UxThemeAccess
import eu.europa.ec.ux.theme.generated.UxBrandConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M6c: the elements draw the library's part assets by name (`ux-library.json` rules, `ux-assets.json` bindings): a mask
 * tinted with the part's content colour, an image as is, both hidden from accessibility; an unbound illustration falls
 * back to the tone glyph; with no export resources nothing is drawn. The painter is a stand-in (the drawables are
 * `:ux-contract`'s, loaded by the host); what is asserted is which asset each part asked for and how it is painted.
 */
@OptIn(ExperimentalTestApi::class)
abstract class AssetScenario {

    private val brand = UxBrandConfig.defaultBrand.id
    private val requested = mutableListOf<String>()

    private val resources = UxSpecResources(
        assets = UxAssets.parse(
            """{"brands": {"$brand": {
              "icon.info": {"resource": "r_info", "format": "vector", "paint": "mask"},
              "icon.priority-high": {"resource": "r_priority", "format": "vector", "paint": "mask"},
              "illustration.success": {"resource": "r_success", "format": "png", "paint": "image"}}}}""",
        ),
        library = UxLibraryAssets.parse(
            """{"elements": {
              "EL-notice": {"assets": [{"part": "tone icon", "prop": "tone", "map": {"info": "icon.info"}}]},
              "EL-status-hero": {"assets": [
                {"part": "art", "prop": "tone", "map": {"success": "illustration.success", "warning": "illustration.warning"}},
                {"part": "tone glyph", "prop": "tone", "map": {"success": "icon.check", "warning": "icon.priority-high"}}]}}}""",
        ),
        painter = { resource -> requested += resource; ColorPainter(Color.Transparent) },
    )

    private fun ComposeUiTest.drawn(): List<UxAssetDrawn> =
        onAllNodes(SemanticsMatcher.keyIsDefined(UxAssetDrawnKey), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
            assertTrue(SemanticsProperties.HideFromAccessibility in n.config, "asset ${n.config[UxAssetDrawnKey]} is announced")
            n.config[UxAssetDrawnKey]
        }

    @Test
    fun aMaskIsTintedWithThePartColourAndAnImageIsDrawnAsIs() = runComposeUiTest {
        var info = Color.Unspecified
        setContent {
            UxTheme(resources = resources) {
                info = UxThemeAccess.tokens.colors.feedbackInfo.border
                Column {
                    UxNotice("n", UxNoticeTone.Info, "text")
                    UxStatusHero("h", UxHeroTone.Success, "Done")
                }
            }
        }
        assertEquals(
            listOf(UxAssetDrawn("icon.info", "r_info", info), UxAssetDrawn("illustration.success", "r_success", null)),
            drawn(),
        )
        assertEquals(listOf("r_info", "r_success"), requested.distinct())
    }

    @Test
    fun anUnboundIllustrationFallsBackToTheToneGlyph() = runComposeUiTest {
        var warning = Color.Unspecified
        setContent {
            UxTheme(resources = resources) {
                warning = UxThemeAccess.tokens.colors.feedbackWarning.border
                UxStatusHero("h", UxHeroTone.Warning, "Expired")
            }
        }
        assertEquals(listOf(UxAssetDrawn("icon.priority-high", "r_priority", warning)), drawn())
    }

    @Test
    fun withoutExportResourcesNoAssetIsDrawn() = runComposeUiTest {
        setUx {
            Column {
                UxNotice("n", UxNoticeTone.Info, "text")
                UxStatusHero("h", UxHeroTone.Success, "Done")
            }
        }
        assertEquals(emptyList(), drawn())
    }
}
