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

package eu.europa.ec.uilogic.component.wrap

import kotlinx.serialization.Serializable

/**
 * KMP-clean, serializable stand-ins for Compose UI types carried by [TextConfig] and other UI
 * models (the same pattern as [TextStyleKey]). The destination resolves each key to its concrete
 * Compose value at render time via the `@Composable`/plain resolvers in :ui-logic
 * (`ColorKey.toColor()`, `TextAlignKey.toTextAlign()`, `TextOverflowKey.toTextOverflow()`), so the
 * config can round-trip through a navigation argument and live in commonMain.
 *
 * Add a case here + a branch in the corresponding resolver; the compiler enforces exhaustiveness.
 */
@Serializable
enum class ColorKey {
    OnSurface,
    OnSurfaceVariant,
    Success,
    Pending,
    Primary,
}

@Serializable
enum class TextAlignKey {
    Start,
    Center,
    End,
}

@Serializable
enum class TextOverflowKey {
    Clip,
    Ellipsis,
    Visible,
}
