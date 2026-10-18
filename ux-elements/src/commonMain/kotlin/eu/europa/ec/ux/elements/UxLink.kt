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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import eu.europa.ec.ux.theme.UxThemeAccess

/**
 * EL-link: an operable text link, underlined as well as coloured, 48 dp high. Renders nothing for a blank [href].
 * The name is the label, plus [externalLabel] for an external target (the marker glyph is hidden). Compose has no
 * link role, so the node is a plain click target named by its content (SPIKE-LOG M5). [onActivate] gets the href;
 * opening it is the runtime's job. Test tag: [id].
 */
@Composable
fun UxLink(
    id: String,
    label: String,
    href: String,
    onActivate: (String) -> Unit,
    modifier: Modifier = Modifier,
    external: Boolean = false,
    externalLabel: String? = null,
    focusRequester: FocusRequester? = null,
) {
    if (href.isBlank()) return
    val tokens = UxThemeAccess.tokens
    val name = if (external && externalLabel != null) "$label, $externalLabel" else label
    Row(
        modifier = modifier
            .testTag(id)
            .uxFocusRing(RoundedCornerShape(tokens.radius.control), focusRequester)
            .clickable(onClickLabel = label) { onActivate(href) }
            .defaultMinSize(minHeight = tokens.size.target)
            .semantics(mergeDescendants = true) { contentDescription = name },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row's content description is the whole name; the visible text is not spoken again (M7 audit: it was).
        BasicText(
            label,
            style = tokens.type.body.copy(color = tokens.colors.textLink, textDecoration = TextDecoration.Underline),
            modifier = Modifier.clearAndSetSemantics { },
        )
        // The library's marker: open-in-new for an external link, chevron-right otherwise (EL-link `assets`).
        PartIcon("EL-link", "marker", external.toString(), tokens.colors.textLink, tokens.size.iconSm, Modifier.padding(start = tokens.comp.link.gap))
    }
}
