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
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/**
 * Three of the actions are real on iOS — opening a URL, opening app settings, and sharing files — and
 * the inert ones are inert for their own reasons rather than because the work is outstanding:
 *
 * - **finishApp** — an iOS app does not exit itself. Apple's HIG treats programmatic termination as a
 *   crash from the user's point of view, and `exit()` is grounds for App Store rejection. The user
 *   leaves via the home gesture.
 * - **openBluetoothSettings** — iOS has no public URL for the Bluetooth settings pane. The system
 *   offers to enable Bluetooth itself when CoreBluetooth is first used, which is the platform's answer
 *   to this.
 *
 * Each of those logs, so an unexpected call during development is visible rather than silent.
 *
 * **openUrlExternally and openAppSettings are real.** Opening a link is something iOS supports through
 * `UIApplication.openURL`, so a shared screen's "open this in the browser" branch behaves the same on
 * both platforms. `openAppSettings` was inert until recently for a stated reason — it is only
 * meaningful once iOS actually asks for a permission — and the QR scanner's camera is the first thing
 * that does, so the reason expired.
 */
@Composable
actual fun rememberPlatformScreenActions(): PlatformScreenActions = remember {
    object : PlatformScreenActions {
        override fun finishApp() = log("finishApp")
        // Deep-linking further than the app's own pane is not possible, and this is the pane that
        // holds the camera switch a denied scanner needs the user to flip. Shared with the biometric
        // callers, which want the same pane for a different reason — see [openIosAppSettings].
        override fun openAppSettings() = openIosAppSettings()
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
         * A real share sheet, over `UIActivityViewController`.
         *
         * The official iOS wallet does this declaratively — its settings row *is* a SwiftUI
         * `ShareLink(item: fileUrl)`. A Compose screen has no `ShareLink`, so the equivalent is to
         * present the same controller UIKit gives `ShareLink` underneath. No Swift seam is needed:
         * `UIActivityViewController` is Objective-C, so Kotlin/Native reaches it directly — unlike the
         * document-signing bridge, where the library was Swift-only.
         *
         * [title] is deliberately ignored: Android names its chooser, and iOS's activity sheet has no
         * equivalent — it labels itself from the items being shared. Kept on the contract because
         * Android needs it.
         */
        override fun shareFiles(paths: List<String>, title: String?) {
            val urls = paths.mapNotNull { NSURL.fileURLWithPath(it) }
            if (urls.isEmpty()) return

            val presenter = topmostViewController() ?: run {
                log("shareFiles (no view controller to present from)")
                return
            }

            val controller = UIActivityViewController(
                activityItems = urls,
                applicationActivities = null,
            )
            // iPad presents this as a popover and raises rather than guessing an anchor, so give it
            // one. Harmless on iPhone, where the sheet is modal and the popover controller is null.
            // `popoverPresentationController` lives on the UIKit category, hence the explicit import.
            controller.popoverPresentationController()?.sourceView = presenter.view
            presenter.presentViewController(controller, animated = true, completion = null)
        }

        /**
         * Unreachable rather than unimplemented: [PlatformIntent] has no iOS constructor, so no value
         * can ever be passed in.
         *
         * This used to add "if iOS ever needs a share sheet it will be `UIActivityViewController` over
         * a payload type of its own, not this" — which is exactly what [shareFiles] now is.
         */
        override fun shareViaChooser(intent: PlatformIntent, title: String?) =
            log("shareViaChooser")

        /**
         * The controller a modal should be presented from: the key window's root, walked down through
         * anything already presented. Presenting on a controller that is itself covered does nothing
         * visible, which is the failure this avoids.
         */
        private fun topmostViewController(): UIViewController? {
            var current = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (current?.presentedViewController != null) {
                current = current.presentedViewController
            }
            return current
        }

        private fun log(action: String) =
            println("$TAG: $action requested, which iOS does not support — ignoring.")
    }
}

private const val TAG = "PlatformScreenActions"
