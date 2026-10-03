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

import androidx.compose.ui.tooling.preview.Preview
import eu.europa.ec.resourceslogic.theme.values.ThemeColors

// Android's Configuration constants, written out: they are android.content.res values and this is
// commonMain. UI_MODE_NIGHT_NO = 0x10, UI_MODE_NIGHT_YES = 0x20, UI_MODE_TYPE_NORMAL = 0x01.
private const val UI_MODE_LIGHT_NORMAL = 0x10 or 0x01
private const val UI_MODE_DARK_NORMAL = 0x20 or 0x01

/**
 * Creates previews for large text on a compact screen in Light and Dark mode.
 */
@Preview(
    name = "Large Text - Light Mode",
    group = "Large text",
    showBackground = true,
    widthDp = 320,
    heightDp = 640,
    fontScale = 1.6f,
    uiMode = UI_MODE_LIGHT_NORMAL,
    backgroundColor = ThemeColors.eudiw_theme_light_background_preview
)
@Preview(
    name = "Large Text - Dark Mode",
    group = "Large text",
    showBackground = true,
    widthDp = 320,
    heightDp = 640,
    fontScale = 1.6f,
    uiMode = UI_MODE_DARK_NORMAL,
    backgroundColor = ThemeColors.eudiw_theme_dark_background_preview
)
annotation class LargeTextPreviews
