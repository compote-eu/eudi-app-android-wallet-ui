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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import eu.europa.ec.ux.theme.UxThemeAccess
import eu.europa.ec.ux.theme.uxElevation

private const val EL_DOCUMENT_CARD = "EL-document-card"
private const val GROUP = "comp.document-card"
private const val SCREEN = "comp.screen"

/** `EL-document-card variant`: the art card (`full`), a one-row header (`mini`), the artwork alone (`hero`), the PID as its holder (`holder`). */
enum class UxCardVariant { Full, Mini, Hero, Holder }

/** `EL-document-card doc_type`: selects the card's colours and its brand artwork (`document-art.<doc_type>`). */
enum class UxDocType { IdCard, DrivingLicence, Pid }

/** `EL-document-card status`: drawn as an [UxStatusChip] (or a status dot on the holder row). */
enum class UxDocStatus { Valid, Expiring, Invalid, Expired, Revoked, Suspended, Stale }

/** `EL-document-card storage`. */
enum class UxStorage { Phone, Cloud }

/** `EL-document-card art`: `brand` draws the active brand's artwork where it binds one, `synthetic` only the card colour. */
enum class UxCardArt { Synthetic, Brand }

/** The library's wire value of a doc type (`document-art.<doc_type>`). */
val UxDocType.wire: String
    get() = when (this) {
        UxDocType.IdCard -> "id-card"
        UxDocType.DrivingLicence -> "driving-licence"
        UxDocType.Pid -> "pid"
    }

private val UxDocStatus.chip: UxChipStatus get() = UxChipStatus.valueOf(name)

/** A document that cannot be used (DEC-025): its art is dimmed and grey; the chip still says the state in words. */
private val UxDocStatus.inactive: Boolean get() = this != UxDocStatus.Valid && this != UxDocStatus.Expiring

/**
 * EL-document-card: one eDoklad as the user recognises it. `full` is the art card of the home screen (the brand's
 * artwork under the title, the status chip and, on the `band` card face, the holder, number and storage; on the
 * `centered` face the title, English name and number centred and no holder or storage), `mini` a one-row header,
 * `hero` the artwork alone (decorative, never interactive), `holder` the PID as its holder's row (initials avatar, name,
 * status dot and line, chevron). Interactive, the whole card is one button named by the [title] and [statusLabel], then
 * the drawn holder, number and storage as its description (Compose has one name string, so they follow it after commas);
 * the art, glyphs and chip are hidden from accessibility. [onOpen] is the `open` event. Test tags: [id], `<id>.title`,
 * `<id>.title-en`, `<id>.holder`, `<id>.number`, `<id>.status` (the chip), `<id>.storage`, `<id>.initials`.
 */
@Suppress("LongParameterList")
@Composable
fun UxDocumentCard(
    id: String,
    variant: UxCardVariant,
    docType: UxDocType,
    title: String,
    status: UxDocStatus,
    statusLabel: String,
    modifier: Modifier = Modifier,
    titleEn: String? = null,
    initials: String? = null,
    holder: String? = null,
    number: String? = null,
    storage: UxStorage? = null,
    storageLabel: String? = null,
    art: UxCardArt = UxCardArt.Brand,
    interactive: Boolean = true,
    enabled: Boolean = true,
    onOpen: () -> Unit = {},
) {
    val centered = UxThemeAccess.selection("card-face") == "centered"
    val drawn = buildList {
        add(title)
        add(statusLabel)
        if (variant == UxCardVariant.Holder && holder != null) add(holder)
        if (variant == UxCardVariant.Full) {
            if (!centered) holder?.let(::add)
            number?.let(::add)
            if (!centered) storageLabel?.let(::add)
        }
    }
    val name = if (variant == UxCardVariant.Holder) listOfNotNull(holder, statusLabel).joinToString(", ") else drawn.joinToString(", ")
    val call = Call(id, interactive && variant != UxCardVariant.Hero, enabled, name, onOpen)
    val parts = Parts(docType, title, status, statusLabel, titleEn, initials, holder, number, storage, storageLabel, art)
    when (variant) {
        UxCardVariant.Full -> FullCard(id, parts, call, centered, modifier)
        UxCardVariant.Mini -> MiniCard(id, parts, call, modifier)
        UxCardVariant.Hero -> HeroCard(id, parts, modifier)
        UxCardVariant.Holder -> HolderCard(id, parts, call, modifier)
    }
}

