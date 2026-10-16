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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import eu.europa.ec.ux.theme.UxThemeAccess

/** `EL-rp-identity trust`. */
enum class UxTrust { Trusted, Untrusted, Unknown }

/**
 * EL-rp-identity: who asks for data. A section of labelled paragraphs (no role): caption, name, trust badge, service,
 * reason. The badge is text ([trustLabel]) plus a hidden glyph, never colour alone. Only a trusted name is prominent
 * (title size, bold); an untrusted or unknown name stays in plain body style. Test tags: [id], `<id>.name`,
 * `<id>.trust`, `<id>.service`, `<id>.reason`.
 */
@Composable
fun UxRpIdentity(
    id: String,
    name: String,
    trust: UxTrust,
    trustLabel: String,
    modifier: Modifier = Modifier,
    service: String? = null,
    reason: String? = null,
    caption: String? = null,
    serviceLabel: String? = null,
    reasonLabel: String? = null,
) {
    val tokens = UxThemeAccess.tokens
    val c = tokens.colors
    val shape = RoundedCornerShape(tokens.radius.card)
    val badge = when (trust) {
        UxTrust.Trusted -> c.feedbackSuccess
        UxTrust.Untrusted -> c.feedbackError
        UxTrust.Unknown -> c.feedbackWarning
    }
    Column(
        modifier = modifier
            .testTag(id)
            .fillMaxWidth()
            .background(c.surfaceCard, shape)
            .border(tokens.size.focusRing / 3, c.borderDefault, shape)
            .padding(tokens.space.m),
        verticalArrangement = Arrangement.spacedBy(tokens.space.xs),
    ) {
        if (caption != null) BasicText(caption, style = tokens.type.caption.copy(color = c.textSecondary))
        val nameStyle = if (trust == UxTrust.Trusted) tokens.type.section.copy(fontWeight = FontWeight.Bold) else tokens.type.body
        BasicText(name, style = nameStyle.copy(color = c.textPrimary), modifier = Modifier.testTag("$id.name"))
        Row(
            modifier = Modifier
                .background(badge.bg, RoundedCornerShape(tokens.radius.pill))
                .padding(horizontal = tokens.space.s, vertical = tokens.space.xs)
                .testTag("$id.trust")
                .semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(tokens.space.xs),
        ) {
            Glyph(glyphOfTrust(trust), badge.fg, tokens.type.status)
            BasicText(trustLabel, style = tokens.type.status.copy(color = badge.fg))
        }
        if (service != null) Labelled(serviceLabel, service, "$id.service")
        if (reason != null) Labelled(reasonLabel, reason, "$id.reason")
    }
}

private fun glyphOfTrust(trust: UxTrust): String = when (trust) {
    UxTrust.Trusted -> "✓"
    UxTrust.Untrusted -> "!"
    UxTrust.Unknown -> "?"
}

@Composable
private fun Labelled(label: String?, value: String, tag: String) {
    val tokens = UxThemeAccess.tokens
    Column(Modifier.testTag(tag).semantics(mergeDescendants = true) { }) {
        if (label != null) BasicText(label, style = tokens.type.caption.copy(color = tokens.colors.textLabel))
        BasicText(value, style = tokens.type.small.copy(color = tokens.colors.textPrimary))
    }
}
