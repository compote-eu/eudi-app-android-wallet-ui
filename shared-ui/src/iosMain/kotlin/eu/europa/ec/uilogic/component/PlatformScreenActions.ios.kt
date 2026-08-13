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
import androidx.compose.runtime.remember
import eu.europa.ec.shared.platform.PlatformIntent
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * All but one of the actions are inert on iOS, and each for its own reason rather than because the
 * work is outstanding:
 *
 * - **finishApp** — an iOS app does not exit itself. Apple's HIG treats programmatic termination as a
 *   crash from the user's point of view, and `exit()` is grounds for App Store rejection. The user
 *   leaves via the home gesture.
 * - **openAppSettings** — reachable in principle via `UIApplicationOpenSettingsURLString`, but only
 *   meaningfully once iOS actually asks for a permission; the wallet has no permission prompts there
 *   yet.
 * - **openBluetoothSettings** — iOS has no public URL for the Bluetooth settings pane. The system
 *   offers to enable Bluetooth itself when CoreBluetooth is first used, which is the platform's answer
 *   to this.
 *
 * Each of those logs, so an unexpected call during development is visible rather than silent.
 *
 * **openUrlExternally is real**, and the only one of the four that is: opening a link is something iOS
 * does support, through `UIApplication.openURL`. So a shared screen's "open this in the browser" branch
 * behaves the same on both platforms.
 */
@Composable
actual fun rememberPlatformScreenActions(): PlatformScreenActions = remember {
    object : PlatformScreenActions {
        override fun finishApp() = log("finishApp")
        override fun openAppSettings() = log("openAppSettings")
        override fun openBluetoothSettings() = log("openBluetoothSettings")

        override fun openUrlExternally(url: String) {
            // `NSURL.URLWithString` returns null for anything it cannot parse, which is the iOS
            // counterpart of the Android side swallowing `ActivityNotFoundException`.
            val nsUrl = NSURL.URLWithString(url)
            if (nsUrl == null) {
                println("$TAG: openUrlExternally could not parse '$url' — ignoring.")
                return
            }
            UIApplication.sharedApplication.openURL(
                url = nsUrl,
                options = emptyMap<Any?, Any>(),
                completionHandler = null,
            )
        }

        /**
         * Unreachable rather than unimplemented: [PlatformIntent] has no iOS constructor, so no value
         * can ever be passed in. If iOS ever needs a share sheet it will be
         * `UIActivityViewController` over a payload type of its own, not this.
         */
        override fun shareViaChooser(intent: PlatformIntent, title: String?) =
            log("shareViaChooser")

        private fun log(action: String) =
            println("$TAG: $action requested, which iOS does not support — ignoring.")
    }
}

private const val TAG = "PlatformScreenActions"
