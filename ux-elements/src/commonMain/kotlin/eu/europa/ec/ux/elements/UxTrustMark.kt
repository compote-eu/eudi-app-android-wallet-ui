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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import eu.europa.ec.ux.theme.UxThemeAccess

private const val EL_TRUST_MARK = "EL-trust-mark"
private const val GROUP = "comp.trust-mark"

/** `EL-trust-mark variant`: `badge` is the mark and label in a row (the home footer entry), `full` a larger mark with the label below. */
enum class UxTrustMarkVariant { Badge, Full }

/** `EL-trust-mark art`: `official` draws the EU artwork (`trust-mark.colour`), `placeholder` only the framed box. */
enum class UxTrustMarkArt { Placeholder, Official }

/**
 * EL-trust-mark: the EU Digital Identity Wallet Trust Mark with a text [label], as an entry of the home screen (`badge`)
 * or the mark of the certification view (`full`). The label is the accessible name and the mark is decorative (hidden);
 * with an [href] the entry is a link, drawn underlined in the link colour with a chevron (Compose has no link role: a
 * click target named by its label, as EL-link); without one it is a static group of text. The artwork keeps its own
 * colours (never tinted), contain-fit at the token's height; where the brand binds none (the export binds
 * `trust-mark.colour` for no brand yet) the framed placeholder is drawn, as the library says. [onActivate] is the `activate` event. Test tags: [id],
 * `<id>.art`, `<id>.label`.
 */
@Suppress("LongParameterList")
@Composable
fun UxTrustMark(
    id: String,
    variant: UxTrustMarkVariant,
    label: String,
    modifier: Modifier = Modifier,
    art: UxTrustMarkArt = UxTrustMarkArt.Official,
    href: String? = null,
    enabled: Boolean = true,
    onActivate: (String) -> Unit = {},
) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val full = variant == UxTrustMarkVariant.Full
    val link = !href.isNullOrBlank()
    val shape = RoundedCornerShape(tokens.radius.control)
    val fg = c.color("fg", tokens.colors.textPrimary)
    val layoutModifier = modifier
        .testTag(id)
        .fillMaxWidth()
        .background(c.color("bg", tokens.colors.surfaceCard), shape)
        .then(if (link) Modifier.uxFocusRing(shape).clickable(enabled = enabled, role = Role.Button) { onActivate(href.orEmpty()) } else Modifier)
        .heightIn(min = tokens.size.target)
        .semantics(mergeDescendants = true) { contentDescription = label }
    val gap = c.dp("gap", tokens.space.m)
    val mark: @Composable () -> Unit = {
        val height = if (full) c.dp("full-art-height", tokens.size.glyphHero) else c.dp("art-height", tokens.size.glyphHero)
        val width = if (full) c.dp("full-art-width", height) else c.dp("art-width", height)
        val asset = if (art == UxTrustMarkArt.Official) partAsset(EL_TRUST_MARK, "mark") else null
        Box(Modifier.testTag("$id.art"), contentAlignment = Alignment.Center) {
            if (asset != null) {
                AssetFill(asset, c.color("art", fg), Modifier.height(height), ContentScale.Fit, Alignment.Center)
            } else {
                // The library's fallback (art absent): the framed placeholder at the mark's size, in the art colour.
                Box(Modifier.size(width = width, height = height).border(tokens.effects.hairline, c.color("art", fg), shape))
            }
        }
    }
    val text: @Composable () -> Unit = {
        BasicText(
            label,
            style = tokens.type.body.copy(
                color = if (link) c.color("link-fg", tokens.colors.textLink) else fg,
                fontSize = c.sp("font-size", tokens.type.body.fontSize),
                lineHeight = c.sp("line", tokens.type.body.lineHeight),
                fontWeight = c.weight("weight", tokens.font.semibold),
                textDecoration = if (link) TextDecoration.Underline else null,
                textAlign = if (full) TextAlign.Center else TextAlign.Start,
            ),
            modifier = Modifier.drawnText("$id.label", label),
        )
    }
    if (full) {
        Column(
            layoutModifier.padding(horizontal = c.dp("full-padding-inline", tokens.space.m), vertical = c.dp("full-padding-block", tokens.space.m)),
            verticalArrangement = Arrangement.spacedBy(gap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            mark()
            text()
        }
    } else {
        Row(
            layoutModifier.padding(horizontal = c.dp("row-padding-inline", tokens.space.m), vertical = c.dp("row-padding-block", tokens.space.s)),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            mark()
            Box(Modifier.weight(1f)) { text() }
            if (link) PartIcon(EL_TRUST_MARK, "chevron", null, tokens.colors.textPrimary, tokens.size.iconSm)
        }
    }
}
