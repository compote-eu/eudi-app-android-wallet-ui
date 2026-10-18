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

/**
 * Marks a component preview. Applied to ~35 component preview functions, all of which now live in
 * commonMain, so this is Compose Multiplatform's `@Preview` rather than Android's.
 *
 * **The name is now slightly historical, and deliberately kept.** The Android annotation this
 * replaces declared *two* `@Preview`s — one forcing `UI_MODE_NIGHT_NO`, one `UI_MODE_NIGHT_YES` —
 * which is how every component used to render as a light/dark pair. Compose Multiplatform's
 * `@Preview` has neither `uiMode` nor `backgroundColor`, so that pairing is not expressible here and
 * a component now previews once, in light. The name stays so the ~35 existing usages keep compiling
 * unchanged; to preview dark, write a second preview function that passes `darkTheme = true` to
 * [PreviewTheme].
 */
@Preview
annotation class ThemeModePreviews
