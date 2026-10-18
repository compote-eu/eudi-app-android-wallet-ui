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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import eu.europa.ec.shared.platform.IosBroadcasts
import eu.europa.ec.shared.platform.PlatformIntent

/**
 * Listens on [IosBroadcasts], the in-process bus iOS has instead of a system-wide one, for as long as it
 * is composed — the same lifetime the Android actual gives its registered receiver.
 *
 * So a broadcast reaches a screen only while it is composed, which under `NavDisplay` means while it is
 * the top entry.
 */
@Composable
actual fun SystemBroadcastReceiver(
    intentFilters: List<String>,
    onTrigger: (intent: PlatformIntent?) -> Unit,
) {
    val trigger by rememberUpdatedState(onTrigger)

    LaunchedEffect(intentFilters) {
        IosBroadcasts.receive(intentFilters).collect { trigger(it) }
    }
}
