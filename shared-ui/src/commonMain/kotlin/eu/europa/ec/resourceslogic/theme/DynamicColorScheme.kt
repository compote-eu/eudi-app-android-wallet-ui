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

package eu.europa.ec.resourceslogic.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Whether this platform can derive a colour scheme from the user's system settings — Material You.
 * Android 12+ only; there is no iOS equivalent.
 *
 * The one platform-specific corner of the theme. Everything else (colours, shapes, typography,
 * dimensions) is plain Compose and lives in commonMain.
 */
internal expect val platformSupportsDynamicTheming: Boolean

/**
 * The platform's dynamic colour scheme for [darkTheme], or null when the platform has none — in
 * which case the caller falls back to the wallet's own palette, which is the normal path and the
 * only path on iOS.
 */
@Composable
internal expect fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme?