private class Call(val id: String, val interactive: Boolean, val enabled: Boolean, val name: String, val onOpen: () -> Unit)

private class Parts(
    val docType: UxDocType,
    val title: String,
    val status: UxDocStatus,
    val statusLabel: String,
    val titleEn: String?,
    val initials: String?,
    val holder: String?,
    val number: String?,
    val storage: UxStorage?,
    val storageLabel: String?,
    val art: UxCardArt,
)

/** The card as one control (a button named [Call.name]) or, not interactive, a merged group of its words. */
@Composable
private fun Modifier.cardControl(call: Call, shape: RoundedCornerShape): Modifier =
    if (call.interactive) {
        this.uxFocusRing(shape)
            .clickable(enabled = call.enabled, role = Role.Button, onClick = call.onOpen)
            .semantics(mergeDescendants = true) { contentDescription = call.name }
    } else {
        this.semantics(mergeDescendants = true) { contentDescription = call.name }
    }

@Composable
private fun docColors(type: UxDocType): Pair<Color, Color> {
    val c = UxComp(GROUP)
    val colors = UxThemeAccess.tokens.colors
    return when (type) {
        UxDocType.IdCard -> c.color("id-card-bg", colors.documentIdCardBg) to c.color("id-card-fg", colors.documentIdCardFg)
        UxDocType.DrivingLicence -> c.color("driving-licence-bg", colors.documentDrivingLicenceBg) to
            c.color("driving-licence-fg", colors.documentDrivingLicenceFg)
        UxDocType.Pid -> c.color("pid-bg", colors.documentPidBg) to c.color("pid-fg", colors.documentPidFg)
    }
}

/** The card ground: the document colour, the PID's gradient to its accent (the art draws over it). */
@Composable
private fun Modifier.cardGround(type: UxDocType, shape: RoundedCornerShape): Modifier {
    val (bg, _) = docColors(type)
    return if (type == UxDocType.Pid) {
        val accent = UxComp(GROUP).color("pid-accent", bg)
        this.background(Brush.linearGradient(listOf(bg, accent)), shape)
    } else {
        this.background(bg, shape)
    }
}

/** The artwork layer of a card: the brand's `document-art.<doc_type>` over the ground, grey and dimmed for an unusable document. */
@Composable
private fun CardArt(id: String, p: Parts, modifier: Modifier, scale: ContentScale = ContentScale.FillWidth) {
    if (p.art != UxCardArt.Brand) return
    val asset = partAsset(EL_DOCUMENT_CARD, "card art", p.docType.wire) ?: return
    val grey = if (p.status.inactive) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null
    val dim = if (p.status.inactive) Modifier.alpha(UxThemeAccess.tokens.effects.dimmedAlpha) else Modifier
    AssetFill(asset, docColors(p.docType).second, modifier.testTag("$id.art").then(dim), scale, Alignment.TopCenter, grey)
}

