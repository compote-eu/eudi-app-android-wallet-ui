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

package eu.europa.ec.dashboardfeature.interactor

import android.content.Intent
import android.net.Uri
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.businesslogic.controller.storage.PrefKeys
import eu.europa.ec.corelogic.provider.RegistrationCheckProvider
import eu.europa.ec.commonfeature.interactor.BiometricInteractor
import eu.europa.ec.shared.platform.PlatformContext

/**
 * Android's half of the settings screen: the build's version and changelog URL, the log files, the
 * batch-counter preference and the real biometric prompt.
 *
 * This is the only place those four collaborators are named — the shared interactor above it sees
 * Booleans and Strings. `BiometricInteractor` is *held* rather than inherited, which is the whole
 * reason `SettingsInteractor` no longer extends it: the settings screen uses three of its members and
 * has no business exposing PIN validation or lockout throttling.
 */
class AndroidSettingsPlatformBridge(
    private val biometricInteractor: BiometricInteractor,
    private val configLogic: ConfigLogic,
    private val logController: LogController,
    private val prefKeys: PrefKeys,
    private val registrationCheckProvider: RegistrationCheckProvider,
) : SettingsPlatformBridge {

    override val appVersion: String get() = configLogic.appVersion

    override val changelogUrl: String? get() = configLogic.changelogUrl

    /** Android always collects logs, so the row is always offered — as it was before this split. */
    override val canRetrieveLogs: Boolean get() = true

    override fun biometricsAvailability(): BiometricsAvailability =
        biometricInteractor.getBiometricsAvailability()

    override suspend fun isBiometricsEnabled(): Boolean =
        biometricInteractor.getBiometricUserSelection()

    override suspend fun setBiometricsEnabled(enabled: Boolean) =
        biometricInteractor.storeBiometricsUsageDecision(shouldUseBiometrics = enabled)

    override fun authenticateWithBiometrics(
        context: PlatformContext,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit
    ) = biometricInteractor.authenticateWithBiometrics(
        context = context,
        notifyOnAuthenticationFailure = notifyOnAuthenticationFailure,
        listener = listener,
    )

    override fun launchBiometricSystemScreen() = biometricInteractor.launchBiometricSystemScreen()

    override suspend fun isBatchIssuanceCounterShown(): Boolean =
        prefKeys.getShowBatchIssuanceCounter()

    override suspend fun setBatchIssuanceCounterShown(shown: Boolean) =
        prefKeys.setShowBatchIssuanceCounter(value = shown)

    /** Wallet Core performs the check, so the row is offered. */
    override val canCheckRegistrations: Boolean get() = true

    override suspend fun isRegistrationCheckEnabled(): Boolean =
        registrationCheckProvider.isEnabled()

    override suspend fun setRegistrationCheckEnabled(enabled: Boolean) =
        registrationCheckProvider.setEnabled(enabled = enabled)

    /**
     * Builds the log-sharing intent, which shared code cannot: an intent is an opaque
     * `PlatformIntent` there. Returns null when there is nothing to share.
     */
    override fun logShareIntent(): Intent? {
        val logs = retrieveLogFileUris()
        if (logs.isEmpty()) return null

        return Intent().apply {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, logs)
            type = "text/*"
        }
    }

    // Not on the bridge contract: an ArrayList<Uri> is a platform detail only this class needs.
    fun retrieveLogFileUris(): ArrayList<Uri> {
        return ArrayList(logController.retrieveLogFileUris())
    }
}
