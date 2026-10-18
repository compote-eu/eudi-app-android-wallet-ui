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

// RESKIN PROBE (option B): the adapter between the generated design system and the app's own screens.
package eu.europa.ec.uilogic.component.ux

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import eu.europa.ec.ux.contract.resources.allDrawableResources
import eu.europa.ec.ux.theme.UxAssets
import eu.europa.ec.ux.theme.UxBrandSelections
import eu.europa.ec.ux.theme.UxComponentTokenTable
import eu.europa.ec.ux.theme.UxLibraryAssets
import eu.europa.ec.ux.theme.UxMode
import eu.europa.ec.ux.theme.UxSpecResources
import eu.europa.ec.ux.theme.UxTheme
import eu.europa.ec.ux.theme.UxThemeAccess
import eu.europa.ec.ux.theme.generated.UxBrandConfig
import org.jetbrains.compose.resources.painterResource
import eu.europa.ec.ux.contract.resources.Res as ContractRes

/**
 * Applies the export's DEFAULT brand (`UxBrandConfig.defaultBrand`, so a `ux-spec/` sync that switches the brand
 * reskins the screen with no Kotlin edit) with the export's data files, then bridges the brand's `sys.color.*` roles
 * into the app's Material colour scheme, so the app components a screen still draws (sheets, loading indicator,
 * Material checkboxes inside kept components) follow the brand too. The wallet's own `ThemeManager` is not touched.
 *
 * Dark mode: the export has no dark modes yet, so [UxMode.Dark] falls back to light (the resolver's rule).
 */
@Composable
fun UxAppTheme(content: @Composable () -> Unit) {
    val resources by produceState(UxSpecResources.NONE) { value = loadExportedSpecResources() }
    UxTheme(
        brand = UxBrandConfig.defaultBrand,
        mode = if (isSystemInDarkTheme()) UxMode.Dark else UxMode.Light,
        resources = resources,
    ) {
        UxMaterialBridge(content)
    }
}

/** The brand's colour roles as the Material scheme of the subtree (the app's typography and shapes stay). */
@Composable
private fun UxMaterialBridge(content: @Composable () -> Unit) {
    val c = UxThemeAccess.tokens.colors
    val scheme = MaterialTheme.colorScheme.copy(
        primary = c.actionPrimaryBg,
        onPrimary = c.actionPrimaryFg,
        secondary = c.actionSecondaryFg,
        background = c.surfacePage,
        onBackground = c.textPrimary,
        surface = c.surfaceDefault,
        onSurface = c.textPrimary,
        onSurfaceVariant = c.textSecondary,
        surfaceDim = c.surfaceSubtle,
        surfaceContainerLowest = c.surfaceRaised,
        surfaceContainerLow = c.surfaceDefault,
        surfaceContainer = c.surfaceDefault,
        surfaceContainerHigh = c.surfaceRaised,
        outline = c.borderDefault,
        outlineVariant = c.borderDefault,
        error = c.feedbackError.fg,
    )
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

/** The export's `ux-assets.json`, `ux-library.json` and `ux-tokens.json` and its drawables, read from `:ux-contract`. */
private suspend fun loadExportedSpecResources(): UxSpecResources {
    suspend fun file(name: String) = ContractRes.readBytes("files/$name").decodeToString()
    val drawables = ContractRes.allDrawableResources
    return UxSpecResources(
        assets = UxAssets.parse(file("ux-assets.json")),
        library = UxLibraryAssets.parse(file("ux-library.json")),
        componentTokens = UxComponentTokenTable.parse(file("ux-tokens.json")),
        selections = UxBrandSelections.parse(file("ux-library.json")),
        painter = { resource -> drawables[resource]?.let { painterResource(it) } },
    )
}
