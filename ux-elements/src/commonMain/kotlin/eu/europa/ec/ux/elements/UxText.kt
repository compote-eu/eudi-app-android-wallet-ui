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

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import eu.europa.ec.ux.theme.UxThemeAccess

/** `EL-text variant`. */
enum class UxTextVariant { Title, Heading, Body, Small, Display }

/** `EL-text tone`. `Link` is presentational only: no link semantics, not focusable (an operable link is `EL-link`). */
enum class UxTextTone { Default, Secondary, Link }

/**
 * EL-text. `Title` is the screen's h1 and `Heading` an h2; both set the heading semantics (Compose has one heading
 * flag, so the level is not expressed, SPIKE-LOG M5). The title takes the template's heading focus target.
 * Test tag: [id].
 */
@Composable
fun UxText(
    id: String,
    text: String,
    variant: UxTextVariant = UxTextVariant.Body,
    modifier: Modifier = Modifier,
    tone: UxTextTone = UxTextTone.Default,
    focusRequester: FocusRequester? = null,
) {
    val tokens = UxThemeAccess.tokens
    val color = when (tone) {
        UxTextTone.Default -> if (variant == UxTextVariant.Heading) tokens.colors.textHeading else tokens.colors.textPrimary
        UxTextTone.Secondary -> tokens.colors.textSecondary
        UxTextTone.Link -> tokens.colors.textLink
    }
    val style: TextStyle = when (variant) {
        UxTextVariant.Title -> tokens.type.title
        UxTextVariant.Heading -> tokens.type.section
        UxTextVariant.Body -> tokens.type.body
        UxTextVariant.Small -> tokens.type.small
        UxTextVariant.Display -> tokens.type.display
    }
    val isHeading = variant == UxTextVariant.Title || variant == UxTextVariant.Heading
    val focus = focusRequester ?: if (variant == UxTextVariant.Title) LocalUxHeadingFocus.current else null
    BasicText(
        text = text,
        style = style.copy(color = color),
        modifier = modifier
            .testTag(id)
            .focusTargetIf(focus)
            .then(if (isHeading) Modifier.semantics { heading() } else Modifier),
    )
}
