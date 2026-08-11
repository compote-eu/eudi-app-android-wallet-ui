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

/**
 * Things a shared screen sometimes asks the *host application* to do, which have no Compose
 * equivalent: leave the app, or send the user into system settings.
 *
 * These were `Context` extensions on Android. Collected behind one interface rather than three
 * separate seams because they share a single reason for existing — they are all "hand this to the OS"
 * — and because a screen that needs one usually needs more than one.
 *
 * On iOS all three are deliberately inert; see the iOS actual for why none of them is a stub waiting
 * to be filled in.
 */
interface PlatformScreenActions {

    /** Leaves the app, as Android's back-from-root does. */
    fun finishApp()

    /** Opens this app's entry in the system settings, so the user can grant a permission. */
    fun openAppSettings()

    /** Opens the system's Bluetooth settings, so the user can switch the radio on. */
    fun openBluetoothSettings()
}

/** The host's [PlatformScreenActions], resolved from the current platform. */
@Composable
expect fun rememberPlatformScreenActions(): PlatformScreenActions
