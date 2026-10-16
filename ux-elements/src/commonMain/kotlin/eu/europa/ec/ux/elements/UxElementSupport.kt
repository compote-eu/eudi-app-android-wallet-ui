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

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
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

/**
 * A decorative glyph (`aria-hidden`): drawn, never announced. The prototype draws text glyphs where the spec binds
 * brand icon assets (deviation, SPIKE-LOG M5).
 */
@Composable
internal fun Glyph(glyph: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    Box(modifier.clearAndSetSemantics { }) { BasicText(glyph, style = style.copy(color = color)) }
}

/** Glyph for an `icon.<name>` id of the asset table; unknown ids draw a dot. */
internal fun glyphFor(icon: String?): String = when (icon) {
    "back" -> "←"
    "close" -> "×"
    "menu" -> "≡"
    "notifications" -> "♪"
    "help" -> "?"
    "qr-code" -> "▣"
    "search" -> "⌕"
    "plus", "add" -> "+"
    "copy" -> "❐"
    else -> "•"
}
