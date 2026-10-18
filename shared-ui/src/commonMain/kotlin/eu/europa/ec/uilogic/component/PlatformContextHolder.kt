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
import eu.europa.ec.shared.platform.PlatformContext

/**
 * The platform handle a shared screen needs when a view-model event carries one — re-issuance threads it
 * down to wallet-core, which wants a real Android `Context`.
 *
 * **Nullable on purpose, and that nullability is the point.** `PlatformContext` is deliberately
 * *uninhabited* on iOS: it has no constructor there, so it is impossible to fabricate one, and that in
 * turn makes it statically provable that any code path needing one is Android-only. Returning null here
 * preserves that guarantee instead of weakening the type to make a screen compile. A screen simply
 * skips the action when it is null — which is honest, since the actions that need it (re-issuance) do
 * not exist on iOS anyway.
 */
@Composable
expect fun rememberPlatformContextOrNull(): PlatformContext?
