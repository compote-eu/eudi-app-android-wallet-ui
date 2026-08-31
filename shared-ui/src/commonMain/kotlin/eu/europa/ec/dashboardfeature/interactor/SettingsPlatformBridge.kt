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

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.uilogic.component.PlatformScreenActions

/**
 * The per-platform half of the settings screen.
 *
 * Everything the screen *shows* — which rows exist, in which order, with which icons, and which
 * switch is on — is built by `SettingsInteractorImpl` in commonMain from the answers below. The
 * split is worth stating because it is not obvious from the member count: this interface is wide
 * (eleven members) but each member is one or two lines per platform, while the list construction it
 * feeds is ~85 lines that would otherwise be duplicated and drift.
 *
 * Every member is a plain Boolean, String or already-shared type. Android's implementation is the
 * only place `ConfigLogic`, `LogController`, `PrefKeys` and `BiometricInteractor` are named.
 */
interface SettingsPlatformBridge {

    /** The version string shown at the bottom of the screen. */
    val appVersion: String

    /** Where the changelog lives, or null when this build has none — the row is then omitted. */
    val changelogUrl: String?

    /**
     * Whether this platform collects log files at all. False omits the row, rather than leaving a
     * row that does nothing when tapped.
     */
    val canRetrieveLogs: Boolean

    /**
     * Whether the device can do biometric authentication. A [BiometricsAvailability.Failure] omits
     * the biometrics row entirely, which is how a platform says "not here".
     */
    fun biometricsAvailability(): BiometricsAvailability

    /**
     * Whether this platform evaluates issuer and relying-party registration certificates at all.
     * False omits the row: the check lives in Wallet Core, which is Android-only, so on iOS a
     * switch here would promise enforcement that nothing performs.
     */
    val canCheckRegistrations: Boolean

    /** The stored registration-check decision — the switch position for that row. */
    suspend fun isRegistrationCheckEnabled(): Boolean

    suspend fun setRegistrationCheckEnabled(enabled: Boolean)

    /** The user's stored biometrics-for-login decision — the biometrics row's switch position. */
    suspend fun isBiometricsEnabled(): Boolean

    suspend fun setBiometricsEnabled(enabled: Boolean)

    /**
     * Raises the platform's biometric prompt. Takes the host [PlatformContext] because Android's
     * `BiometricPrompt` needs the activity; the caller has it from the click that started this.
     * Null where the platform has none: iOS raises the same prompt without one.
     */
    fun authenticateWithBiometrics(
        context: PlatformContext?,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit
    )

    /**
     * Whether [launchBiometricSystemScreen] actually lands on biometric enrolment.
     *
     * True on Android, which can open that OS screen directly — self-explanatory, so the caller sends
     * the user straight there. False on iOS, where no app may link deeper than its own Settings pane;
     * a caller that arrives there having been told nothing has been dumped somewhere unrelated to what
     * it asked for, so the shared screen explains first and offers the choice.
     *
     * A capability rather than a platform check, so the screens never ask which platform they are on —
     * the same arrangement as [canRetrieveLogs] and [canCheckRegistrations].
     */
    val canOpenBiometricEnrolment: Boolean

    /** Sends the user to the OS screen where biometrics are enrolled, or as near as it can get. */
    fun launchBiometricSystemScreen()

    /** Whether document rows show their credentials counter. */
    suspend fun isBatchIssuanceCounterShown(): Boolean

    suspend fun setBatchIssuanceCounterShown(shown: Boolean)

    /**
     * Absolute paths of the collected log files, newest-relevant first, or empty when there is
     * nothing to share.
     *
     * **Paths rather than an intent, since 2026-08-27.** This used to be `logShareIntent(): PlatformIntent?`,
     * which no iOS implementation could ever satisfy: `PlatformIntent` is uninhabited there, so iOS
     * could only answer `null` and the settings row was omitted. A path is something both platforms
     * genuinely have — Android's Treessence files, iOS's multipaz log — and turning one into a share
     * sheet is the platform's job, which is what
     * [PlatformScreenActions.shareFiles] is for.
     *
     * Android returns several because it rotates (10 files of 5 MB); iOS returns at most one.
     */
    fun logFilePaths(): List<String>
}
