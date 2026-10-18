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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import eu.europa.ec.ux.theme.UxThemeAccess
import eu.europa.ec.ux.theme.uxElevation

private const val EL_SEGMENTED_SWITCH = "EL-segmented-switch"
private const val GROUP = "comp.segmented-switch"

/** `EL-segmented-switch layout`: `auto` is `segmented` for two options and `chips` for more (DEC-020). */
enum class UxSwitchLayout { Auto, Segmented, Chips }

/** One option of the switch: its stable [id], the visible [label] (an unavailable option says why in it), whether it is selected and unavailable. */
data class UxSwitchOption(val id: String, val label: String, val selected: Boolean, val disabled: Boolean)

/**
 * EL-segmented-switch: choose one view of the same screen from two to four options in a row. A radio group named by
 * [label] (shown above when [labelVisible], else only named), each option a radio button; selection is the raised thumb
 * (`segmented`, plus the accent ring and a check where the brand's `selected-segment` style is `accented`) or the filled
 * chip with a check (`chips`), AND the selected state, never colour alone. A disabled option stays visible and its words
 * say why. [onChange] gets the option's id; changing the option moves no focus and opens nothing. Test tags: [id],
 * `<id>.label`, `<id>.option.<option id>`.
 */
@Suppress("LongParameterList")
@Composable
fun UxSegmentedSwitch(
    id: String,
    label: String,
    options: List<UxSwitchOption>,
    modifier: Modifier = Modifier,
    labelVisible: Boolean = false,
    layout: UxSwitchLayout = UxSwitchLayout.Auto,
    enabled: Boolean = true,
    onChange: (String) -> Unit = {},
) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val chips = layout == UxSwitchLayout.Chips || (layout == UxSwitchLayout.Auto && options.size > 2)
    Column(
        modifier = modifier
            .testTag(id)
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(c.dp("gap", tokens.space.xs)),
    ) {
        if (labelVisible) {
            BasicText(
                label,
                style = tokens.type.small.copy(color = c.color("label-fg", tokens.colors.textLabel), fontWeight = c.weight("semibold", tokens.font.semibold)),
                modifier = Modifier.drawnText("$id.label", label),
            )
        }
        if (chips) Chips(id, options, enabled, onChange) else Segmented(id, options, enabled, onChange)
    }
}

@Composable
private fun optionText(selected: Boolean, disabled: Boolean, small: Boolean): TextStyle {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val base = if (small) tokens.type.small else tokens.type.body
    val fg = when {
        disabled -> c.color("label-fg", tokens.colors.textLabel)
        selected -> c.color("thumb-fg", tokens.colors.textSelected)
        else -> c.color("fg", tokens.colors.textPrimary)
    }
    return base.copy(
        color = fg,
        fontSize = if (small) base.fontSize else c.sp("font-size", base.fontSize),
        lineHeight = if (small) base.lineHeight else c.sp("line", base.lineHeight),
        fontWeight = c.weight("semibold", tokens.font.semibold),
        textAlign = TextAlign.Center,
    )
}

/** A track with the selected option as a raised thumb. */
@Composable
private fun Segmented(id: String, options: List<UxSwitchOption>, enabled: Boolean, onChange: (String) -> Unit) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val accented = UxThemeAccess.selection("$EL_SEGMENTED_SWITCH.selected") != "plain"
    val padding = c.dp("track-padding", tokens.space.xs)
    val cardRadius = c.dp("card-radius", tokens.radius.control)
    val track = RoundedCornerShape(cardRadius + padding / 2)
    val segment = RoundedCornerShape(cardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.color("track-bg", tokens.colors.surfaceSubtle), track)
            .padding(padding),
        horizontalArrangement = Arrangement.spacedBy(c.dp("options-gap", tokens.space.xs)),
    ) {
        options.forEach { o ->
            val thumb = if (o.selected) {
                Modifier
                    .uxElevation(c.shadow("thumb-shadow"), segment)
                    .background(c.color("thumb-bg", tokens.colors.surfaceRaised), segment)
                    .then(if (accented) Modifier.border(c.dp("ring-width", tokens.size.focusRing / 2), c.color("border", tokens.colors.actionPrimaryBg), segment) else Modifier)
            } else {
                Modifier
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .testTag("$id.option.${o.id}")
                    .then(thumb)
                    .uxFocusRing(segment)
                    .heightIn(min = tokens.size.target)
                    .selectable(selected = o.selected, enabled = enabled && !o.disabled, role = Role.RadioButton) { if (!o.selected) onChange(o.id) }
                    .padding(horizontal = c.dp("segment-padding-inline", tokens.space.s)),
                horizontalArrangement = Arrangement.spacedBy(c.dp("face-gap", tokens.space.xs), Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (o.selected && accented) {
                    PartIcon(EL_SEGMENTED_SWITCH, "selected check", null, c.color("thumb-fg", tokens.colors.textSelected), tokens.size.iconSm)
                }
                BasicText(o.label, style = optionText(o.selected, o.disabled, small = o.disabled), modifier = Modifier.drawnText("$id.option.${o.id}.label", o.label))
            }
        }
    }
}

/** A scrolling row of pills, the selected one filled and marked by a check. */
@Composable
private fun Chips(id: String, options: List<UxSwitchOption>, enabled: Boolean, onChange: (String) -> Unit) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val pill = RoundedCornerShape(c.dp("pill-radius", tokens.radius.pill))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(c.dp("chips-gap", tokens.space.xs)),
    ) {
        options.forEach { o ->
            val ground = if (o.selected) c.color("chip-selected-bg", tokens.colors.actionPrimaryBg) else c.color("chip-bg", tokens.colors.surfaceRaised)
            val fg = if (o.selected) c.color("chip-selected-fg", tokens.colors.actionPrimaryFg) else c.color("fg", tokens.colors.textPrimary)
            Box(
                modifier = Modifier
                    .testTag("$id.option.${o.id}")
                    .heightIn(min = tokens.size.target)
                    .selectable(selected = o.selected, enabled = enabled && !o.disabled, role = Role.RadioButton) { if (!o.selected) onChange(o.id) }
                    .padding(vertical = c.dp("chip-target-padding-block", tokens.space.xs)),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .uxFocusRing(pill)
                        .heightIn(min = c.dp("chip-min-height", tokens.size.target))
                        .clip(pill)
                        .background(ground)
                        .then(if (o.selected) Modifier else Modifier.border(tokens.effects.hairline, c.color("chip-ring", tokens.colors.borderDefault), pill))
                        .padding(
                            start = if (o.selected) c.dp("chip-selected-padding-left", tokens.space.s) else c.dp("chip-padding-inline", tokens.space.m),
                            end = c.dp("chip-padding-inline", tokens.space.m),
                        ),
                    horizontalArrangement = Arrangement.spacedBy(c.dp("face-gap", tokens.space.xs)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (o.selected) PartIcon(EL_SEGMENTED_SWITCH, "selected check", null, fg, tokens.size.iconSm)
                    BasicText(o.label, style = optionText(o.selected, o.disabled, small = false).copy(color = if (o.disabled) c.color("label-fg", fg) else fg), modifier = Modifier.drawnText("$id.option.${o.id}.label", o.label))
                }
            }
        }
    }
}
