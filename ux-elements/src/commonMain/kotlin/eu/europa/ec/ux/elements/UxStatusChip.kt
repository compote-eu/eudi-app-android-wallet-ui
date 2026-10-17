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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import eu.europa.ec.ux.theme.UxThemeAccess

private const val EL_STATUS_CHIP = "EL-status-chip"
private const val GROUP = "comp.status-chip"

/** `EL-status-chip status`: the library's thirteen words; the tone is the renderer's mapping (see [UxChipTone]). */
enum class UxChipStatus { Valid, Expiring, Invalid, Expired, Revoked, Suspended, Stale, Success, Failed, Refused, Unverified, Info, Upcoming }

/** The five tones of a chip (`comp.status-chip.<tone>-*`): the library maps each status onto one. */
enum class UxChipTone { Success, Error, Warning, Info, Neutral }

/** The library's status-to-tone mapping (`EL-status-chip` `status` description); upcoming is neutral (CAN-090). */
val UxChipStatus.tone: UxChipTone
    get() = when (this) {
        UxChipStatus.Valid, UxChipStatus.Success -> UxChipTone.Success
        UxChipStatus.Invalid, UxChipStatus.Revoked, UxChipStatus.Failed, UxChipStatus.Refused -> UxChipTone.Error
        UxChipStatus.Expiring, UxChipStatus.Expired, UxChipStatus.Suspended, UxChipStatus.Stale, UxChipStatus.Unverified -> UxChipTone.Warning
        UxChipStatus.Info -> UxChipTone.Info
        UxChipStatus.Upcoming -> UxChipTone.Neutral
    }

/** What a chip of one tone draws: background, label colour, the dot and the ring of the brand's `mark` and `ring` styles. */
internal class UxChipColors(val bg: Color, val fg: Color, val dot: Color, val ring: Color)

/** The tone's colours from `comp.status-chip.*`, falling back to the shell's feedback roles. */
@Composable
internal fun chipColors(tone: UxChipTone): UxChipColors {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val name = tone.name.lowercase()
    val fb = when (tone) {
        UxChipTone.Success -> tokens.colors.feedbackSuccess
        UxChipTone.Error -> tokens.colors.feedbackError
        UxChipTone.Warning -> tokens.colors.feedbackWarning
        UxChipTone.Info -> tokens.colors.feedbackInfo
        UxChipTone.Neutral -> null
    }
    return UxChipColors(
        bg = c.color("$name-bg", fb?.bg ?: tokens.colors.surfaceRaised),
        fg = c.color("$name-fg", fb?.fg ?: tokens.colors.textSecondary),
        dot = c.color("$name-dot", fb?.border ?: tokens.colors.textSecondary),
        ring = c.color("$name-ring", fb?.ring ?: tokens.colors.borderDefault),
    )
}

/**
 * EL-status-chip: the short status of a document or a transaction, a tinted pill with a mark and the [label]. The label
 * alone carries the meaning (never colour); the brand's `mark` style picks a tone glyph (`icon`) or a colour dot (`dot`),
 * its `ring` style a 1 px ring (`ring`) or none; `upcoming` is neutral with no mark and always a ring. Inline text with no
 * role: the mark is hidden from accessibility. [decorative] is for a chip inside a part that names itself (the document
 * card: its name already says the status), so the chip adds nothing to that name. Test tags: [id], `<id>.dot`, `<id>.label`.
 */
@Composable
fun UxStatusChip(id: String, status: UxChipStatus, label: String, modifier: Modifier = Modifier, decorative: Boolean = false) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val tone = status.tone
    val colors = chipColors(tone)
    val shape = RoundedCornerShape(c.dp("radius", tokens.radius.pill))
    val dotMark = UxThemeAccess.selection("$EL_STATUS_CHIP.mark") == "dot"
    val ring = tone == UxChipTone.Neutral || UxThemeAccess.selection("$EL_STATUS_CHIP.ring") == "ring"
    val gap = c.dp("gap", tokens.space.xs)
    Row(
        modifier = modifier
            .testTag(id)
            .background(colors.bg, shape)
            .then(if (ring) Modifier.border(tokens.effects.hairline, colors.ring, shape) else Modifier)
            .padding(
                start = c.dp("padding-left", tokens.space.s),
                end = c.dp("padding-right", tokens.space.s),
                top = c.dp("padding-block", tokens.space.xs),
                bottom = c.dp("padding-block", tokens.space.xs),
            )
            .then(if (decorative) Modifier.drawnText(id, label) else Modifier.semantics(mergeDescendants = true) { }),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tone != UxChipTone.Neutral) {
            if (dotMark) {
                Box(Modifier.testTag("$id.dot").size(c.dp("dot-size", tokens.space.xs)).background(colors.dot, CircleShape))
            } else {
                PartIcon(EL_STATUS_CHIP, "tone glyph", status.name.lowercase(), colors.fg, tokens.size.iconSm)
            }
        }
        BasicText(
            label,
            style = tokens.type.status.copy(
                color = colors.fg,
                fontSize = c.sp("font-size", tokens.type.status.fontSize),
                lineHeight = c.sp("line", tokens.type.status.lineHeight),
                fontWeight = c.weight("weight", tokens.font.status),
            ),
            modifier = if (decorative) Modifier else Modifier.testTag("$id.label"),
        )
    }
}

/** The dot colour of the holder row's status line (`comp.document-card.holder-dot-*`), by the chip's tone. */
internal val UxChipTone.dotKey: String
    get() = when (this) {
        UxChipTone.Success -> "holder-dot-success"
        UxChipTone.Error -> "holder-dot-error"
        UxChipTone.Warning, UxChipTone.Info, UxChipTone.Neutral -> "holder-dot-warning"
    }
