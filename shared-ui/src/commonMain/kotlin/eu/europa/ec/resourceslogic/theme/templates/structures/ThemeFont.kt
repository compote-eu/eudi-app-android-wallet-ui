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

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import eu.europa.ec.resourceslogic.theme.templates.structures.ThemeFontStyle.Companion.toFontStyle
import eu.europa.ec.resourceslogic.theme.templates.structures.ThemeFontWeight.Companion.toFontWeight
import org.jetbrains.compose.resources.FontResource

/**
 * A font face in the theme, as a *description* rather than a loaded [Font].
 *
 * [res] is a compose-resources [FontResource] rather than the Android `@FontRes` int it used to be,
 * so the theme's typography works on both platforms — the same migration the drawable corpus went
 * through. The consequence is that [toFont] is `@Composable`, because that is how compose-resources
 * loads a font, which is why the typography is resolved inside `ThemeManager.Theme()` rather than
 * eagerly in its builder.
 */
data class ThemeFont(
    val res: FontResource,
    val weight: ThemeFontWeight,
    val style: ThemeFontStyle
) {
    companion object {
        @Composable
        fun ThemeFont.toFont(): Font = org.jetbrains.compose.resources.Font(
            resource = res,
            weight = weight.toFontWeight(),
            style = style.toFontStyle()
        )
    }
}
