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

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import eu.europa.ec.ux.theme.UxAsset
import eu.europa.ec.ux.theme.UxAssetPaint
import eu.europa.ec.ux.theme.UxFontWeight
import eu.europa.ec.ux.theme.UxShadow
import eu.europa.ec.ux.theme.UxThemeAccess

/**
 * The `comp.<group>.*` values of one part, read from the export's `ux-tokens.json` by exact key (M5e): `UxComp("comp.chip").dp("gap")`
 * reads `comp.chip.gap`. The second argument is what the part draws when the export has no such key (a `sys.*` role from
 * the shell), so a part still draws without the table; no value of a part is written in Kotlin.
 */
internal class UxComp(private val group: String) {
    @Composable
    fun dp(name: String, fallback: Dp): Dp = UxThemeAccess.compDp("$group.$name", fallback)

    @Composable
    fun color(name: String, fallback: Color): Color = UxThemeAccess.compColor("$group.$name", fallback)

    @Composable
    fun sp(name: String, fallback: TextUnit): TextUnit = UxThemeAccess.compSp("$group.$name", fallback)

    @Composable
    fun weight(name: String, fallback: UxFontWeight): UxFontWeight = UxThemeAccess.compWeight("$group.$name", fallback)

    @Composable
    fun shadow(name: String): UxShadow = UxThemeAccess.compShadow("$group.$name")
}

/** A decorative asset filling its box (a card's artwork): hidden from accessibility, the same test hook as [AssetImage]. */
@Composable
internal fun AssetFill(
    asset: UxAsset,
    tint: Color,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.FillWidth,
    alignment: Alignment = Alignment.TopCenter,
    filter: ColorFilter? = null,
) {
    val painter = UxThemeAccess.resources.painter(asset.resource) ?: return
    val mask = if (asset.paint == UxAssetPaint.Mask) tint else null
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = scale,
        alignment = alignment,
        colorFilter = mask?.let { ColorFilter.tint(it) } ?: filter,
        modifier = modifier.clearAndSetSemantics {
            hideFromAccessibility()
            this[UxAssetDrawnKey] = UxAssetDrawn(asset.id, asset.resource, mask)
        },
    )
}

/** What a card's visual text draws: a test hook only (the node is hidden from accessibility; the card's name carries the words). */
val UxDrawnTextKey = SemanticsPropertyKey<String>("UxDrawnText")

/** A text of a part whose accessible name is set on the part itself: hidden from accessibility, tagged, text kept as a test hook. */
internal fun Modifier.drawnText(tag: String, text: String): Modifier = clearAndSetSemantics {
    testTag = tag
    hideFromAccessibility()
    this[UxDrawnTextKey] = text
}

/**
 * True while a template draws the screen's h1 inside the app bar's band (TPL-home: an app bar with no title of its own
 * shows the screen title between its controls, as the library's renderer does): `EL-text variant=title` then takes the
 * bar's title style and colours.
 */
val LocalUxBarTitle = compositionLocalOf { false }

/** Set by a template that wants to know whether its action region holds an `EL-bottom-bar` (it floats; the band goes transparent). */
val LocalUxBottomBarPresence = compositionLocalOf<MutableState<Boolean>?> { null }
