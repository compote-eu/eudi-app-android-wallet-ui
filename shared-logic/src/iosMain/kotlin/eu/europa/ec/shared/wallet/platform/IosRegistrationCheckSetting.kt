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

package eu.europa.ec.shared.wallet.platform

import platform.Foundation.NSUserDefaults

/**
 * The user's registration-check switch: whether issuers' and verifiers' registration certificates are
 * checked. One setting for both, as on Android, and **off by default**, as there.
 *
 * Kept in the app group's defaults so the Digital Credentials extension — another process, whose
 * `standardUserDefaults` is its own — reads the same answer as the app. Until 2026-10-02 it lived in the
 * app's standard defaults, which the extension cannot see; a value stored there is carried over the first
 * time the app reads it, so a switch turned on before the update stays on. Until the app has read it once,
 * the extension sees the check as off.
 *
 * Without an app group — a signing setup that grants none — the app's own defaults are used, and the
 * extension, which then has no wallet either, reads the check as off.
 */
object IosRegistrationCheckSetting {

    private val store: RegistrationCheckStore
        get() = RegistrationCheckStore(
            shared = IosAppGroup.identifier()?.let { NSUserDefaults(suiteName = it) },
            own = NSUserDefaults.standardUserDefaults,
        )

    fun isEnabled(): Boolean = store.isEnabled()

    fun setEnabled(value: Boolean) = store.setEnabled(value)
}

/** [IosRegistrationCheckSetting] over two stores a test can supply. */
internal class RegistrationCheckStore(
    /** The app group's defaults, or null when the build has no app group. */
    private val shared: NSUserDefaults?,
    /** This process's own defaults, where the app kept the switch before 2026-10-02. */
    private val own: NSUserDefaults,
) {

    fun isEnabled(): Boolean {
        val shared = shared ?: return own.boolForKey(KEY)
        if (shared.objectForKey(KEY) == null && own.objectForKey(KEY) != null) {
            shared.setBool(own.boolForKey(KEY), KEY)
        }
        return shared.boolForKey(KEY)
    }

    fun setEnabled(value: Boolean) = (shared ?: own).setBool(value, KEY)

    private companion object {
        /** Android's key too — `PrefKeys.getRegistrationCheckEnabled`. */
        const val KEY = "RegistrationCheckEnabled"
    }
}
