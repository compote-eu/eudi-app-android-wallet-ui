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

// RESKIN PROBE (option B): thin adapters where the generated library has no element for an app component. Each one is
// composed of generated elements and reads only UxTheme tokens (no literal colour, size or weight).
package eu.europa.ec.uilogic.component.ux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemSupportingContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.ux.elements.LocalUxFullWidthActions
import eu.europa.ec.ux.elements.UxAttributeItem
import eu.europa.ec.ux.elements.UxAttributeList
import eu.europa.ec.ux.elements.UxButton
import eu.europa.ec.ux.elements.UxButtonVariant
import eu.europa.ec.ux.elements.UxLink
import eu.europa.ec.ux.elements.UxRequirement
import eu.europa.ec.ux.elements.UxText
import eu.europa.ec.ux.elements.UxTextTone
import eu.europa.ec.ux.elements.UxTextVariant
import eu.europa.ec.ux.theme.UxThemeAccess

/**
 * The app's `WrapActionCard` (title, artwork, primary action, "learn more") drawn with the generated parts: a card
 * ground from `sys.color.surface.card` / `sys.radius.card`, an `EL-text` heading, an `EL-button` primary and an `EL-link`
 * for "learn more". The library has no action-card element; the artwork stays the app's own (passed as [art]).
 */
@Composable
fun UxActionCard(
    id: String,
    title: String,
    primaryLabel: String,
    learnMoreLabel: String,
    onAction: () -> Unit,
    onLearnMore: () -> Unit,
    modifier: Modifier = Modifier,
    art: @Composable () -> Unit = {},
) {
    val tokens = UxThemeAccess.tokens
    val shape = RoundedCornerShape(tokens.radius.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.colors.surfaceCard, shape)
            .border(tokens.effects.hairline, tokens.colors.borderDefault, shape)
            .padding(tokens.space.m),
        verticalArrangement = Arrangement.spacedBy(tokens.space.m),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.space.m), verticalAlignment = Alignment.CenterVertically) {
            UxText("$id.title", title, UxTextVariant.Heading, Modifier.weight(1f))
            art()
        }
        CompositionLocalProvider(LocalUxFullWidthActions provides true) {
            UxButton("$id.primary", primaryLabel, UxButtonVariant.Primary, onPress = onAction)
        }
        UxLink("$id.learn-more", learnMoreLabel, href = "#learn-more", onActivate = { onLearnMore() })
    }
}

/**
 * The app's `WrapExpandableListItem` for one requested document: a header row (name, supporting line, expand state)
 * and, when expanded, the claims as an `EL-attribute-list`. A claim row with a disabled, checked checkbox is the
 * library's `mandatory` (locked) item, every other checkbox row an `optional` one; rows without a checkbox make the list
 * read-only. Lossy by construction (the library list is flat): a nested claim group is flattened into "group · claim"
 * labels, and an image claim shows no image.
 */
@Composable
fun UxRequestedDocument(
    id: String,
    document: ExpandableListItemUi.NestedListItem,
    selectable: Boolean,
    expandedLabel: String,
    collapsedLabel: String,
    onToggleClaim: (itemId: String) -> Unit,
    onExpandedChange: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = UxThemeAccess.tokens
    val shape = RoundedCornerShape(tokens.radius.card)
    val header = document.header
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.colors.surfaceCard, shape)
            .border(tokens.effects.hairline, tokens.colors.borderDefault, shape)
            .padding(tokens.space.m),
        verticalArrangement = Arrangement.spacedBy(tokens.space.s),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = tokens.size.target)
                .clickable(role = Role.Button) { onExpandedChange(header.itemId) }
                .semantics { stateDescription = if (document.isExpanded) expandedLabel else collapsedLabel },
            verticalArrangement = Arrangement.spacedBy(tokens.space.xs),
        ) {
            UxText("$id.name", header.mainText(), UxTextVariant.Heading)
            (header.supportingContentData as? ListItemSupportingContentDataUi.Text)?.let {
                UxText("$id.supporting", it.text, UxTextVariant.Small, tone = UxTextTone.Secondary)
            }
        }
        if (document.isExpanded) {
            val rows = document.nestedItems.flatten(prefix = null)
            UxAttributeList(
                id = "$id.claims",
                items = rows.map { it.toAttributeItem() },
                readonly = !selectable || rows.none { it.trailingContentData is ListItemTrailingContentDataUi.Checkbox },
                onToggle = { itemId, _ -> onToggleClaim(itemId) },
            )
        }
    }
}

private fun ListItemDataUi.mainText(): String = (mainContentData as? ListItemMainContentDataUi.Text)?.text.orEmpty()

private fun List<ExpandableListItemUi>.flatten(prefix: String?): List<ListItemDataUi> = flatMap { item ->
    when (item) {
        is ExpandableListItemUi.SingleListItem -> listOf(item.header.withPrefix(prefix))
        is ExpandableListItemUi.NestedListItem -> item.nestedItems.flatten(listOfNotNull(prefix, item.header.mainText()).joinToString(" · "))
    }
}

private fun ListItemDataUi.withPrefix(prefix: String?): ListItemDataUi =
    if (prefix == null) this else copy(overlineText = listOfNotNull(prefix, overlineText).joinToString(" · "))

private fun ListItemDataUi.toAttributeItem(): UxAttributeItem {
    val checkbox = (trailingContentData as? ListItemTrailingContentDataUi.Checkbox)?.checkboxData
    val locked = checkbox != null && !checkbox.enabled && checkbox.isChecked
    return UxAttributeItem(
        id = itemId,
        label = overlineText ?: mainText(),
        requirement = if (locked) UxRequirement.Mandatory else UxRequirement.Optional,
        value = if (overlineText != null) (mainContentData as? ListItemMainContentDataUi.Text)?.text else null,
        selected = checkbox?.isChecked ?: false,
    )
}
