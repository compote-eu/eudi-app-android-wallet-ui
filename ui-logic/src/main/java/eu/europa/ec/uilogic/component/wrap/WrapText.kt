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

package eu.europa.ec.uilogic.component.wrap

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.TextLengthPreviewProvider
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import kotlinx.serialization.Serializable

/**
 * Data class representing the configuration for text elements.
 *
 * Fully serializable and KMP-clean: every Compose type is carried as a keyed enum
 * ([styleKey], [colorKey], [textAlignKey], [overflowKey]) and resolved to its concrete
 * Compose value at render time (see [toTextStyle], [toColor], [toTextAlign], [toTextOverflow]).
 * This means a `TextConfig` can be carried across a navigation argument without losing its style.
 *
 * @property styleKey Optional [TextStyleKey]. `null` means use `LocalTextStyle.current`.
 * @property colorKey Text [ColorKey]. `null` means use `MaterialTheme.colorScheme.onSurface`.
 * @property textAlignKey Horizontal alignment, defaults to [TextAlignKey.Start].
 * @property maxLines Maximum number of lines, defaults to 2.
 * @property overflowKey Overflow handling, defaults to [TextOverflowKey.Ellipsis].
 */
@Serializable
data class TextConfig(
    val styleKey: TextStyleKey? = null,
    val colorKey: ColorKey? = null,
    val textAlignKey: TextAlignKey = TextAlignKey.Start,
    val maxLines: Int = 2,
    val overflowKey: TextOverflowKey = TextOverflowKey.Ellipsis,
)

@Composable
fun WrapText(
    modifier: Modifier = Modifier,
    text: String,
    textConfig: TextConfig,
) {
    Text(
        modifier = modifier,
        text = text,
        style = textConfig.styleKey?.toTextStyle() ?: LocalTextStyle.current,
        color = textConfig.colorKey?.toColor() ?: MaterialTheme.colorScheme.onSurface,
        textAlign = textConfig.textAlignKey.toTextAlign(),
        maxLines = textConfig.maxLines,
        overflow = textConfig.overflowKey.toTextOverflow(),
    )
}

/** Resolves a [ColorKey] to a live [Color] from the current Material theme (`@Composable`). */
@Composable
fun ColorKey.toColor(): Color = when (this) {
    ColorKey.OnSurface -> MaterialTheme.colorScheme.onSurface
    ColorKey.OnSurfaceVariant -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Resolves a [TextAlignKey] to a Compose [TextAlign]. */
fun TextAlignKey.toTextAlign(): TextAlign = when (this) {
    TextAlignKey.Start -> TextAlign.Start
    TextAlignKey.Center -> TextAlign.Center
    TextAlignKey.End -> TextAlign.End
}

/** Resolves a [TextOverflowKey] to a Compose [TextOverflow]. */
fun TextOverflowKey.toTextOverflow(): TextOverflow = when (this) {
    TextOverflowKey.Clip -> TextOverflow.Clip
    TextOverflowKey.Ellipsis -> TextOverflow.Ellipsis
    TextOverflowKey.Visible -> TextOverflow.Visible
}

/**
 * Stable identity for every [TextStyle] used through [TextConfig].
 *
 * Compose [TextStyle] carries deep runtime state (font family resolvers, paint shadow,
 * brush, etc.) that does not round-trip cleanly through serialization. Instead of
 * trying to serialize the whole thing, [TextConfig] carries a [TextStyleKey] and the
 * destination resolves it to a concrete [TextStyle] at render time via
 * [toTextStyle] — picking from the project's `MaterialTheme.typography` scale.
 *
 * The full Material 3 typography scale is present so any future caller has a stable
 * choice without needing to extend this enum. Project-specific variants (e.g.
 * [BodyLargeBold]) are added below the standard scale.
 *
 * **Adding a new style:** add one enum entry here and one branch in [toTextStyle].
 * The compiler enforces the mapping is exhaustive.
 */
@Serializable
enum class TextStyleKey {
    DisplayLarge,
    DisplayMedium,
    DisplaySmall,
    HeadlineLarge,
    HeadlineMedium,
    HeadlineSmall,
    TitleLarge,
    TitleMedium,
    TitleSmall,
    BodyLarge,
    BodyMedium,
    BodySmall,
    LabelLarge,
    LabelMedium,
    LabelSmall,

    /** [BodyLarge] with [FontWeight.W600] applied — used for emphasized body text. */
    BodyLargeBold,
}

/**
 * Resolves a [TextStyleKey] to a live [TextStyle] from the current Material theme.
 * Must be called from a `@Composable` scope because it reads `MaterialTheme.typography`.
 */
@Composable
fun TextStyleKey.toTextStyle(): TextStyle = when (this) {
    TextStyleKey.DisplayLarge -> MaterialTheme.typography.displayLarge
    TextStyleKey.DisplayMedium -> MaterialTheme.typography.displayMedium
    TextStyleKey.DisplaySmall -> MaterialTheme.typography.displaySmall
    TextStyleKey.HeadlineLarge -> MaterialTheme.typography.headlineLarge
    TextStyleKey.HeadlineMedium -> MaterialTheme.typography.headlineMedium
    TextStyleKey.HeadlineSmall -> MaterialTheme.typography.headlineSmall
    TextStyleKey.TitleLarge -> MaterialTheme.typography.titleLarge
    TextStyleKey.TitleMedium -> MaterialTheme.typography.titleMedium
    TextStyleKey.TitleSmall -> MaterialTheme.typography.titleSmall
    TextStyleKey.BodyLarge -> MaterialTheme.typography.bodyLarge
    TextStyleKey.BodyMedium -> MaterialTheme.typography.bodyMedium
    TextStyleKey.BodySmall -> MaterialTheme.typography.bodySmall
    TextStyleKey.LabelLarge -> MaterialTheme.typography.labelLarge
    TextStyleKey.LabelMedium -> MaterialTheme.typography.labelMedium
    TextStyleKey.LabelSmall -> MaterialTheme.typography.labelSmall
    TextStyleKey.BodyLargeBold ->
        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W600)
}

@ThemeModePreviews
@Composable
private fun WrapTextConfigPreview(
    @PreviewParameter(TextLengthPreviewProvider::class) text: String
) {
    PreviewTheme {
        WrapText(
            text = text,
            textConfig = TextConfig(
                styleKey = TextStyleKey.BodyLarge,
                maxLines = 1,
            )
        )
    }
}