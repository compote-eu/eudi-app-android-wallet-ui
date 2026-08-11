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

package eu.europa.ec.resourceslogic.theme.sets

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import eu.europa.ec.resourceslogic.theme.templates.ThemeDimensTemplate
import eu.europa.ec.resourceslogic.theme.templates.ThemeTypographyTemplate

data class ThemeSet(
    val isInDarkMode: Boolean,
    val lightColors: ColorScheme,
    val darkColors: ColorScheme,
    /**
     * The typography as an **unresolved template**, not a `Typography`. compose-resources loads a
     * font from a `@Composable`, so the font faces can only be resolved inside composition —
     * `ThemeManager.Theme()` does that. Keeping the template here (rather than one font family
     * applied afterwards) preserves each text style's own font family exactly as before.
     */
    val typo: ThemeTypographyTemplate,
    val shapes: Shapes,
    val dimens: ThemeDimensTemplate
)