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

package eu.europa.ec.resourceslogic.theme.values

import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.theme.templates.ThemeTextStyle
import eu.europa.ec.resourceslogic.theme.templates.ThemeTypographyTemplate
import eu.europa.ec.resourceslogic.theme.templates.structures.ThemeFont
import eu.europa.ec.resourceslogic.theme.templates.structures.ThemeFontStyle
import eu.europa.ec.resourceslogic.theme.templates.structures.ThemeFontWeight
import eu.europa.ec.resourceslogic.theme.templates.structures.ThemeTextAlign

/**
 * `sk` flavor typography — ID SK uses Source Sans 3 (bundled at R.font.source_sans_3, SIL OFL).
 *
 * This is the `sk` source-set override of the default [ThemeTypography]. It keeps the app's
 * established Material type scale (sizes/line-heights) so layouts are unaffected, and only
 * swaps the typeface (Roboto -> Source Sans 3), mirroring the default weight distribution
 * (regular body/headings, semibold for titles/labels).
 */
internal class ThemeTypography {
    companion object {
        val typo: ThemeTypographyTemplate
            get() {
                return ThemeTypographyTemplate(
                    displayLarge = ThemeTextStyle(
                        fontFamily = listOf(SourceSansRegular),
                        fontSize = 57, lineHeight = 64, letterSpacing = -0.25f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    displayMedium = ThemeTextStyle(
                        fontFamily = listOf(SourceSansRegular),
                        fontSize = 45, lineHeight = 52, letterSpacing = 0f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    displaySmall = ThemeTextStyle(
                        fontFamily = listOf(SourceSansRegular),
                        fontSize = 36, lineHeight = 44, letterSpacing = 0f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    headlineLarge = ThemeTextStyle(
                        fontFamily = listOf(SourceSansRegular),
                        fontSize = 32, lineHeight = 40, letterSpacing = 0f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    headlineMedium = ThemeTextStyle(
                        fontFamily = listOf(SourceSansRegular),
                        fontSize = 28, lineHeight = 36, letterSpacing = 0f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    headlineSmall = ThemeTextStyle(
                        fontFamily = listOf(SourceSansRegular),
                        fontSize = 24, lineHeight = 32, letterSpacing = 0f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    titleLarge = ThemeTextStyle(
                        fontFamily = listOf(SourceSansRegular),
                        fontSize = 22, lineHeight = 28, letterSpacing = 0f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    titleMedium = ThemeTextStyle(
                        fontFamily = listOf(SourceSansSemiBold),
                        fontSize = 16, lineHeight = 24, letterSpacing = 0.15f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    titleSmall = ThemeTextStyle(
                        fontFamily = listOf(SourceSansSemiBold),
                        fontSize = 14, lineHeight = 20, letterSpacing = 0.1f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    labelLarge = ThemeTextStyle(
                        fontFamily = listOf(SourceSansSemiBold),
                        fontSize = 14, lineHeight = 20, letterSpacing = 0.1f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    labelMedium = ThemeTextStyle(
                        fontFamily = listOf(SourceSansSemiBold),
                        fontSize = 12, lineHeight = 16, letterSpacing = 0.5f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    labelSmall = ThemeTextStyle(
                        fontFamily = listOf(SourceSansSemiBold),
                        fontSize = 11, lineHeight = 16, letterSpacing = 0.5f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    bodyLarge = ThemeTextStyle(
                        fontFamily = listOf(SourceSansRegular),
                        fontSize = 16, lineHeight = 24, letterSpacing = 0.5f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    bodyMedium = ThemeTextStyle(
                        fontFamily = listOf(SourceSansRegular),
                        fontSize = 14, lineHeight = 20, letterSpacing = 0.25f,
                        textAlign = ThemeTextAlign.Start
                    ),
                    bodySmall = ThemeTextStyle(
                        fontFamily = listOf(SourceSansRegular),
                        fontSize = 12, lineHeight = 16, letterSpacing = 0.4f,
                        textAlign = ThemeTextAlign.Start
                    )
                )
            }
    }
}

// Source Sans 3 is a variable font (single file); on API 29+ the requested weight is applied
// via Typeface.create, so these entries all point at the one bundled resource.
internal val SourceSansRegular = ThemeFont(
    res = R.font.source_sans_3,
    weight = ThemeFontWeight.W400,
    style = ThemeFontStyle.Normal,
)
internal val SourceSansSemiBold = ThemeFont(
    res = R.font.source_sans_3,
    weight = ThemeFontWeight.W600,
    style = ThemeFontStyle.Normal,
)
