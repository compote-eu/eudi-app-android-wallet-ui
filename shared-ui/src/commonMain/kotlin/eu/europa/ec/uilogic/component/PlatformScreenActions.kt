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
 * Things a shared screen sometimes asks the *host application* to do, which have no Compose
 * equivalent: leave the app, send the user into system settings, or hand something to another app.
 *
 * These were `Context` extensions on Android. Collected behind one interface rather than five
 * separate seams because they share a single reason for existing — they are all "hand this to the OS"
 * — and because a screen that needs one usually needs more than one.
 *
 * On iOS the first three are deliberately inert and only [openUrlExternally] does real work; see the
 * iOS actual for why none of the inert ones is a stub waiting to be filled in.
 */
interface PlatformScreenActions {

    /** Leaves the app, as Android's back-from-root does. */
    fun finishApp()

    /** Opens this app's entry in the system settings, so the user can grant a permission. */
    fun openAppSettings()

    /** Opens the system's Bluetooth settings, so the user can switch the radio on. */
    fun openBluetoothSettings()

    /**
     * Hands [url] to whatever the platform uses to open links — the browser, or another app that
     * claims it. Silent if nothing can.
     *
     * Takes a `String` rather than a parsed URL because there is no shared URL type: Android's `Uri`
     * and iOS's `NSURL` are both platform types, and every caller starts from a string anyway.
     */
    fun openUrlExternally(url: String)

    /**
     * Offers [intent] to the user's choice of app, under [title] — Android's share sheet.
     *
     * The intent is built by whichever platform code owns the payload (the settings screen's log
     * files, for instance) and is opaque here, so this seam only *presents* it. On iOS a
     * [PlatformIntent] cannot be constructed at all, which makes this statically unreachable there
     * rather than unimplemented.
     */
    fun shareViaChooser(intent: PlatformIntent, title: String?)
}

/** The host's [PlatformScreenActions], resolved from the current platform. */
@Composable
expect fun rememberPlatformScreenActions(): PlatformScreenActions
