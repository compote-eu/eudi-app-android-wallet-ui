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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import eu.europa.ec.ux.theme.UxShadow
import eu.europa.ec.ux.theme.UxThemeAccess
import eu.europa.ec.ux.theme.uxElevation

/** `EL-button variant`. */
enum class UxButtonVariant { Primary, Secondary, Icon, IconPrimary, Search }

/**
 * EL-button: role Button, named by its visible label; the icon variants draw only a glyph and are named by [label]
 * (the glyph is decorative). 48 dp minimum target. [onPress] is the `press` event. `popupStateLabel` is the text a
 * screen reader appends for `popup = true` ("collapsed"; a prop the spec leaves to the renderer). Test tag: [id].
 */
@Composable
fun UxButton(
    id: String,
    label: String,
    variant: UxButtonVariant,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    icon: String? = null,
    popup: Boolean = false,
    popupStateLabel: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val tokens = UxThemeAccess.tokens
    val c = tokens.colors
    val iconOnly = variant == UxButtonVariant.Icon || variant == UxButtonVariant.IconPrimary
    val shape = when (variant) {
        UxButtonVariant.Primary, UxButtonVariant.Secondary -> RoundedCornerShape(tokens.radius.control)
        UxButtonVariant.Icon, UxButtonVariant.IconPrimary -> RoundedCornerShape(tokens.radius.pill)
        UxButtonVariant.Search -> RoundedCornerShape(tokens.radius.pill)
    }
    val bg = when (variant) {
        UxButtonVariant.Primary -> c.actionPrimaryBg
        UxButtonVariant.Secondary, UxButtonVariant.Icon -> c.actionSecondaryBg
        UxButtonVariant.IconPrimary -> c.actionFloatingBg
        UxButtonVariant.Search -> c.surfaceRaised
    }
    val fg = when (variant) {
        UxButtonVariant.Primary -> c.actionPrimaryFg
        UxButtonVariant.Secondary, UxButtonVariant.Icon -> c.actionSecondaryFg
        UxButtonVariant.IconPrimary -> c.actionFloatingFg
        UxButtonVariant.Search -> c.textSecondary
    }
    val shadow = when (variant) {
        UxButtonVariant.Icon -> tokens.comp.button.iconShadow
        UxButtonVariant.IconPrimary -> tokens.comp.button.floatingShadow
        else -> UxShadow.None
    }
    Box(
        modifier = modifier
            .then(if (LocalUxFullWidthActions.current) Modifier.fillMaxWidth() else Modifier)
            .testTag(id)
            .then(if (disabled) Modifier.alpha(tokens.effects.disabledAlpha) else Modifier)
            .uxFocusRing(shape, focusRequester)
            .uxElevation(shadow, shape)
            .clip(shape)
            .background(bg)
            .clickable(enabled = !disabled, role = Role.Button, onClick = onPress)
            .defaultMinSize(minWidth = tokens.size.target, minHeight = tokens.size.target)
            .semantics {
                if (iconOnly) contentDescription = label
                if (popup && popupStateLabel != null) stateDescription = popupStateLabel
            }
            .then(if (iconOnly) Modifier else Modifier.padding(horizontal = tokens.space.m)),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.space.s), verticalAlignment = Alignment.CenterVertically) {
            val shownIcon = icon ?: if (variant == UxButtonVariant.Search) "search" else null
            if (shownIcon != null) {
                PartIcon("EL-button", "leading icon", shownIcon, fg, UxThemeAccess.compDp("comp.button.icon-glyph-size", tokens.size.iconMd))
            }
            if (!iconOnly) BasicText(label, style = tokens.type.button.copy(color = fg, textAlign = TextAlign.Center))
        }
    }
}
