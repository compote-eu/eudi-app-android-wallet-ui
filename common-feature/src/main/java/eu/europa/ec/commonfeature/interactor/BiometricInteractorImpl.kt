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

package eu.europa.ec.commonfeature.interactor

import android.content.Context
import eu.europa.ec.authenticationlogic.config.AuthenticationConfig
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricAuthenticationController
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.controller.storage.BiometryStorageController
import eu.europa.ec.authenticationlogic.controller.throttle.PinThrottleController
import eu.europa.ec.authenticationlogic.provider.PinLockoutState
import eu.europa.ec.authenticationlogic.secure.SecurePin
import kotlinx.coroutines.flow.Flow

// Phase 3b: the contract moved to :shared-ui/commonMain (same package).
class BiometricInteractorImpl(
    private val biometryStorageController: BiometryStorageController,
    private val biometricAuthenticationController: BiometricAuthenticationController,
    private val quickPinInteractor: QuickPinInteractor,
    private val pinThrottleController: PinThrottleController,
    private val authenticationConfig: AuthenticationConfig,
) : BiometricInteractor {

    override val maxFailedPinAttempts: Int
        get() = authenticationConfig.maxFailedPinAttempts

    override fun isPinValid(pin: SecurePin): Flow<QuickPinInteractorPinValidPartialState> =
        quickPinInteractor.isCurrentPinValid(pin)

    override suspend fun getPinLockoutState(): PinLockoutState =
        pinThrottleController.getState()

    override suspend fun recordPinFailure(): PinLockoutState =
        pinThrottleController.recordFailure()

    override suspend fun resetPinThrottle() {
        pinThrottleController.recordSuccess()
    }

    override suspend fun storeBiometricsUsageDecision(shouldUseBiometrics: Boolean) {
        biometryStorageController.setUseBiometricsAuth(shouldUseBiometrics)
    }

    override suspend fun getBiometricUserSelection(): Boolean {
        return biometryStorageController.getUseBiometricsAuth()
    }

    override fun getBiometricsAvailability(): BiometricsAvailability {
        return biometricAuthenticationController.getBiometricsAvailability()
    }

    override fun authenticateWithBiometrics(
        context: Context?,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit
    ) {
        // Nullable only because the shared signature has to admit iOS, which has no host context.
        // On Android the screen always has one, so this is unreachable in practice — it reports an
        // outcome regardless, because the alternative is a caller that never hears back. Neither
        // caller shows the message; `notifyOnAuthenticationFailure` is what surfaces a real failure.
        if (context == null) {
            listener(BiometricsAuthenticate.Failed(errorMessage = "No host context for the prompt."))
            return
        }
        biometricAuthenticationController.authenticate(
            context,
            notifyOnAuthenticationFailure,
            listener
        )
    }

    override fun launchBiometricSystemScreen() {
        biometricAuthenticationController.launchBiometricSystemScreen()
    }
}