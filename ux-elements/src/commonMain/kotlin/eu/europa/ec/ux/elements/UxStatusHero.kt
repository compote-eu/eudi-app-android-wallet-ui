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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import eu.europa.ec.ux.theme.UxFeedbackColors
import eu.europa.ec.ux.theme.UxThemeAccess

/** `EL-status-hero tone`. */
enum class UxHeroTone { Success, Error, Warning, Info, Pending }

/** `EL-status-hero art`. The prototype has no brand illustration assets, so both draw the tone glyph in a circle. */
enum class UxHeroArt { Glyph, Illustration }

/**
 * EL-status-hero: the centred outcome block (glyph, title, text). The title is the screen's h1 and takes the
 * template's heading focus target. `tone = Pending` makes the block a polite live region (title and text are read as
 * one announcement without moving focus); the other tones are not live, because a result is a new screen whose h1
 * takes focus on entry. The glyph is hidden. Test tags: [id], `<id>.title`, `<id>.text`.
 */
@Composable
fun UxStatusHero(
    id: String,
    tone: UxHeroTone,
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    @Suppress("UNUSED_PARAMETER") art: UxHeroArt = UxHeroArt.Illustration,
    focusRequester: FocusRequester? = null,
) {
    val tokens = UxThemeAccess.tokens
    val accent: UxFeedbackColors = when (tone) {
        UxHeroTone.Success -> tokens.colors.feedbackSuccess
        UxHeroTone.Error -> tokens.colors.feedbackError
        UxHeroTone.Warning -> tokens.colors.feedbackWarning
        UxHeroTone.Info, UxHeroTone.Pending -> tokens.colors.feedbackInfo
    }
    val glyph = when (tone) {
        UxHeroTone.Success -> "✓"
        UxHeroTone.Error -> "×"
        UxHeroTone.Warning -> "!"
        UxHeroTone.Info -> "i"
        UxHeroTone.Pending -> "…"
    }
    val semanticsModifier = if (tone == UxHeroTone.Pending) {
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
    } else {
        Modifier
    }
    Column(
        modifier = modifier.testTag(id).fillMaxWidth().then(semanticsModifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tokens.space.m),
    ) {
        Box(
            Modifier.size(tokens.size.glyphHero).background(accent.bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(glyph, accent.border, tokens.type.display)
        }
        val titleFocus = focusRequester ?: LocalUxHeadingFocus.current
        BasicText(
            title,
            style = tokens.type.title.copy(color = tokens.colors.textPrimary, textAlign = TextAlign.Center),
            modifier = Modifier.testTag("$id.title").focusTargetIf(titleFocus).semantics { heading() },
        )
        if (text != null) {
            BasicText(
                text,
                style = tokens.type.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center),
                modifier = Modifier.testTag("$id.text"),
            )
        }
    }
}
