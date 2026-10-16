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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import eu.europa.ec.ux.theme.UxAttributePairs
import eu.europa.ec.ux.theme.UxCheckboxStyle
import eu.europa.ec.ux.theme.UxThemeAccess

/** `EL-attribute-list items[].requirement`. */
enum class UxRequirement { Mandatory, Optional }

/** One entry of the bound list. [label] is the resolved copy; [value] is holder data. */
data class UxAttributeItem(
    val id: String,
    val label: String,
    val requirement: UxRequirement,
    val value: String? = null,
    val selected: Boolean = false,
    val copyable: Boolean = false,
    val photo: Boolean = false,
)

/**
 * EL-attribute-list: the requested data from one list, mandatory items first (locked, checked, tagged by
 * [mandatoryLabel] so the lock is not colour or disabled-state alone), then the optional group (real checkboxes). The
 * element is controlled: [UxAttributeItem.selected] is what is drawn, and [onToggle] is the `toggle` event (item id,
 * new value; never fired for a mandatory item). With [readonly] it is a plain list of label and value pairs with no
 * controls, tags or headings. [onCopy] is the `copy` event. The brand's `styles` choose the pair layout and the
 * checkbox look. Test tags: [id], `<id>.item.<item id>`, `<id>.copy.<item id>`.
 */
@Composable
fun UxAttributeList(
    id: String,
    items: List<UxAttributeItem>,
    modifier: Modifier = Modifier,
    readonly: Boolean = false,
    onToggle: (itemId: String, selected: Boolean) -> Unit = { _, _ -> },
    onCopy: (itemId: String) -> Unit = {},
    mandatoryHeading: String? = null,
    optionalHeading: String? = null,
    mandatoryLabel: String? = null,
    optionalLabel: String? = null,
    copyLabel: String? = null,
) {
    val tokens = UxThemeAccess.tokens
    Column(modifier.testTag(id).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(tokens.space.m)) {
        if (readonly) {
            Group(null, "$id.items") { items.forEach { AttributeRow(id, it, true, null, copyLabel, onToggle, onCopy) } }
        } else {
            val mandatory = items.filter { it.requirement == UxRequirement.Mandatory }
            val optional = items.filter { it.requirement == UxRequirement.Optional }
            if (mandatory.isNotEmpty()) {
                Group(mandatoryHeading, "$id.mandatory") {
                    mandatory.forEach { AttributeRow(id, it, false, mandatoryLabel, copyLabel, onToggle, onCopy) }
                }
            }
            if (optional.isNotEmpty()) {
                Group(optionalHeading, "$id.optional") {
                    optional.forEach { AttributeRow(id, it, false, optionalLabel, copyLabel, onToggle, onCopy) }
                }
            }
        }
    }
}

@Composable
private fun Group(headingText: String?, tag: String, content: @Composable () -> Unit) {
    val tokens = UxThemeAccess.tokens
    Column(Modifier.testTag(tag).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(tokens.space.xs)) {
        if (headingText != null) {
            BasicText(
                headingText,
                style = tokens.type.section.copy(color = tokens.colors.textHeading),
                modifier = Modifier.semantics { heading() },
            )
        }
        content()
    }
}

@Suppress("LongParameterList")
@Composable
private fun AttributeRow(
    listId: String,
    item: UxAttributeItem,
    readonly: Boolean,
    tag: String?,
    copyLabel: String?,
    onToggle: (String, Boolean) -> Unit,
    onCopy: (String) -> Unit,
) {
    val tokens = UxThemeAccess.tokens
    val brand = UxThemeAccess.brand
    val mandatory = item.requirement == UxRequirement.Mandatory
    val checked = mandatory || item.selected
    val rowModifier = Modifier
        .testTag("$listId.item.${item.id}")
        .fillMaxWidth()
        .defaultMinSize(minHeight = tokens.size.target)
        .let {
            when {
                readonly -> it.semantics(mergeDescendants = true) { }
                mandatory -> it.toggleable(value = true, enabled = false, role = Role.Checkbox, onValueChange = {})
                else -> it.toggleable(value = checked, role = Role.Checkbox) { v -> onToggle(item.id, v) }
            }
        }
    Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(tokens.space.s), verticalAlignment = Alignment.CenterVertically) {
        if (!readonly) CheckMark(checked, brand.styles.checkbox)
        val texts: @Composable () -> Unit = {
            BasicText(item.label, style = tokens.type.body.copy(color = tokens.colors.textLabel))
            if (item.value != null && !item.photo) {
                BasicText(item.value, style = tokens.type.body.copy(color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold))
            }
            if (tag != null) BasicText(tag, style = tokens.type.status.copy(color = tokens.colors.textSecondary))
        }
        if (readonly && brand.styles.attributePairs == UxAttributePairs.Columns) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) { texts() }
        } else {
            Column(Modifier.weight(1f)) { texts() }
        }
        if (item.photo && item.value != null) {
            Box(
                Modifier.size(tokens.size.target).background(tokens.colors.surfaceSubtle, RoundedCornerShape(tokens.radius.control))
                    .clearAndSetSemantics {
                        contentDescription = item.value
                        role = Role.Image
                    },
            )
        }
        if (readonly && item.copyable) {
            Box(
                Modifier.testTag("$listId.copy.${item.id}").size(tokens.size.target)
                    .clickable(role = Role.Button) { onCopy(item.id) }
                    .semantics { contentDescription = "${copyLabel.orEmpty()} ${item.label}".trim() },
                contentAlignment = Alignment.Center,
            ) { Glyph(glyphFor("copy"), tokens.colors.textLink, tokens.type.body) }
        }
    }
}

@Composable
private fun CheckMark(checked: Boolean, style: UxCheckboxStyle) {
    val tokens = UxThemeAccess.tokens
    val shape = RoundedCornerShape(tokens.radius.control)
    val fill = if (checked && style == UxCheckboxStyle.Filled) tokens.colors.actionPrimaryBg else tokens.colors.surfaceRaised
    Box(
        Modifier.size(tokens.size.iconMd).background(fill, shape).border(tokens.size.focusRing / 2, tokens.colors.actionPrimaryBg, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Glyph("✓", if (style == UxCheckboxStyle.Filled) tokens.colors.actionPrimaryFg else tokens.colors.actionPrimaryBg, tokens.type.small)
        }
    }
}