@Composable
private fun FullCard(id: String, p: Parts, call: Call, centered: Boolean, modifier: Modifier) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val shape = RoundedCornerShape(c.dp("radius", tokens.radius.card))
    val (_, fg) = docColors(p.docType)
    val artRatio = artRatio(p)
    BoxWithConstraints(
        modifier = modifier
            .testTag(id)
            .fillMaxWidth()
            .uxElevation(c.shadow("shadow"), shape)
            .clip(shape)
            .cardGround(p.docType, shape)
            .cardControl(call, shape),
    ) {
        val minHeight = artRatio?.let { maxWidth / it } ?: tokens.size.target
        CardArt(id, p, Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().heightIn(min = minHeight)) {
            val inline = c.dp("body-padding-inline", tokens.space.m)
            if (centered) {
                Column(
                    modifier = bodyGrow(artRatio != null).fillMaxWidth().padding(
                        start = inline,
                        end = inline,
                        top = c.dp("body-padding-bottom", tokens.space.s),
                        bottom = c.dp("face-padding-bottom", tokens.space.xs),
                    ),
                    verticalArrangement = Arrangement.spacedBy(c.dp("face-gap", tokens.space.xs), Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CenteredFace(id, p, fg)
                }
                Box(Modifier.fillMaxWidth().padding(start = c.dp("face-chip-inset", tokens.space.s), end = c.dp("face-chip-inset", tokens.space.s), bottom = c.dp("face-chip-inset", tokens.space.s))) {
                    UxStatusChip("$id.status", p.status.chip, p.statusLabel, decorative = true)
                }
            } else {
                Column(
                    modifier = bodyGrow(artRatio != null).fillMaxWidth().padding(
                        start = inline,
                        end = inline,
                        top = c.dp("body-padding-top", tokens.space.m),
                        bottom = c.dp("body-padding-bottom", tokens.space.s),
                    ),
                    verticalArrangement = Arrangement.spacedBy(c.dp("body-gap", tokens.space.xs)),
                ) {
                    BandFace(id, p, fg)
                }
                Band(id, p)
            }
        }
    }
}

/** With artwork the body takes the card's height above the band; without it the card is as tall as its content. */
private fun ColumnScope.bodyGrow(withArt: Boolean): Modifier = if (withArt) Modifier.weight(1f) else Modifier

/** The loaded artwork's own proportion (the card keeps it as its minimum); null without artwork, the card is then as tall as its content. */
@Composable
private fun artRatio(p: Parts): Float? {
    val asset = if (p.art == UxCardArt.Brand) partAsset(EL_DOCUMENT_CARD, "card art", p.docType.wire) else null
    val size = asset?.let { UxThemeAccess.resources.painter(it.resource) }?.intrinsicSize
    return if (size != null && size.isSpecified && size.height > 0f) size.width / size.height else null
}

@Composable
private fun CenteredFace(id: String, p: Parts, fg: Color) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val centre = TextAlign.Center
    BasicText(
        p.title,
        style = tokens.type.headline.copy(
            color = fg,
            fontSize = c.sp("face-title-size", tokens.type.headline.fontSize),
            lineHeight = c.sp("face-title-line", tokens.type.headline.lineHeight),
            fontWeight = c.weight("title-weight", tokens.font.bold),
            textAlign = centre,
        ),
        modifier = Modifier.drawnText("$id.title", p.title),
    )
    p.titleEn?.let {
        BasicText(
            it,
            style = tokens.type.body.copy(
                color = fg,
                fontSize = c.sp("face-name-size", tokens.type.body.fontSize),
                lineHeight = c.sp("face-name-line", tokens.type.body.lineHeight),
                fontWeight = c.weight("face-name-weight", tokens.font.regular),
                textAlign = centre,
            ),
            modifier = Modifier.drawnText("$id.title-en", it),
        )
    }
    p.number?.let {
        BasicText(
            it,
            style = tokens.type.small.copy(
                color = c.color("face-number-fg", fg),
                fontSize = c.sp("face-number-size", tokens.type.small.fontSize),
                lineHeight = c.sp("face-number-line", tokens.type.small.lineHeight),
                fontWeight = c.weight("number-weight", tokens.font.bold),
                textAlign = centre,
            ),
            modifier = Modifier.padding(top = c.dp("face-number-margin-top", tokens.space.xs)).drawnText("$id.number", it),
        )
    }
}

