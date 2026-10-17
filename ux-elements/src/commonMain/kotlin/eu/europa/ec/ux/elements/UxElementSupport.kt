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
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.Dp
import eu.europa.ec.ux.theme.UxAsset
import eu.europa.ec.ux.theme.UxAssetPaint
import eu.europa.ec.ux.theme.UxThemeAccess

/**
 * The focus target of the screen's h1. A template provides it from its `headingFocus` parameter; the element that
 * renders the h1 (`EL-text variant=title`, `EL-status-hero`) attaches it, so the runtime can move focus to the heading
 * after a state change without knowing which element draws it.
 */
val LocalUxHeadingFocus = compositionLocalOf<FocusRequester?> { null }

/** True inside a template's action region: its buttons take the full width, as the spec's bottom actions do. */
val LocalUxFullWidthActions = compositionLocalOf { false }

/** Attaches [requester] (when given) in front of the next focus target of the chain. */
internal fun Modifier.withFocusRequester(requester: FocusRequester?): Modifier =
    if (requester == null) this else this.focusRequester(requester)

/** Makes a non-interactive node focusable, but only when a runtime focus target asks for it (no extra tab stop). */
internal fun Modifier.focusTargetIf(requester: FocusRequester?): Modifier =
    if (requester == null) this else this.focusRequester(requester).focusable()

/** The visible focus ring (`sys.color.focus.ring`, 3 px) around a focus target, drawn only while it holds focus. */
@Composable
internal fun Modifier.uxFocusRing(shape: Shape, requester: FocusRequester? = null): Modifier {
    val tokens = UxThemeAccess.tokens
    var focused by remember { mutableStateOf(false) }
    return this
        .withFocusRequester(requester)
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) Modifier.border(tokens.size.focusRing, tokens.colors.focusRing, shape) else Modifier,
        )
}

/** What a decorative asset node draws: a test hook only (the node is hidden from accessibility). */
data class UxAssetDrawn(val id: String, val resource: String, val tint: Color?)

/** The semantics key of [UxAssetDrawn]. */
val UxAssetDrawnKey = SemanticsPropertyKey<UxAssetDrawn>("UxAssetDrawn")

/**
 * The asset [element]'s [part] draws for prop value [value], by the library's rules (`ux-library.json` element
 * `assets`), bound for the active brand (`ux-assets.json`); null when the library names none or the brand binds none
 * (DEC-080: no art is invented, the caller draws its fallback or nothing).
 */
@Composable
@ReadOnlyComposable
internal fun partAsset(element: String, part: String, value: String? = null): UxAsset? {
    val resources = UxThemeAccess.resources
    val id = resources.library.assetId(element, part, value) ?: return null
    return resources.assets.asset(UxThemeAccess.brand.id, id)
}

/**
 * A decorative asset (`aria-hidden`): loaded by its resource name, a mask tinted with [tint], an image drawn as is;
 * drawn, never announced. Nothing is drawn when the host's resources cannot load the name.
 */
@Composable
internal fun AssetImage(asset: UxAsset, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    val painter = UxThemeAccess.resources.painter(asset.resource) ?: return
    val filter = if (asset.paint == UxAssetPaint.Mask) tint else null
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = filter?.let { ColorFilter.tint(it) },
        modifier = modifier.size(size).clearAndSetSemantics {
            hideFromAccessibility()
            this[UxAssetDrawnKey] = UxAssetDrawn(asset.id, asset.resource, filter)
        },
    )
}

/** [partAsset] drawn with [AssetImage], or nothing. */
@Composable
internal fun PartIcon(element: String, part: String, value: String?, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    partAsset(element, part, value)?.let { AssetImage(it, tint, size, modifier) }
}
