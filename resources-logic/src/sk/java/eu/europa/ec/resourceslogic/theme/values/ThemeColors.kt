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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import eu.europa.ec.resourceslogic.theme.ThemeManager
import eu.europa.ec.resourceslogic.theme.templates.ThemeColorsTemplate

private val isInDarkMode: Boolean
    get() {
        return ThemeManager.instance.set.isInDarkMode
    }

/**
 * `sk` flavor color palette — ID SK 3.1 (see wiki/SK_THEME.md).
 *
 * This is the `sk` source-set override of the default [ThemeColors]; it mirrors the default's
 * public API but with the ID SK values. Light values are the official ID SK tokens (MIT
 * `id-sk/idsk3-frontend`); dark values are derived (ID SK ships no dark mode) using Material 3
 * practice and validated for WCAG AA.
 */
class ThemeColors {
    companion object {
        private const val white: Long = 0xFFFFFFFF
        private const val black: Long = 0xFF000000

        // Light theme base colors palette (ID SK).
        private const val eudiw_theme_light_primary: Long = 0xFF126DFF
        private const val eudiw_theme_light_onPrimary: Long = white
        private const val eudiw_theme_light_primaryContainer: Long = 0xFFEFF5FE
        private const val eudiw_theme_light_onPrimaryContainer: Long = 0xFF072C66
        private const val eudiw_theme_light_secondary: Long = 0xFF45608E
        private const val eudiw_theme_light_onSecondary: Long = white
        private const val eudiw_theme_light_secondaryContainer: Long = 0xFFD6E3FF
        private const val eudiw_theme_light_onSecondaryContainer: Long = 0xFF001B3D
        private const val eudiw_theme_light_tertiary: Long = 0xFF4C2C92
        private const val eudiw_theme_light_onTertiary: Long = white
        private const val eudiw_theme_light_tertiaryContainer: Long = 0xFFE9DDFF
        private const val eudiw_theme_light_onTertiaryContainer: Long = 0xFF1B0060
        private const val eudiw_theme_light_error: Long = 0xFFC3112B
        private const val eudiw_theme_light_onError: Long = white
        private const val eudiw_theme_light_errorContainer: Long = 0xFFFBEEF0
        private const val eudiw_theme_light_onErrorContainer: Long = 0xFF4E0711
        private const val eudiw_theme_light_surface: Long = white
        private const val eudiw_theme_light_onSurface: Long = 0xFF1A1C1E
        private const val eudiw_theme_light_background: Long = eudiw_theme_light_surface
        private const val eudiw_theme_light_onBackground: Long = eudiw_theme_light_onSurface
        private const val eudiw_theme_light_surfaceVariant: Long = 0xFFEEF2F7
        private const val eudiw_theme_light_onSurfaceVariant: Long = 0xFF44474E
        private const val eudiw_theme_light_outline: Long = 0xFF74777C
        private const val eudiw_theme_light_outlineVariant: Long = 0xFFC3C6CD
        private const val eudiw_theme_light_scrim: Long = black
        private const val eudiw_theme_light_inverseSurface: Long = 0xFF2F3033
        private const val eudiw_theme_light_inverseOnSurface: Long = 0xFFF1F0F4
        private const val eudiw_theme_light_inversePrimary: Long = 0xFFA6C8FF
        private const val eudiw_theme_light_surfaceDim: Long = 0xFFDAD9E0
        private const val eudiw_theme_light_surfaceBright: Long = white
        private const val eudiw_theme_light_surfaceContainerLowest: Long = white
        private const val eudiw_theme_light_surfaceContainerLow: Long = 0xFFF7F8FB
        private const val eudiw_theme_light_surfaceContainer: Long = 0xFFF1F3F8
        private const val eudiw_theme_light_surfaceContainerHigh: Long = 0xFFECEEF3
        private const val eudiw_theme_light_surfaceContainerHighest: Long = 0xFFE6E8EF
        private const val eudiw_theme_light_surfaceTint: Long = eudiw_theme_light_surface

        // Light theme fixed accent roles (identical in dark as well).
        private const val eudiw_theme_light_primaryFixed: Long = 0xFFD6E3FF
        private const val eudiw_theme_light_primaryFixedDim: Long = 0xFFA6C8FF
        private const val eudiw_theme_light_onPrimaryFixed: Long = 0xFF001A41
        private const val eudiw_theme_light_onPrimaryFixedVariant: Long = 0xFF00458E

        private const val eudiw_theme_light_secondaryFixed: Long = 0xFFD6E3FF
        private const val eudiw_theme_light_secondaryFixedDim: Long = 0xFFBAC7DB
        private const val eudiw_theme_light_onSecondaryFixed: Long = 0xFF001B3D
        private const val eudiw_theme_light_onSecondaryFixedVariant: Long = 0xFF2D4665

        private const val eudiw_theme_light_tertiaryFixed: Long = 0xFFE9DDFF
        private const val eudiw_theme_light_tertiaryFixedDim: Long = 0xFFD0BCFF
        private const val eudiw_theme_light_onTertiaryFixed: Long = 0xFF1B0060
        private const val eudiw_theme_light_onTertiaryFixedVariant: Long = 0xFF4F378B

        // Light theme extra colors palette (ID SK).
        internal const val eudiw_theme_light_success: Long = 0xFF078814
        internal const val eudiw_theme_light_warning: Long = 0xFFBD730C
        internal const val eudiw_theme_light_pending: Long = 0xFFD96E00
        internal const val eudiw_theme_light_divider: Long = 0xFFE0E0E0

        // Dark theme base colors palette (derived, WCAG-AA validated).
        private const val eudiw_theme_dark_primary: Long = 0xFFA6C8FF
        private const val eudiw_theme_dark_onPrimary: Long = 0xFF00315C
        private const val eudiw_theme_dark_primaryContainer: Long = 0xFF1A468C
        private const val eudiw_theme_dark_onPrimaryContainer: Long = 0xFFD5E3FF
        private const val eudiw_theme_dark_secondary: Long = 0xFFBAC7DB
        private const val eudiw_theme_dark_onSecondary: Long = 0xFF243141
        private const val eudiw_theme_dark_secondaryContainer: Long = 0xFF3A4758
        private const val eudiw_theme_dark_onSecondaryContainer: Long = 0xFFD6E3FF
        private const val eudiw_theme_dark_tertiary: Long = 0xFFD0BCFF
        private const val eudiw_theme_dark_onTertiary: Long = 0xFF381E72
        private const val eudiw_theme_dark_tertiaryContainer: Long = 0xFF4F378B
        private const val eudiw_theme_dark_onTertiaryContainer: Long = 0xFFEADDFF
        private const val eudiw_theme_dark_error: Long = 0xFFFFB4AB
        private const val eudiw_theme_dark_onError: Long = 0xFF690005
        private const val eudiw_theme_dark_errorContainer: Long = 0xFF93000A
        private const val eudiw_theme_dark_onErrorContainer: Long = 0xFFFFDAD6
        private const val eudiw_theme_dark_surface: Long = 0xFF131316
        private const val eudiw_theme_dark_onSurface: Long = 0xFFE5E2E6
        private const val eudiw_theme_dark_background: Long = eudiw_theme_dark_surface
        private const val eudiw_theme_dark_onBackground: Long = eudiw_theme_dark_onSurface
        private const val eudiw_theme_dark_surfaceVariant: Long = 0xFF44474E
        private const val eudiw_theme_dark_onSurfaceVariant: Long = 0xFFC6C6D0
        private const val eudiw_theme_dark_outline: Long = 0xFF909099
        private const val eudiw_theme_dark_outlineVariant: Long = 0xFF44474E
        private const val eudiw_theme_dark_scrim: Long = black
        private const val eudiw_theme_dark_inverseSurface: Long = 0xFFE5E2E6
        private const val eudiw_theme_dark_inverseOnSurface: Long = 0xFF303034
        private const val eudiw_theme_dark_inversePrimary: Long = 0xFF126DFF
        private const val eudiw_theme_dark_surfaceDim: Long = 0xFF131316
        private const val eudiw_theme_dark_surfaceBright: Long = 0xFF39393E
        private const val eudiw_theme_dark_surfaceContainerLowest: Long = 0xFF0E0E11
        private const val eudiw_theme_dark_surfaceContainerLow: Long = 0xFF1B1B1F
        private const val eudiw_theme_dark_surfaceContainer: Long = 0xFF1F1F23
        private const val eudiw_theme_dark_surfaceContainerHigh: Long = 0xFF2A2A2E
        private const val eudiw_theme_dark_surfaceContainerHighest: Long = 0xFF343539
        private const val eudiw_theme_dark_surfaceTint: Long = eudiw_theme_dark_surface

        // Dark theme fixed accent roles (same values as light).
        private const val eudiw_theme_dark_primaryFixed: Long = 0xFFD6E3FF
        private const val eudiw_theme_dark_primaryFixedDim: Long = 0xFFA6C8FF
        private const val eudiw_theme_dark_onPrimaryFixed: Long = 0xFF001A41
        private const val eudiw_theme_dark_onPrimaryFixedVariant: Long = 0xFF00458E

        private const val eudiw_theme_dark_secondaryFixed: Long = 0xFFD6E3FF
        private const val eudiw_theme_dark_secondaryFixedDim: Long = 0xFFBAC7DB
        private const val eudiw_theme_dark_onSecondaryFixed: Long = 0xFF001B3D
        private const val eudiw_theme_dark_onSecondaryFixedVariant: Long = 0xFF2D4665

        private const val eudiw_theme_dark_tertiaryFixed: Long = 0xFFE9DDFF
        private const val eudiw_theme_dark_tertiaryFixedDim: Long = 0xFFD0BCFF
        private const val eudiw_theme_dark_onTertiaryFixed: Long = 0xFF1B0060
        private const val eudiw_theme_dark_onTertiaryFixedVariant: Long = 0xFF4F378B

        // Dark theme extra colors palette (derived).
        internal const val eudiw_theme_dark_success: Long = 0xFF7FD98A
        internal const val eudiw_theme_dark_warning: Long = 0xFFF5BE6B
        internal const val eudiw_theme_dark_pending: Long = 0xFFFFB871
        internal const val eudiw_theme_dark_divider: Long = 0xFF44474E

        const val eudiw_theme_light_background_preview: Long = eudiw_theme_light_surface
        const val eudiw_theme_dark_background_preview: Long = eudiw_theme_dark_surface

        internal val lightColors = ThemeColorsTemplate(
            primary = eudiw_theme_light_primary,
            onPrimary = eudiw_theme_light_onPrimary,
            primaryContainer = eudiw_theme_light_primaryContainer,
            onPrimaryContainer = eudiw_theme_light_onPrimaryContainer,
            secondary = eudiw_theme_light_secondary,
            onSecondary = eudiw_theme_light_onSecondary,
            secondaryContainer = eudiw_theme_light_secondaryContainer,
            onSecondaryContainer = eudiw_theme_light_onSecondaryContainer,
            tertiary = eudiw_theme_light_tertiary,
            onTertiary = eudiw_theme_light_onTertiary,
            tertiaryContainer = eudiw_theme_light_tertiaryContainer,
            onTertiaryContainer = eudiw_theme_light_onTertiaryContainer,
            error = eudiw_theme_light_error,
            errorContainer = eudiw_theme_light_errorContainer,
            onError = eudiw_theme_light_onError,
            onErrorContainer = eudiw_theme_light_onErrorContainer,
            background = eudiw_theme_light_background,
            onBackground = eudiw_theme_light_onBackground,
            surface = eudiw_theme_light_surface,
            onSurface = eudiw_theme_light_onSurface,
            surfaceVariant = eudiw_theme_light_surfaceVariant,
            onSurfaceVariant = eudiw_theme_light_onSurfaceVariant,
            outline = eudiw_theme_light_outline,
            inverseOnSurface = eudiw_theme_light_inverseOnSurface,
            inverseSurface = eudiw_theme_light_inverseSurface,
            inversePrimary = eudiw_theme_light_inversePrimary,
            surfaceTint = eudiw_theme_light_surfaceTint,
            outlineVariant = eudiw_theme_light_outlineVariant,
            scrim = eudiw_theme_light_scrim,
            surfaceBright = eudiw_theme_light_surfaceBright,
            surfaceDim = eudiw_theme_light_surfaceDim,
            surfaceContainer = eudiw_theme_light_surfaceContainer,
            surfaceContainerHigh = eudiw_theme_light_surfaceContainerHigh,
            surfaceContainerHighest = eudiw_theme_light_surfaceContainerHighest,
            surfaceContainerLow = eudiw_theme_light_surfaceContainerLow,
            surfaceContainerLowest = eudiw_theme_light_surfaceContainerLowest,
            primaryFixed = eudiw_theme_light_primaryFixed,
            primaryFixedDim = eudiw_theme_light_primaryFixedDim,
            onPrimaryFixed = eudiw_theme_light_onPrimaryFixed,
            onPrimaryFixedVariant = eudiw_theme_light_onPrimaryFixedVariant,
            secondaryFixed = eudiw_theme_light_secondaryFixed,
            secondaryFixedDim = eudiw_theme_light_secondaryFixedDim,
            onSecondaryFixed = eudiw_theme_light_onSecondaryFixed,
            onSecondaryFixedVariant = eudiw_theme_light_onSecondaryFixedVariant,
            tertiaryFixed = eudiw_theme_light_tertiaryFixed,
            tertiaryFixedDim = eudiw_theme_light_tertiaryFixedDim,
            onTertiaryFixed = eudiw_theme_light_onTertiaryFixed,
            onTertiaryFixedVariant = eudiw_theme_light_onTertiaryFixedVariant,
        )

        internal val darkColors = ThemeColorsTemplate(
            primary = eudiw_theme_dark_primary,
            onPrimary = eudiw_theme_dark_onPrimary,
            primaryContainer = eudiw_theme_dark_primaryContainer,
            onPrimaryContainer = eudiw_theme_dark_onPrimaryContainer,
            secondary = eudiw_theme_dark_secondary,
            onSecondary = eudiw_theme_dark_onSecondary,
            secondaryContainer = eudiw_theme_dark_secondaryContainer,
            onSecondaryContainer = eudiw_theme_dark_onSecondaryContainer,
            tertiary = eudiw_theme_dark_tertiary,
            onTertiary = eudiw_theme_dark_onTertiary,
            tertiaryContainer = eudiw_theme_dark_tertiaryContainer,
            onTertiaryContainer = eudiw_theme_dark_onTertiaryContainer,
            error = eudiw_theme_dark_error,
            errorContainer = eudiw_theme_dark_errorContainer,
            onError = eudiw_theme_dark_onError,
            onErrorContainer = eudiw_theme_dark_onErrorContainer,
            background = eudiw_theme_dark_background,
            onBackground = eudiw_theme_dark_onBackground,
            surface = eudiw_theme_dark_surface,
            onSurface = eudiw_theme_dark_onSurface,
            surfaceVariant = eudiw_theme_dark_surfaceVariant,
            onSurfaceVariant = eudiw_theme_dark_onSurfaceVariant,
            outline = eudiw_theme_dark_outline,
            inverseOnSurface = eudiw_theme_dark_inverseOnSurface,
            inverseSurface = eudiw_theme_dark_inverseSurface,
            inversePrimary = eudiw_theme_dark_inversePrimary,
            surfaceTint = eudiw_theme_dark_surfaceTint,
            outlineVariant = eudiw_theme_dark_outlineVariant,
            scrim = eudiw_theme_dark_scrim,
            surfaceBright = eudiw_theme_dark_surfaceBright,
            surfaceDim = eudiw_theme_dark_surfaceDim,
            surfaceContainer = eudiw_theme_dark_surfaceContainer,
            surfaceContainerHigh = eudiw_theme_dark_surfaceContainerHigh,
            surfaceContainerHighest = eudiw_theme_dark_surfaceContainerHighest,
            surfaceContainerLow = eudiw_theme_dark_surfaceContainerLow,
            surfaceContainerLowest = eudiw_theme_dark_surfaceContainerLowest,
            primaryFixed = eudiw_theme_dark_primaryFixed,
            primaryFixedDim = eudiw_theme_dark_primaryFixedDim,
            onPrimaryFixed = eudiw_theme_dark_onPrimaryFixed,
            onPrimaryFixedVariant = eudiw_theme_dark_onPrimaryFixedVariant,
            secondaryFixed = eudiw_theme_dark_secondaryFixed,
            secondaryFixedDim = eudiw_theme_dark_secondaryFixedDim,
            onSecondaryFixed = eudiw_theme_dark_onSecondaryFixed,
            onSecondaryFixedVariant = eudiw_theme_dark_onSecondaryFixedVariant,
            tertiaryFixed = eudiw_theme_dark_tertiaryFixed,
            tertiaryFixedDim = eudiw_theme_dark_tertiaryFixedDim,
            onTertiaryFixed = eudiw_theme_dark_onTertiaryFixed,
            onTertiaryFixedVariant = eudiw_theme_dark_onTertiaryFixedVariant,
        )

        val primary: Color
            get() = if (isInDarkMode) Color(eudiw_theme_dark_primary) else Color(eudiw_theme_light_primary)

        val success: Color
            get() = if (isInDarkMode) Color(eudiw_theme_dark_success) else Color(eudiw_theme_light_success)

        val warning: Color
            get() = if (isInDarkMode) Color(eudiw_theme_dark_warning) else Color(eudiw_theme_light_warning)

        val pending: Color
            get() = if (isInDarkMode) Color(eudiw_theme_dark_pending) else Color(eudiw_theme_light_pending)

        val error: Color
            get() = if (isInDarkMode) Color(eudiw_theme_dark_error) else Color(eudiw_theme_light_error)

        val divider: Color
            get() = if (isInDarkMode) Color(eudiw_theme_dark_divider) else Color(eudiw_theme_light_divider)
    }
}

val ColorScheme.success: Color
    @Composable get() = if (isSystemInDarkTheme()) {
        Color(ThemeColors.eudiw_theme_dark_success)
    } else {
        Color(ThemeColors.eudiw_theme_light_success)
    }

val ColorScheme.warning: Color
    @Composable get() = if (isSystemInDarkTheme()) {
        Color(ThemeColors.eudiw_theme_dark_warning)
    } else {
        Color(ThemeColors.eudiw_theme_light_warning)
    }

val ColorScheme.pending: Color
    @Composable get() = if (isSystemInDarkTheme()) {
        Color(ThemeColors.eudiw_theme_dark_pending)
    } else {
        Color(ThemeColors.eudiw_theme_light_pending)
    }

val ColorScheme.divider: Color
    @Composable get() = if (isSystemInDarkTheme()) {
        Color(ThemeColors.eudiw_theme_dark_divider)
    } else {
        Color(ThemeColors.eudiw_theme_light_divider)
    }
