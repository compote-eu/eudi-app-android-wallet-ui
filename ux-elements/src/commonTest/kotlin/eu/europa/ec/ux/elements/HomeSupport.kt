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

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import eu.europa.ec.ux.elements.fixture.UxAssetsSnapshot
import eu.europa.ec.ux.elements.fixture.UxLibrarySnapshot
import eu.europa.ec.ux.elements.fixture.UxTokensSnapshot
import eu.europa.ec.ux.theme.UxAssets
import eu.europa.ec.ux.theme.UxBrandSelections
import eu.europa.ec.ux.theme.UxComponentTokenTable
import eu.europa.ec.ux.theme.UxLibraryAssets
import eu.europa.ec.ux.theme.UxMode
import eu.europa.ec.ux.theme.UxSpecResources
import eu.europa.ec.ux.theme.UxTheme
import eu.europa.ec.ux.theme.generated.UxBrandConfig
import kotlin.test.assertTrue

/** The export's own data (M5e): the `comp.*` table, the asset bindings, the library rules and the brand selections. */
internal object HomeSpec {
    const val EUDIW = "eudiw_v1"
    const val IDSK = "idsk"

    val tokens: UxComponentTokenTable = UxComponentTokenTable.parse(UxTokensSnapshot.JSON)

    /** The artwork's own proportion the stand-in painter reports, so the card's minimum height can be asserted. */
    val ART = Size(width = 1250f, height = 788f)

    private class Art(private val size: Size) : Painter() {
        override val intrinsicSize: Size = size

        override fun DrawScope.onDraw() = Unit
    }

    /** The resources of the export; [drawn] records each drawable the elements ask the painter for. */
    fun resources(drawn: MutableList<String> = mutableListOf(), assets: UxAssets = UxAssets.parse(UxAssetsSnapshot.JSON)): UxSpecResources = UxSpecResources(
        assets = assets,
        library = UxLibraryAssets.parse(UxLibrarySnapshot.JSON),
        componentTokens = tokens,
        selections = UxBrandSelections.parse(UxLibrarySnapshot.JSON),
        painter = { resource ->
            drawn += resource
            Art(if (resource.contains("document_art")) ART else Size.Unspecified)
        },
    )
}

/** Draws [content] in [brand] and [mode] with the export's data; the PID art is an SVG, which the stand-in painter also offers. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.setHome(
    brand: String = HomeSpec.EUDIW,
    mode: UxMode = UxMode.Light,
    resources: UxSpecResources = HomeSpec.resources(),
    content: @Composable () -> Unit,
) = setContent { UxTheme(UxBrandConfig.brands.first { it.id == brand }, mode, resources) { content() } }

/** Every asset the elements drew (the hook of [AssetImage] and [AssetFill]), in tree order; hidden from accessibility by contract. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.drawnAssets(): List<UxAssetDrawn> =
    onAllNodes(SemanticsMatcher.keyIsDefined(UxAssetDrawnKey), useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        assertTrue(SemanticsProperties.HideFromAccessibility in n.config, "asset ${n.config[UxAssetDrawnKey]} is announced")
        n.config[UxAssetDrawnKey]
    }

/** The words a card or chip drew under [tag] (the [UxDrawnTextKey] hook; the node is hidden from accessibility). */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.drawnText(tag: String): String {
    val config = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
    assertTrue(SemanticsProperties.HideFromAccessibility in config, "$tag is announced on its own")
    return config[UxDrawnTextKey]
}
