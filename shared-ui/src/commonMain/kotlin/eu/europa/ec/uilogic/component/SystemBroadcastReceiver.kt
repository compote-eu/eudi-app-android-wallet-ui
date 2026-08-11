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
import eu.europa.ec.shared.platform.PlatformIntent

/**
 * Listens for platform broadcasts named by [intentFilters] for as long as it is composed, handing each
 * one to [onTrigger].
 *
 * **Android-only in substance, and unlike most seams here that is not a temporary state.** This exists
 * because the wallet catches the OpenID4VCI redirect as an Android broadcast; iOS has no system-wide
 * broadcast bus, and the equivalent hand-off arrives through the app delegate's URL handling instead.
 * So the iOS actual is a deliberate no-op rather than an unimplemented stub — a shared screen may
 * declare a broadcast action and simply never be triggered there.
 *
 * The intent is a [PlatformIntent], the existing platform-handle seam, which is an `actual typealias`
 * for `android.content.Intent` on Android and uninhabited on iOS.
 */
@Composable
expect fun SystemBroadcastReceiver(
    intentFilters: List<String>,
    onTrigger: (intent: PlatformIntent?) -> Unit,
)
