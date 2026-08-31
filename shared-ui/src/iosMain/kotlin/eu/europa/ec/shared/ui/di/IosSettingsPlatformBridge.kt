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

import eu.europa.ec.shared.wallet.log.IosLogFile

import eu.europa.ec.shared.wallet.config.iosWalletConfig

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.storage.IosBiometricAvailability
import eu.europa.ec.authenticationlogic.storage.IosBiometricGate
import eu.europa.ec.authenticationlogic.storage.IosBiometricOutcome
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.biometric_no_hardware
import eu.europa.ec.shared.resources.biometric_prompt_subtitle
import eu.europa.ec.uilogic.component.openIosAppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import eu.europa.ec.dashboardfeature.interactor.SettingsPlatformBridge
import eu.europa.ec.shared.platform.PlatformContext
import platform.Foundation.NSBundle

/**
 * iOS's [SettingsPlatformBridge]. The screen it feeds is real but short: the app version and the
 * batch-issuance-counter switch, which is the one setting iOS genuinely has.
 *
 * **The biometrics row is real now.** It answers from the same [IosBiometricGate] the login screen
 * uses, which matters more than it looks: the switch's state *is* the existence of the gated Keychain
 * item, so a switch showing "on" cannot mean anything other than that the item is there. Turning it on
 * creates the item, which is the moment iOS decides whether the policy can be honoured at all.
 *
 * The two remaining omissions are facts about this build, not placeholders:
 * - **No logs row.** Log collection is `:business-logic`'s `LogController`, an Android file-and-Uri
 *   affair; iOS writes no log files, so there would be nothing to share.
 * - **No changelog row.** The URL is per-flavour Android build config; this build has none.
 */
internal class IosSettingsPlatformBridge(
    private val gate: IosBiometricGate,
    private val strings: StringCatalog,
) : SettingsPlatformBridge {

    /**
     * `CFBundleShortVersionString`, the counterpart of Android's `BuildConfig.APP_VERSION`. Empty
     * rather than a placeholder when absent — the screen centres it under the list, where an honest
     * blank is better than the word "unknown".
     */
    override val appVersion: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey(CFBundleShortVersionString) as? String
            ?: ""

    /** Per build flavour, as on Android: `dev` publishes none, `demo` points at the GitHub releases. */
    override val changelogUrl: String? get() = iosWalletConfig.changelogUrl

    /**
     * True since multipaz writes a log file — see [IosLogFile]. The row
     * still disappears when the file is empty, because [logFilePaths] answers empty and the interactor
     * treats that as "nothing to share", exactly as it does on an Android build with no logs yet.
     */
    override val canRetrieveLogs: Boolean get() = true

    override fun biometricsAvailability(): BiometricsAvailability =
        when (gate.availability()) {
            IosBiometricAvailability.Available -> BiometricsAvailability.CanAuthenticate
            IosBiometricAvailability.NotEnrolled -> BiometricsAvailability.NonEnrolled
            IosBiometricAvailability.Unavailable ->
                BiometricsAvailability.Failure(errorMessage = strings[Res.string.biometric_no_hardware])
        }

    override suspend fun isBiometricsEnabled(): Boolean = gate.isEnabled()

    /**
     * Turning the switch on is what creates the gated item, and iOS can refuse — no passcode set, no
     * biometrics enrolled. A refusal leaves the item absent, so the next [isBiometricsEnabled] reports
     * the switch back off rather than leaving it on over nothing.
     */
    override suspend fun setBiometricsEnabled(enabled: Boolean) {
        if (enabled) gate.enable() else gate.disable()
    }

    /** No app may link to Settings › Face ID & Passcode, so the destination has to be explained. */
    override val canOpenBiometricEnrolment: Boolean get() = false

    override fun authenticateWithBiometrics(
        context: PlatformContext?,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit
    ) {
        // The settings switch confirms with a prompt before it changes anything, exactly as Android's
        // does. `PlatformContext` is always null here and unused: the prompt is the system's.
        CoroutineScope(Dispatchers.Main).launch {
            listener(
                when (val outcome = gate.authenticate(strings[Res.string.biometric_prompt_subtitle])) {
                    is IosBiometricOutcome.Success -> BiometricsAuthenticate.Success
                    is IosBiometricOutcome.Cancelled -> BiometricsAuthenticate.Cancelled
                    is IosBiometricOutcome.Failed ->
                        BiometricsAuthenticate.Failed(errorMessage = outcome.message)
                }
            )
        }
    }

    /**
     * The app's own Settings pane, which is as close to biometric enrolment as iOS allows anyone to
     * link — see [openIosAppSettings]. The switch's "you have nothing enrolled" branch used to end
     * here in silence.
     */
    override fun launchBiometricSystemScreen() = openIosAppSettings()

    /** Real, and shared with the documents list — see [IosPreferences]. */
    override suspend fun isBatchIssuanceCounterShown(): Boolean =
        IosPreferences.showBatchIssuanceCounter()

    override suspend fun setBatchIssuanceCounterShown(shown: Boolean) =
        IosPreferences.setShowBatchIssuanceCounter(shown)

    /**
     * False, and the row is omitted. The issuer and relying-party registration policies live in
     * `eudi-lib-android-wallet-core`, which has no iOS counterpart, and multipaz has no equivalent —
     * so nothing here would evaluate a registration certificate. A switch would promise enforcement
     * that does not happen, which is worse than no switch.
     */
    override val canCheckRegistrations: Boolean get() = false

    override suspend fun isRegistrationCheckEnabled(): Boolean = false

    override suspend fun setRegistrationCheckEnabled(enabled: Boolean) = Unit

    /**
     * multipaz's log file, when it has anything in it.
     *
     * This used to be `logShareIntent(): PlatformIntent?` returning null — not a limitation of iOS but
     * of the seam, which asked for an Android `Intent`. Paths are answerable here, so the settings row
     * is no longer omitted: [canRetrieveLogs] is true and this points at the file
     * [IosLogFile] has multipaz writing.
     *
     * Up to ten paths, the same as Android: [IosLogFile] rotates rather than leaning on multipaz's
     * single truncating file, so both platforms hand over a comparable bundle.
     */
    override fun logFilePaths(): List<String> = IosLogFile.paths()
}

// Named for the Info.plist key it holds, so the two read as the same thing at the call site.
@Suppress("TopLevelPropertyNaming")
private const val CFBundleShortVersionString = "CFBundleShortVersionString"
