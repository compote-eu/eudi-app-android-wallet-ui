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

package eu.europa.ec.uilogic.component.preview

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import eu.europa.ec.resourceslogic.theme.ThemeManager

/**
 * Wraps a preview in the wallet's real theme, so previewed components look like the app rather than
 * like default Material.
 *
 * [darkTheme] exists because [ThemeModePreviews] can no longer force the preview's night mode the way
 * the Android-only annotation did — pass `true` from a second preview function to see a component's
 * dark rendering.
 */
@Composable
fun PreviewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    ThemeManager.instance.Theme(darkTheme = darkTheme) { content() }
}
