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

// Phase 3b: the biometric interactor *contract*, moved so `SettingsInteractor` (which extends it) and the
// view-models that use it can live in commonMain. `BiometricInteractorImpl` stays in :common-feature with
// its Koin provider and its Android controllers. The only Android type in the signatures was the host
// `Context`, now the opaque `PlatformContext`; everything else was already shared — the availability and
// result types, `SecurePin`, `PinLockoutState` and the PIN-validity partial state. Package unchanged.
package eu.europa.ec.commonfeature.interactor

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.provider.PinLockoutState
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow

interface BiometricInteractor {
    val maxFailedPinAttempts: Int

    fun getBiometricsAvailability(): BiometricsAvailability
    suspend fun getBiometricUserSelection(): Boolean
    suspend fun storeBiometricsUsageDecision(shouldUseBiometrics: Boolean)
    fun authenticateWithBiometrics(
        context: PlatformContext,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit
    )

    fun launchBiometricSystemScreen()
    fun isPinValid(pin: SecurePin): Flow<QuickPinInteractorPinValidPartialState>

    suspend fun getPinLockoutState(): PinLockoutState
    suspend fun recordPinFailure(): PinLockoutState
    suspend fun resetPinThrottle()
}
