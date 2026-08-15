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
import eu.europa.ec.commonfeature.interactor.BiometricInteractor
import eu.europa.ec.commonfeature.interactor.QuickPinInteractor
import eu.europa.ec.commonfeature.interactor.QuickPinInteractorPinValidPartialState
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow

/**
 * iOS's login gate: the PIN half is real, the biometric half is not there yet.
 *
 * Everything about the PIN — validity, failure counting, lockout — is the shared [QuickPinInteractor] over
 * the Keychain-backed store, so the unlock screen behaves exactly as it does on Android. What this reports
 * honestly is that there is no biometric prompt: [getBiometricsAvailability] fails, so the shared screen
 * shows its PIN field and never offers Face ID.
 *
 * **Why not `LocalAuthentication` already:** `LAContext.evaluatePolicy` would raise a Face ID prompt easily
 * enough, but a biometric *login* has to be worth something — on Android, enabling it stores the decision
 * and the PIN check is then guarded by a Keystore key that biometry unlocks. Wiring the prompt without that
 * would mean a Face ID that any failed scan lets you skip past with the PIN, which is theatre. The prompt
 * belongs with the key policy, and that is its own piece of work.
 */
internal class IosBiometricInteractor(
    private val quickPinInteractor: QuickPinInteractor,
) : BiometricInteractor {

    override val maxFailedPinAttempts: Int
        get() = quickPinInteractor.maxFailedPinAttempts

    override fun getBiometricsAvailability(): BiometricsAvailability =
        BiometricsAvailability.Failure(errorMessage = NOT_AVAILABLE)

    /** Nothing ever stored it, so the answer is the honest one rather than a remembered preference. */
    override suspend fun getBiometricUserSelection(): Boolean = false

    override suspend fun storeBiometricsUsageDecision(shouldUseBiometrics: Boolean) = Unit

    /**
     * Unreachable while [getBiometricsAvailability] fails — the shared screen only calls this once
     * availability says yes — but it answers rather than throwing, so a future caller gets "no".
     */
    override fun authenticateWithBiometrics(
        context: PlatformContext,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit,
    ) = listener(BiometricsAuthenticate.Failed(errorMessage = NOT_AVAILABLE))

    /** iOS has no "enrol a biometric" screen a third-party app may open. */
    override fun launchBiometricSystemScreen() = Unit

    override fun isPinValid(pin: SecurePin): Flow<QuickPinInteractorPinValidPartialState> =
        quickPinInteractor.isCurrentPinValid(pin)

    override suspend fun getPinLockoutState(): PinLockoutState =
        quickPinInteractor.getPinLockoutState()

    override suspend fun recordPinFailure(): PinLockoutState = quickPinInteractor.recordPinFailure()

    override suspend fun resetPinThrottle() = quickPinInteractor.resetPinThrottle()

    private companion object {
        const val NOT_AVAILABLE = "Biometric login is not available on iOS yet."
    }
}
