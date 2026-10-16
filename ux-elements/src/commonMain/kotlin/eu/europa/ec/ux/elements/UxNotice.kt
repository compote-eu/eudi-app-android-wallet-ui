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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import eu.europa.ec.ux.theme.UxFeedbackColors
import eu.europa.ec.ux.theme.UxThemeAccess
import eu.europa.ec.ux.theme.UxTokens

/** `EL-notice tone`. */
enum class UxNoticeTone { Info, Warning, Error, Success }

internal fun UxTokens.feedback(tone: UxNoticeTone): UxFeedbackColors = when (tone) {
    UxNoticeTone.Info -> colors.feedbackInfo
    UxNoticeTone.Warning -> colors.feedbackWarning
    UxNoticeTone.Error -> colors.feedbackError
    UxNoticeTone.Success -> colors.feedbackSuccess
}

internal fun glyphOf(tone: UxNoticeTone): String = when (tone) {
    UxNoticeTone.Info -> "i"
    UxNoticeTone.Warning -> "!"
    UxNoticeTone.Error -> "×"
    UxNoticeTone.Success -> "✓"
}

/**
 * EL-notice: an inline, non-blocking message (a plain note: not a live region, as the spec's v0 says). The tone shows
 * in the [title] text and a hidden glyph as well as the colour. The title and text are one merged node; [extra] (a
 * slot, for example an `EL-link`) stays its own node. Test tags: [id], `<id>.title`, `<id>.text`.
 */
@Composable
fun UxNotice(
    id: String,
    tone: UxNoticeTone,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val tokens = UxThemeAccess.tokens
    val fb = tokens.feedback(tone)
    val shape = RoundedCornerShape(tokens.radius.card)
    Row(
        modifier = modifier
            .testTag(id)
            .fillMaxWidth()
            .background(fb.bg, shape)
            .border(tokens.size.focusRing / 3, fb.border, shape)
            .padding(tokens.space.m),
        horizontalArrangement = Arrangement.spacedBy(tokens.space.s),
    ) {
        Glyph(glyphOf(tone), fb.border, tokens.type.section)
        Column(verticalArrangement = Arrangement.spacedBy(tokens.space.xs)) {
            Column(Modifier.semantics(mergeDescendants = true) { }) {
                if (title != null) {
                    BasicText(
                        title,
                        style = tokens.type.body.copy(color = fb.fg, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.testTag("$id.title"),
                    )
                }
                BasicText(text, style = tokens.type.small.copy(color = fb.fg), modifier = Modifier.testTag("$id.text"))
            }
            extra?.invoke()
        }
    }
}