@Composable
private fun ColumnScope.BandFace(id: String, p: Parts, fg: Color) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    BasicText(
        p.title,
        style = tokens.type.section.copy(
            color = fg,
            fontSize = c.sp("title-size", tokens.type.section.fontSize),
            lineHeight = c.sp("title-line", tokens.type.section.lineHeight),
            fontWeight = c.weight("title-weight", tokens.font.bold),
        ),
        modifier = Modifier.drawnText("$id.title", p.title),
    )
    p.holder?.let {
        BasicText(
            it,
            style = tokens.type.body.copy(color = fg, fontWeight = c.weight("holder-weight", tokens.font.semibold)),
            modifier = Modifier.padding(top = c.dp("holder-margin-top", tokens.space.xs)).drawnText("$id.holder", it),
        )
    }
    p.number?.let {
        BasicText(
            it,
            style = tokens.type.small.copy(color = fg, fontWeight = c.weight("number-weight", tokens.font.bold)),
            modifier = Modifier.drawnText("$id.number", it),
        )
    }
}

/** The foot of a `band` card: the status chip and where the document is kept (a glyph and its words). */
@Composable
private fun Band(id: String, p: Parts) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val bandFg = c.color("band-fg", tokens.colors.textPrimary)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = c.dp("band-min-height", tokens.size.target))
            .background(c.color("band-bg", tokens.colors.surfaceRaised))
            .padding(
                start = c.dp("band-padding-left", tokens.space.s),
                end = c.dp("band-padding-right", tokens.space.s),
                top = c.dp("band-padding-block", tokens.space.xs),
                bottom = c.dp("band-padding-block", tokens.space.xs),
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UxStatusChip("$id.status", p.status.chip, p.statusLabel, decorative = true)
        if (p.storage != null && p.storageLabel != null) {
            Row(
                modifier = Modifier.testTag("$id.storage"),
                horizontalArrangement = Arrangement.spacedBy(c.dp("storage-gap", tokens.space.xs)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PartIcon(EL_DOCUMENT_CARD, "storage glyph", p.storage.name.lowercase(), bandFg, c.dp("storage-glyph-size", tokens.size.iconMd))
                BasicText(
                    p.storageLabel,
                    style = tokens.type.small.copy(color = bandFg),
                    modifier = Modifier.drawnText("$id.storage-label", p.storageLabel),
                )
            }
        }
    }
}

