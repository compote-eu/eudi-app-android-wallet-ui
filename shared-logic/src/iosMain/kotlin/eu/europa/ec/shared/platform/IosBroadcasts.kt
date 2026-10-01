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

package eu.europa.ec.shared.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter

/**
 * The in-process bus `SystemBroadcastReceiver` listens to on iOS — Android's broadcasts, for one app.
 *
 * Shared screens react to broadcast actions (`CoreActions`), which on Android the activity and the
 * background workers send. Without a bus here every one of those listeners would be dead on iOS, however
 * the payload itself reached the app.
 *
 * Like a broadcast it reaches only the receivers registered when it is sent: no replay, so a screen
 * composed later never acts on an old one. Safe to send from any thread.
 */
object IosBroadcasts {

    private val intents = MutableSharedFlow<PlatformIntent>(extraBufferCapacity = BUFFER)

    /** @return false only if the buffer was full, which a handful of UI events never fills. */
    fun send(intent: PlatformIntent): Boolean = intents.tryEmit(intent)

    /** The broadcasts whose action is one of [actions], as `IntentFilter.addAction` selects them. */
    fun receive(actions: List<String>): Flow<PlatformIntent> =
        intents.filter { it.platformAction() in actions }

    private const val BUFFER = 16
}
