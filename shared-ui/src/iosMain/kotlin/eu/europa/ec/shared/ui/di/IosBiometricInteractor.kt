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
import eu.europa.ec.authenticationlogic.provider.PinLockoutState
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.authenticationlogic.storage.IosBiometricAvailability
import eu.europa.ec.authenticationlogic.storage.IosBiometricGate
import eu.europa.ec.authenticationlogic.storage.IosBiometricOutcome
import eu.europa.ec.commonfeature.interactor.BiometricInteractor
import eu.europa.ec.commonfeature.interactor.QuickPinInteractor
import eu.europa.ec.commonfeature.interactor.QuickPinInteractorPinValidPartialState
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.biometric_no_hardware
import eu.europa.ec.shared.resources.biometric_prompt_subtitle
import eu.europa.ec.uilogic.component.openIosAppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * iOS's login gate: the PIN through the shared [QuickPinInteractor], Face ID / Touch ID through
 * [IosBiometricGate].
 *
 * Everything about the PIN — validity, failure counting, lockout — is the shared interactor over the
 * Keychain-backed store, so the unlock screen behaves as it does on Android. The biometric half is now
 * real, and real in the way that matters: the gate binds the prompt to a Keychain item the *system*
 * releases only after biometry, which is the iOS answer to Android's Keystore-backed `CryptoObject`.
 * The earlier version of this class declined to raise a prompt at all rather than raise one the app
 * itself decided the outcome of; that objection is what the gate's design answers.
 */
internal class IosBiometricInteractor(
    private val quickPinInteractor: QuickPinInteractor,
    private val gate: IosBiometricGate,
    private val strings: StringCatalog,
) : BiometricInteractor {

    override val maxFailedPinAttempts: Int
        get() = quickPinInteractor.maxFailedPinAttempts

    override fun getBiometricsAvailability(): BiometricsAvailability =
        when (gate.availability()) {
            IosBiometricAvailability.Available -> BiometricsAvailability.CanAuthenticate
            IosBiometricAvailability.NotEnrolled -> BiometricsAvailability.NonEnrolled
            IosBiometricAvailability.Unavailable ->
                BiometricsAvailability.Failure(errorMessage = strings[Res.string.biometric_no_hardware])
        }

    /**
     * Whether biometric login is on, which on iOS is the same question as whether the gated Keychain
     * item exists — there is no separate preference that could disagree with it.
     */
    override suspend fun getBiometricUserSelection(): Boolean = gate.isEnabled()

    override suspend fun storeBiometricsUsageDecision(shouldUseBiometrics: Boolean) {
        if (shouldUseBiometrics) gate.enable() else gate.disable()
    }

    /**
     * Raises the prompt, on the callback shape the shared screen expects.
     *
     * [context] is always null on iOS and unused: the prompt belongs to the system rather than to a
     * host activity, which is exactly why Android needs the parameter and this does not.
     */
    override fun authenticateWithBiometrics(
        context: PlatformContext?,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit,
    ) {
        // The contract is a callback rather than a suspend function, so the wait is launched here.
        // Main, because the listener drives view state.
        CoroutineScope(Dispatchers.Main).launch {
            // The system prompt's own subtitle, and the same words Android's `BiometricPrompt`
            // shows — the prompt is the platform's, but what it says should not depend on which.
            val outcome = gate.authenticate(reason = strings[Res.string.biometric_prompt_subtitle])
            listener(
                when (outcome) {
                    is IosBiometricOutcome.Success -> BiometricsAuthenticate.Success
                    is IosBiometricOutcome.Cancelled -> BiometricsAuthenticate.Cancelled
                    is IosBiometricOutcome.Failed ->
                        BiometricsAuthenticate.Failed(errorMessage = outcome.message)
                }
            )
        }
    }

    /**
     * iOS still has no biometric-enrolment screen a third-party app may open, so this opens the app's
     * own Settings pane: the nearest reachable place, and where a *denied* Face ID permission is
     * turned back on.
     *
     * This used to do nothing, on the argument that landing the user somewhere that is not the
     * enrolment screen is worse than being honest. That was wrong about which failure is worse. The
     * caller reaches here only from the "you have no biometrics set up" branch, so doing nothing
     * makes a deliberate tap look broken, and the official native EUDI iOS wallet answers the same
     * situation the same way — an explanatory alert with a *Go to settings* button. The message half
     * is still missing here; see [openIosAppSettings].
     */
    override fun launchBiometricSystemScreen() = openIosAppSettings()

    override fun isPinValid(pin: SecurePin): Flow<QuickPinInteractorPinValidPartialState> =
        quickPinInteractor.isCurrentPinValid(pin)

    override suspend fun getPinLockoutState(): PinLockoutState =
        quickPinInteractor.getPinLockoutState()

    override suspend fun recordPinFailure(): PinLockoutState = quickPinInteractor.recordPinFailure()

    override suspend fun resetPinThrottle() = quickPinInteractor.resetPinThrottle()
}