@Composable
private fun HolderCard(id: String, p: Parts, call: Call, modifier: Modifier) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val shape = RoundedCornerShape(c.dp("holder-radius", tokens.radius.card))
    val padding = c.dp("holder-padding", tokens.space.m)
    Row(
        modifier = modifier
            .testTag(id)
            .fillMaxWidth()
            .heightIn(min = c.dp("holder-min-height", tokens.size.target))
            .background(c.color("holder-bg", tokens.colors.surfaceCard), shape)
            .border(tokens.effects.hairline, c.color("holder-ring", tokens.colors.borderDefault), shape)
            .cardControl(call, shape)
            .padding(padding),
        horizontalArrangement = Arrangement.spacedBy(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatar = c.dp("holder-avatar-size", tokens.size.target)
        Box(
            Modifier.size(avatar).background(c.color("holder-avatar-bg", tokens.colors.surfaceSelected), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            p.initials?.let {
                BasicText(
                    it,
                    style = tokens.type.body.copy(
                        color = c.color("holder-avatar-fg", tokens.colors.textSelected),
                        fontSize = c.sp("font-size", tokens.type.body.fontSize),
                        fontWeight = c.weight("title-weight", tokens.font.bold),
                    ),
                    modifier = Modifier.drawnText("$id.initials", it),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(c.dp("holder-body-gap", tokens.space.xs))) {
            p.holder?.let {
                BasicText(
                    it,
                    style = tokens.type.body.copy(
                        color = tokens.colors.textPrimary,
                        fontSize = c.sp("holder-name-size", tokens.type.body.fontSize),
                        lineHeight = c.sp("holder-name-line", tokens.type.body.lineHeight),
                        fontWeight = c.weight("holder-weight", tokens.font.semibold),
                    ),
                    modifier = Modifier.drawnText("$id.holder", it),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(c.dp("holder-dot-gap", tokens.space.xs)), verticalAlignment = Alignment.CenterVertically) {
                val dot = c.color(p.status.chip.tone.dotKey, chipColors(p.status.chip.tone).dot)
                Box(Modifier.testTag("$id.dot").size(c.dp("holder-dot-size", tokens.space.xs)).background(dot, CircleShape))
                BasicText(
                    p.statusLabel,
                    style = tokens.type.small.copy(
                        color = c.color("holder-status-fg", tokens.colors.textSecondary),
                        fontSize = c.sp("small-size", tokens.type.small.fontSize),
                        lineHeight = c.sp("small-line", tokens.type.small.lineHeight),
                    ),
                    modifier = Modifier.drawnText("$id.status", p.statusLabel),
                )
            }
        }
        if (call.interactive) Chevron(tokens.colors.textPrimary)
    }
}

@Composable
private fun Chevron(tint: Color) {
    val tokens = UxThemeAccess.tokens
    PartIcon(EL_DOCUMENT_CARD, "chevron", null, tint, UxComp(GROUP).dp("holder-chevron-size", tokens.size.iconMd))
}

@Composable
private fun MiniCard(id: String, p: Parts, call: Call, modifier: Modifier) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val shape = RoundedCornerShape(UxComp(SCREEN).dp("card-radius", tokens.radius.card))
    val thumb = RoundedCornerShape(c.dp("mini-radius", tokens.radius.control))
    Row(
        modifier = modifier
            .testTag(id)
            .fillMaxWidth()
            .uxElevation(UxComp(SCREEN).shadow("card-shadow"), shape)
            .background(UxComp(SCREEN).color("card-bg", tokens.colors.surfaceCard), shape)
            .cardControl(call, shape)
            .padding(horizontal = c.dp("mini-padding-inline", tokens.space.m), vertical = c.dp("mini-padding-block", tokens.space.s)),
        horizontalArrangement = Arrangement.spacedBy(c.dp("mini-gap", tokens.space.m)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(c.dp("mini-art-width", tokens.size.target)).heightIn(min = c.dp("mini-art-height", tokens.size.target))
                .clip(thumb).cardGround(p.docType, thumb),
        ) {
            CardArt(id, p, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(c.dp("mini-body-gap", tokens.space.xs))) {
            BasicText(
                p.title,
                style = tokens.type.body.copy(color = tokens.colors.textPrimary, fontSize = c.sp("font-size", tokens.type.body.fontSize), lineHeight = c.sp("line", tokens.type.body.lineHeight)),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.drawnText("$id.title", p.title),
            )
            UxStatusChip("$id.status", p.status.chip, p.statusLabel, decorative = true)
        }
        if (call.interactive) Chevron(tokens.colors.textPrimary)
    }
}

/** The artwork alone above a detail screen's title: decorative, never interactive, no title or status drawn. */
@Composable
private fun HeroCard(id: String, p: Parts, modifier: Modifier) {
    val tokens = UxThemeAccess.tokens
    val c = UxComp(GROUP)
    val shape = RoundedCornerShape(c.dp("radius", tokens.radius.card))
    Box(
        modifier = modifier
            .testTag(id)
            .width(c.dp("hero-width", tokens.size.target))
            .uxElevation(c.shadow("shadow"), shape)
            .clip(shape)
            .cardGround(p.docType, shape)
            .heightIn(min = artRatio(p)?.let { c.dp("hero-width", tokens.size.target) / it } ?: tokens.size.target)
            .semantics(mergeDescendants = true) { },
    ) {
        CardArt(id, p, Modifier.fillMaxWidth())
    }
}
