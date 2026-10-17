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


package eu.europa.ec.ux.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import eu.europa.ec.ux.theme.generated.UxBrandConfig

/** The active tokens. Defaults to the exported default brand so an element never crashes outside a [UxTheme]. */
val LocalUxTokens = staticCompositionLocalOf { UxBrandConfig.defaultBrand.light.tokens }

/** The active brand's `styles` and `layouts` selections. */
val LocalUxBrand = staticCompositionLocalOf { UxBrandConfig.defaultBrand }

/**
 * Applies one brand in one mode, with the export's data files ([resources]: assets, library asset rules, `comp.*`
 * table; M6c). Never touches the wallet's `ThemeManager`.
 */
@Composable
fun UxTheme(
    brand: UxBrand = UxBrandConfig.defaultBrand,
    mode: UxMode = UxMode.Light,
    resources: UxSpecResources = LocalUxSpecResources.current,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalUxBrand provides brand,
        LocalUxMode provides mode,
        LocalUxTokens provides brand.tokens(mode),
        LocalUxSpecResources provides resources,
        content = content,
    )
}

/** Read access to the active theme from an element. */
object UxThemeAccess {
    val tokens: UxTokens
        @Composable @ReadOnlyComposable
        get() = LocalUxTokens.current

    val brand: UxBrand
        @Composable @ReadOnlyComposable
        get() = LocalUxBrand.current

    val resources: UxSpecResources
        @Composable @ReadOnlyComposable
        get() = LocalUxSpecResources.current

    /** A `comp.*` dimension of the active brand and mode from `ux-tokens.json`, or [fallback] (a shell value). */
    @Composable @ReadOnlyComposable
    fun compDp(key: String, fallback: Dp): Dp =
        LocalUxSpecResources.current.componentTokens.dp(LocalUxBrand.current.id, LocalUxMode.current, key) ?: fallback

    /** A `comp.*` colour of the active brand and mode from `ux-tokens.json`, or [fallback] (a `sys.*` role). */
    @Composable @ReadOnlyComposable
    fun compColor(key: String, fallback: Color): Color =
        LocalUxSpecResources.current.componentTokens.color(LocalUxBrand.current.id, LocalUxMode.current, key) ?: fallback

    /** A `comp.*` font size or line height (sp) from `ux-tokens.json`, or [fallback]. */
    @Composable @ReadOnlyComposable
    fun compSp(key: String, fallback: TextUnit): TextUnit =
        LocalUxSpecResources.current.componentTokens.sp(LocalUxBrand.current.id, LocalUxMode.current, key) ?: fallback

    /** A `comp.*` font weight from `ux-tokens.json`, or [fallback]. */
    @Composable @ReadOnlyComposable
    fun compWeight(key: String, fallback: FontWeight): FontWeight =
        LocalUxSpecResources.current.componentTokens.weight(LocalUxBrand.current.id, LocalUxMode.current, key) ?: fallback

    /** A `comp.*` shadow from `ux-tokens.json`, or [fallback]. */
    @Composable @ReadOnlyComposable
    fun compShadow(key: String, fallback: UxShadow = UxShadow.None): UxShadow =
        LocalUxSpecResources.current.componentTokens.shadow(LocalUxBrand.current.id, LocalUxMode.current, key) ?: fallback

    /** The active brand's library selection [key] (`EL-status-chip.mark`, `card-face`, ...), or null. */
    @Composable @ReadOnlyComposable
    fun selection(key: String): String? = LocalUxSpecResources.current.selections.of(LocalUxBrand.current.id, key)
}
