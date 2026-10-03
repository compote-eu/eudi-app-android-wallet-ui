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

// Upstream 23b98be0, in shared code; `AnnotatedString.fromHtml` is replaced by [boldMarkupToAnnotatedString]
// because Compose Multiplatform does not offer it on iOS.
package eu.europa.ec.dashboardfeature.ui.transactions.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.SIZE_SMALL
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.wrap.WrapCard

@Composable
internal fun PrivacyActionInfoCard(
    modifier: Modifier,
    label: String?,
    text: String,
) {
    WrapCard(
        modifier = modifier,
        shape = RoundedCornerShape(SIZE_SMALL.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SPACING_MEDIUM.dp),
            verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp),
        ) {
            label?.let { availableLabel ->
                Text(
                    text = availableLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun PrivacyActionExplanation(
    modifier: Modifier,
    responsibility: String,
    followUp: String,
) {
    val responsibilityText = remember(responsibility) {
        responsibility.boldMarkupToAnnotatedString()
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SPACING_LARGE.dp),
    ) {
        Text(
            text = responsibilityText,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = followUp,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The `<b>…</b>` spans of [this] in bold, the tags dropped — upstream's `AnnotatedString.fromHtml`, which
 * Compose Multiplatform has on Android but not on iOS. Bold is the only markup these strings carry, so a
 * tag of any other kind is left as text rather than guessed at. An unclosed `<b>` bolds to the end.
 */
internal fun String.boldMarkupToAnnotatedString(): AnnotatedString {
    // Named, because inside the builder `length` would be the builder's own.
    val source = this
    return buildAnnotatedString {
        var index = 0
        while (index < source.length) {
            val open = source.indexOf(BOLD_OPEN, startIndex = index, ignoreCase = true)
            if (open < 0) {
                append(source.substring(index))
                break
            }
            append(source.substring(index, open))
            val start = open + BOLD_OPEN.length
            val close = source.indexOf(BOLD_CLOSE, startIndex = start, ignoreCase = true)
                .takeIf { it >= 0 } ?: source.length
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(source.substring(start, close)) }
            index = (close + BOLD_CLOSE.length).coerceAtMost(source.length)
        }
    }
}

private const val BOLD_OPEN = "<b>"
private const val BOLD_CLOSE = "</b>"

@ThemeModePreviews
@Composable
private fun PrivacyActionInfoCardWithLabelPreview() {
    PreviewTheme {
        PrivacyActionInfoCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SPACING_MEDIUM.dp),
            label = "Responsible data protection authority",
            text = "Example data protection authority",
        )
    }
}

@ThemeModePreviews
@Composable
private fun PrivacyActionInfoCardWithoutLabelPreview() {
    PreviewTheme {
        PrivacyActionInfoCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SPACING_MEDIUM.dp),
            label = null,
            text = "This will open your email application to request data deletion from TravelBook.",
        )
    }
}

@ThemeModePreviews
@Composable
private fun PrivacyActionExplanationPreview() {
    PreviewTheme {
        PrivacyActionExplanation(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SPACING_MEDIUM.dp),
            responsibility = "<b>The wallet doesn't submit or track this report.</b> Contact the authority directly.",
            followUp = "Any updates will come from the authority, not the wallet.",
        )
    }
}