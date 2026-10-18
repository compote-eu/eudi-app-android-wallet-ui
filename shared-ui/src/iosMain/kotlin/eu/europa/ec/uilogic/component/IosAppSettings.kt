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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * Opens this app's own pane in the system Settings app.
 *
 * As deep as iOS lets a third-party app take the user. There is no public URL for the Face ID or
 * passcode panes, so anything more specific is impossible rather than merely unimplemented — the
 * official native EUDI iOS wallet lands on this same pane, for this same reason.
 *
 * Which makes it the right destination for two different questions and a complete answer to neither:
 * a *denied* biometric permission is turned back on here, while *enrolment* lives in
 * Settings › Face ID & Passcode and the user has to walk there. It beats doing nothing, which is
 * what the biometric callers used to do.
 *
 * On the main queue because UIKit requires it, and fire-and-forget because nothing here can act on
 * whether the user changed anything once they arrive.
 */
internal fun openIosAppSettings() {
    CoroutineScope(Dispatchers.Main).launch {
        val settings = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
        if (settings == null) {
            // Cannot happen with a system constant, but a silent no-op is what this function exists
            // to stop being.
            println("$TAG: the system settings URL would not parse — ignoring.")
            return@launch
        }
        UIApplication.sharedApplication.openURL(
            url = settings,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
    }
}

private const val TAG = "IosAppSettings"
