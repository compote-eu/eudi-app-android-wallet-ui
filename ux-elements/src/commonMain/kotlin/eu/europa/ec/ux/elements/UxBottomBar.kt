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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.text.style.TextAlign
import eu.europa.ec.ux.theme.UxThemeAccess
import eu.europa.ec.ux.theme.uxElevation

private const val EL_BOTTOM_BAR = "EL-bottom-bar"
private const val GROUP = "comp.bottom-bar"

/** `EL-bottom-bar action`: `menu` draws the round action button beside the pill. */
enum class UxBarAction { None, Menu }

/** One destination of the bar: its stable [id], the visible [label], the icon name (an `icon.<icon>` id) and whether it is current. */
data class UxBarTab(val id: String, val label: String, val icon: String, val selected: Boolean)

/**
 * EL-bottom-bar: the wallet's top-level navigation, a floating pill of destinations (an icon over a one-or-two word label)
 * with the current one on the selected ground, and an optional round action button beside it. A navigation landmark named
 * by [label] (Compose has no landmark role: the group is a traversal group with collection info), each destination a button
 * named by its label; the current one is marked by its ground AND its selected state, never colour alone. The action button
 * is icon-only, named by [actionLabel], and advertises an expand action (the closest Compose has to `aria-haspopup=menu`).
 * [onSelect] gets the destination's id (pressing the current one does nothing); [onAction] is the `action` event. Test
 * tags: [id], `<id>.tab.<tab id>`, `<id>.action`.
 */
@Suppress("LongParameterList")
@Composable
fun UxBottomBar(
    id: String,
    label: String,
    tabs: List<UxBarTab>,
    modifier: Modifier = Modifier,
    action: UxBarAction = UxBarAction.None,
    actionLabel: String? = null,
    enabled: Boolean = true,
    onSelect: (String) -> Unit = {},
    onAction: () -> Unit = {},
    tabTag: (tabId: String) -> String = { "$id.tab.$it" },
) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val radius = RoundedCornerShape(c.dp("radius", tokens.radius.pill))
    val presence = LocalUxBottomBarPresence.current
    DisposableEffect(presence) {
        presence?.value = true
        onDispose { presence?.value = false }
    }
    Row(
        modifier = modifier.testTag(id).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(c.dp("gap", tokens.space.s)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .testTag("$id.tabs")
                .uxElevation(c.shadow("shadow"), radius)
                .background(c.color("bg", tokens.colors.surfaceRaised), radius)
                .padding(c.dp("padding", tokens.space.xs))
                .semantics {
                    isTraversalGroup = true
                    contentDescription = label
                    collectionInfo = CollectionInfo(rowCount = 1, columnCount = tabs.size)
                },
            horizontalArrangement = Arrangement.spacedBy(c.dp("tab-gap", tokens.space.xs)),
        ) {
            tabs.forEach { tab -> Tab(id, tab, radius, enabled, onSelect, Modifier.weight(1f), tabTag(tab.id)) }
        }
        if (action == UxBarAction.Menu) {
            val size = c.dp("action-size", tokens.size.target)
            Box(
                modifier = Modifier
                    .testTag("$id.action")
                    .uxElevation(c.shadow("action-shadow"), radius)
                    .size(size)
                    .uxFocusRing(radius)
                    .background(c.color("action-bg", tokens.colors.actionFloatingBg), radius)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onAction)
                    .semantics {
                        contentDescription = actionLabel.orEmpty()
                        expand { onAction(); true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                PartIcon(EL_BOTTOM_BAR, "action button", null, c.color("action-fg", tokens.colors.actionFloatingFg), c.dp("action-icon-size", tokens.size.iconMd))
            }
        }
    }
}

@Composable
private fun Tab(
    barId: String,
    tab: UxBarTab,
    shape: RoundedCornerShape,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier,
    tag: String,
) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val fg = if (tab.selected) c.color("selected-fg", tokens.colors.textSelected) else c.color("fg", tokens.colors.textSecondary)
    val ground = if (tab.selected) Modifier.background(c.color("selected-bg", tokens.colors.surfaceSelected), shape) else Modifier
    Column(
        modifier = modifier
            .testTag(tag)
            .uxFocusRing(shape)
            .then(ground)
            .heightIn(min = c.dp("tab-min-height", tokens.size.target))
            .selectable(selected = tab.selected, enabled = enabled, role = Role.Button) { if (!tab.selected) onSelect(tab.id) }
            .semantics { contentDescription = tab.label }
            .padding(horizontal = c.dp("tab-gap", tokens.space.xs)),
        verticalArrangement = Arrangement.spacedBy(c.dp("tab-row-gap", tokens.space.xs), Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PartIcon(EL_BOTTOM_BAR, "destination icon", tab.icon, fg, c.dp("tab-icon-size", tokens.size.iconMd))
        BasicText(
            tab.label,
            style = tokens.type.caption.copy(
                color = fg,
                fontSize = c.sp("label-size", tokens.type.caption.fontSize),
                lineHeight = c.sp("label-line", tokens.type.caption.lineHeight),
                fontWeight = c.weight("label-weight", tokens.font.status),
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.drawnText("$barId.tab.${tab.id}.label", tab.label),
        )
    }
}
