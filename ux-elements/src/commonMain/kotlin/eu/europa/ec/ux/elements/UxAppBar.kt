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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import eu.europa.ec.ux.theme.UxThemeAccess
import eu.europa.ec.ux.theme.uxElevation

private const val EL_APP_BAR = "EL-app-bar"
private const val GROUP = "comp.app-bar"

/** `EL-app-bar leading`. */
enum class UxBarLeading { Back, Close, Menu, None }

/** `EL-app-bar trailing`. */
enum class UxBarTrailing { None, Notifications, Help }

/**
 * EL-app-bar: navigation chrome. The leading and trailing controls are 48 dp buttons named by [leadingLabel] and
 * [trailingLabel] (the unread dot is hidden, so the trailing label must say "unread"); the title is plain text and
 * never a heading (the screen's h1 stays in the content). `onLeading` and `onTrailing` are the `leading` and
 * `trailing` events. Test tags: [id], `<id>.leading`, `<id>.trailing`, `<id>.title`.
 */
@Composable
fun UxAppBar(
    id: String,
    leading: UxBarLeading,
    modifier: Modifier = Modifier,
    leadingLabel: String? = null,
    title: String? = null,
    trailing: UxBarTrailing = UxBarTrailing.None,
    trailingLabel: String? = null,
    badge: Boolean = false,
    onLeading: () -> Unit = {},
    onTrailing: () -> Unit = {},
    leadingFocus: FocusRequester? = null,
    trailingFocus: FocusRequester? = null,
) {
    val tokens = UxThemeAccess.tokens
    val bar = UxComp(GROUP)
    val fg = bar.color("fg", tokens.colors.textOnAppBar)
    Row(
        modifier = modifier
            .testTag(id)
            .fillMaxWidth()
            .uxElevation(tokens.comp.appBar.shadow, RectangleShape)
            .background(bar.color("bg", tokens.colors.surfaceAppBar))
            .heightIn(min = bar.dp("min-height", tokens.comp.appBar.minHeight))
            .padding(horizontal = bar.dp("padding-inline", tokens.space.xs)),
        horizontalArrangement = Arrangement.spacedBy(bar.dp("column-gap", tokens.space.xs)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != UxBarLeading.None) {
            BarButton("$id.leading", leadingLabel.orEmpty(), "leading control" to leading.name.lowercase(), onLeading, leadingFocus, false)
        } else {
            Box(Modifier.size(tokens.size.target))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (title != null) {
                BasicText(
                    title,
                    style = tokens.type.appBar.copy(
                        color = fg,
                        fontSize = bar.sp("title-size", tokens.type.appBar.fontSize),
                        lineHeight = bar.sp("title-line", tokens.type.appBar.lineHeight),
                        fontWeight = bar.weight("weight", tokens.comp.appBar.weight),
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("$id.title"),
                )
            }
        }
        if (trailing != UxBarTrailing.None) {
            BarButton("$id.trailing", trailingLabel.orEmpty(), "trailing control" to trailing.name.lowercase(), onTrailing, trailingFocus, badge)
        } else {
            Box(Modifier.size(tokens.size.target))
        }
    }
}

@Composable
private fun BarButton(
    tag: String,
    label: String,
    icon: Pair<String, String>,
    onClick: () -> Unit,
    focus: FocusRequester?,
    badge: Boolean,
) {
    val tokens = UxThemeAccess.tokens
    val bar = UxComp(GROUP)
    Box(
        modifier = Modifier
            .testTag(tag)
            .size(tokens.size.target)
            .uxFocusRing(CircleShape, focus)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PartIcon(EL_APP_BAR, icon.first, icon.second, bar.color("fg", tokens.colors.textOnAppBar), tokens.size.iconMd)
        if (badge) {
            // The unread dot, ringed in the bar's foreground so it shows on any band (the library's renderer: a ring of the fg colour).
            Box(
                Modifier.align(Alignment.TopEnd).padding(bar.dp("badge-offset", tokens.comp.appBar.badgeOffset))
                    .size(bar.dp("badge-size", tokens.comp.appBar.badgeSize))
                    .background(bar.color("badge", tokens.colors.feedbackError.border), CircleShape)
                    .border(tokens.effects.hairline, bar.color("fg", tokens.colors.textOnAppBar), CircleShape),
            )
        }
    }
}
