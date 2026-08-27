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

// Phase 3b: the interactor *contract* moves to commonMain so `SettingsViewModel` can live there, per the
// `SplashInteractor` pattern; `SettingsInteractorImpl` keeps ConfigLogic, LogController, PrefKeys and
// ResourceProvider in :dashboard-feature.
//
// This one needed a real change rather than a retype, twice. The view-model originally BUILT the
// log-sharing intent itself — `Intent().apply { action = ACTION_SEND_MULTIPLE; ... }` — which common
// code cannot do, so it became `getLogShareIntent(): PlatformIntent?`. That was still Android-shaped:
// `PlatformIntent` is uninhabited on iOS, so iOS could only ever answer null and the settings row was
// omitted there. It is now [getLogFilePaths], which both platforms can answer, with the share itself
// delegated to `PlatformScreenActions.shareFiles`. `retrieveLogFileUris` (an `ArrayList<Uri>`) remains
// deliberately OFF this contract: a platform detail only Android's implementation needs.
package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsItemUi
import eu.europa.ec.shared.platform.PlatformContext

/**
 * No longer `: BiometricInteractor`.
 *
 * The inheritance was convenience — `SettingsInteractorImpl` satisfied it with
 * `BiometricInteractor by biometricInteractor` — but it made the settings screen depend on the
 * *whole* authentication contract, PIN validation and lockout throttling included. That is 11 members
 * of which this screen uses three, and it would have forced iOS (which has no authentication layer at
 * all yet) to answer questions about PIN lockout in order to render a settings list.
 *
 * So the three that are actually used are declared here — same names and signatures, so nothing
 * calling them had to change — and [SettingsPlatformBridge] supplies them per platform.
 */
interface SettingsInteractor {
    fun getAppVersion(): String
    fun getChangelogUrl(): String?

    /**
     * Absolute paths of the collected log files, or empty when there are none to share. The emptiness
     * check the view-model used to do lives here.
     */
    fun getLogFilePaths(): List<String>
    suspend fun getSettingsItemsUi(changelogUrl: String?): List<SettingsItemUi>
    suspend fun toggleBiometricsAuthentication()
    suspend fun toggleShowBatchIssuanceCounter()
    suspend fun toggleRegistrationCheck()

    /**
     * Told to the user after [toggleRegistrationCheck], because Wallet Core reads both registration
     * policies when it builds its managers, so the flip only takes effect on the next app start.
     * It lives here rather than in the view-model for the same reason the other strings do: shared
     * view-models have no resource access of their own.
     */
    val registrationCheckRestartMessage: String

    // Previously inherited from BiometricInteractor; see the note above.
    fun getBiometricsAvailability(): BiometricsAvailability

    fun authenticateWithBiometrics(
        context: PlatformContext,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit
    )

    fun launchBiometricSystemScreen()
}
