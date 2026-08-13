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

package eu.europa.ec.shared.ui.di

import platform.Foundation.NSUserDefaults

/**
 * The iOS side of the handful of plain UI preferences the shared screens read, over `NSUserDefaults`.
 *
 * This is deliberately *not* a port of `:business-logic`'s `PrefKeys`/`PrefsController`: that layer is
 * an encrypted datastore holding the wallet PIN hash and other secrets, and it should stay Android-only
 * until iOS has a security story of its own. What lives here is only what a settings *switch*
 * legitimately stores — user-visible display choices, no secrets — which is exactly what
 * `NSUserDefaults` is for.
 *
 * Two things must agree with Android and are easy to get wrong:
 * - the **default is true** when the key is absent, matching `PrefsController`'s
 *   `getBool("ShowBatchIssuanceCounter", true)`. `NSUserDefaults.boolForKey` returns false for a
 *   missing key, so absence is checked explicitly rather than trusted.
 * - the same value is read by both the settings toggle and the documents list, so flipping the switch
 *   actually changes what the list shows. `IosSettingsPlatformBridge` and
 *   `IosDocumentsPlatformBridge` therefore both come here rather than keeping their own answer.
 */
internal object IosPreferences {

    /** Same key string as Android's, so the two platforms' stores read alike even though they are separate. */
    private const val SHOW_BATCH_ISSUANCE_COUNTER = "ShowBatchIssuanceCounter"

    fun showBatchIssuanceCounter(): Boolean {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(SHOW_BATCH_ISSUANCE_COUNTER) == null) return true
        return defaults.boolForKey(SHOW_BATCH_ISSUANCE_COUNTER)
    }

    fun setShowBatchIssuanceCounter(value: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(value, SHOW_BATCH_ISSUANCE_COUNTER)
    }
}
