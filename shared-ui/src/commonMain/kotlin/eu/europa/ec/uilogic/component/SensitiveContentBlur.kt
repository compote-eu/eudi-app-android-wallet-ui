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

package eu.europa.ec.uilogic.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Blurs content that is being hidden as sensitive — an unrevealed claim value in a document.
 *
 * Per-platform purely because of Android's history: Compose's own `Modifier.blur` needs API 31+, so
 * below that the app falls back to the `cloudy` library, which is Android-only. iOS renders through
 * Skia and has `Modifier.blur` on every version the app supports, so there the seam is one call.
 *
 * `@Composable` because the Android fallback (`cloudy`) is a composable modifier, even though
 * `Modifier.blur` on its own is not.
 */
@Composable
expect fun Modifier.sensitiveContentBlur(): Modifier
