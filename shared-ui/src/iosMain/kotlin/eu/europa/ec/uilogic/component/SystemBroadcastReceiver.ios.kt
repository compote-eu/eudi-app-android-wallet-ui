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
 * No-op: iOS has no system-wide broadcast bus, so there is nothing to register for. This is the
 * intended end state, not a gap — the redirect this mechanism carries on Android reaches an iOS app
 * through URL handling in the app delegate, which is a different seam entirely.
 *
 * [onTrigger] can never fire here: `PlatformIntent` is uninhabited on iOS, so there is no value that
 * could be passed to it.
 */
@Composable
actual fun SystemBroadcastReceiver(
    intentFilters: List<String>,
    onTrigger: (intent: PlatformIntent?) -> Unit,
) {
    // Intentionally empty.
}
