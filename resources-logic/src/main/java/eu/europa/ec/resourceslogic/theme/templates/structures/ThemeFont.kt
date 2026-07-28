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

package eu.europa.ec.resourceslogic.theme.templates.structures

import androidx.annotation.FontRes
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import eu.europa.ec.resourceslogic.theme.templates.structures.ThemeFontStyle.Companion.toFontStyle
import eu.europa.ec.resourceslogic.theme.templates.structures.ThemeFontWeight.Companion.toFontWeight

data class ThemeFont(
    @param:FontRes val res: Int,
    val weight: ThemeFontWeight,
    val style: ThemeFontStyle
) {
    companion object {
        @OptIn(ExperimentalTextApi::class)
        fun ThemeFont.toFont(): Font {
            val fontWeight = weight.toFontWeight()
            val fontStyle = style.toFontStyle()
            return Font(
                resId = res,
                weight = fontWeight,
                style = fontStyle,
                // Drive the variable-font `wght`/`ital` axes so a variable font (e.g. the sk
                // flavor's Source Sans 3, one file for all weights) actually renders the requested
                // weight/style instead of its default. Harmless for static fonts (Roboto), which
                // ignore axes they don't declare.
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(fontWeight.weight),
                    // FontStyle.value is 0 (Normal) / 1 (Italic) — the OpenType `ital` axis convention.
                    FontVariation.italic(fontStyle.value.toFloat()),
                ),
            )
        }
    }
}