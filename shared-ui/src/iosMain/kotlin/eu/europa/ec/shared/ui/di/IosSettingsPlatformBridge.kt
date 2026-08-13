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

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.dashboardfeature.interactor.SettingsPlatformBridge
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.platform.PlatformIntent
import platform.Foundation.NSBundle

/**
 * iOS's [SettingsPlatformBridge]. The screen it feeds is real but short: the app version and the
 * batch-issuance-counter switch, which is the one setting iOS genuinely has.
 *
 * The three omissions are facts about this build, not placeholders:
 * - **No biometrics row.** iOS has Face ID, but the wallet has no authentication layer there at all —
 *   no PIN, no login gate, nothing for a biometrics-for-login switch to protect. Reporting
 *   [BiometricsAvailability.Failure] is how a platform says "not here", and the shared interactor then
 *   omits the row. When iOS gains a login gate, this is where `LocalAuthentication` goes.
 * - **No logs row.** Log collection is `:business-logic`'s `LogController`, an Android file-and-Uri
 *   affair; iOS writes no log files, so there would be nothing to share.
 * - **No changelog row.** The URL is per-flavour Android build config; this build has none.
 */
internal class IosSettingsPlatformBridge : SettingsPlatformBridge {

    /**
     * `CFBundleShortVersionString`, the counterpart of Android's `BuildConfig.APP_VERSION`. Empty
     * rather than a placeholder when absent — the screen centres it under the list, where an honest
     * blank is better than the word "unknown".
     */
    override val appVersion: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey(CFBundleShortVersionString) as? String
            ?: ""

    override val changelogUrl: String? get() = null

    override val canRetrieveLogs: Boolean get() = false

    override fun biometricsAvailability(): BiometricsAvailability =
        BiometricsAvailability.Failure(
            errorMessage = "Biometric login is not available on iOS yet."
        )

    /**
     * Both unreachable while [biometricsAvailability] fails: the row that would call them is not
     * built. They answer honestly rather than throwing, so a future caller gets "no" instead of a
     * crash.
     */
    override suspend fun isBiometricsEnabled(): Boolean = false

    override suspend fun setBiometricsEnabled(enabled: Boolean) {
        println("$TAG: ignoring a biometrics decision; iOS has no login gate to apply it to.")
    }

    override fun authenticateWithBiometrics(
        context: PlatformContext,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit
    ) {
        // Doubly unreachable: it also takes a `PlatformContext`, which has no iOS constructor.
        listener(BiometricsAuthenticate.Failed(errorMessage = "No biometric prompt on iOS yet."))
    }

    override fun launchBiometricSystemScreen() {
        println("$TAG: no biometric enrolment screen to open on iOS.")
    }

    /** Real, and shared with the documents list — see [IosPreferences]. */
    override suspend fun isBatchIssuanceCounterShown(): Boolean =
        IosPreferences.showBatchIssuanceCounter()

    override suspend fun setBatchIssuanceCounterShown(shown: Boolean) =
        IosPreferences.setShowBatchIssuanceCounter(shown)

    /** Null, and it could not be anything else: `PlatformIntent` is uninhabited on iOS. */
    override fun logShareIntent(): PlatformIntent? = null
}

private const val TAG = "IosSettingsPlatformBridge"
private const val CFBundleShortVersionString = "CFBundleShortVersionString"
