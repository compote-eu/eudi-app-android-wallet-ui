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

import kotlinx.serialization.Serializable

/**
 * Data class representing the configuration for text elements.
 *
 * Fully serializable and KMP-clean: every Compose type is carried as a keyed enum
 * ([styleKey], [colorKey], [textAlignKey], [overflowKey]) and resolved to its concrete
 * Compose value at render time (see `toTextStyle`, `toColor`, `toTextAlign`, `toTextOverflow`).
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

/**
 * Stable identity for every text style used through [TextConfig].
 *
 * Compose `TextStyle` carries deep runtime state (font family resolvers, paint shadow,
 * brush, etc.) that does not round-trip cleanly through serialization. Instead of
 * trying to serialize the whole thing, [TextConfig] carries a [TextStyleKey] and the
 * destination resolves it to a concrete `TextStyle` at render time via
 * `toTextStyle` — picking from the project's `MaterialTheme.typography` scale.
 *
 * The full Material 3 typography scale is present so any future caller has a stable
 * choice without needing to extend this enum. Project-specific variants (e.g.
 * [BodyLargeBold]) are added below the standard scale.
 *
 * **Adding a new style:** add one enum entry here and one branch in `toTextStyle`.
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

    /** [BodyLarge] with `FontWeight.W600` applied — used for emphasized body text. */
    BodyLargeBold,
}
