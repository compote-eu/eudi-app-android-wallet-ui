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

// Phase 3b: the interactor *contract* moves to commonMain so `PinViewModel` can live there, following
// the `SplashInteractor` pattern — `QuickPinInteractorImpl` stays in :common-feature along with its
// Koin provider, since it needs PinStorageController, PinThrottleController, AuthenticationConfig and
// ResourceProvider. Every signature here is already platform-neutral now that `SecurePin` and
// `PinLockoutState` are in :shared-logic. Package unchanged.
//
// The partial states carry resolved `String` error messages rather than `UiText`: they are produced by
// the implementation, and interactors are the layer that owns resolved strings.
package eu.europa.ec.commonfeature.interactor

import eu.europa.ec.authenticationlogic.provider.PinLockoutState
import eu.europa.ec.authenticationlogic.secure.SecurePin
import kotlinx.coroutines.flow.Flow

interface QuickPinInteractor {
    val maxFailedPinAttempts: Int

    fun setPin(newPin: SecurePin, initialPin: SecurePin): Flow<QuickPinInteractorSetPinPartialState>

    fun changePin(
        newPin: SecurePin
    ): Flow<QuickPinInteractorSetPinPartialState>

    fun isCurrentPinValid(pin: SecurePin): Flow<QuickPinInteractorPinValidPartialState>
    suspend fun hasPin(): Boolean

    suspend fun getPinLockoutState(): PinLockoutState
    suspend fun recordPinFailure(): PinLockoutState
    suspend fun resetPinThrottle()
}

sealed class QuickPinInteractorSetPinPartialState {
    data object Success : QuickPinInteractorSetPinPartialState()
    data class Failed(val errorMessage: String) : QuickPinInteractorSetPinPartialState()
}

sealed class QuickPinInteractorPinValidPartialState {
    data object Success : QuickPinInteractorPinValidPartialState()
    data class Failed(val errorMessage: String) : QuickPinInteractorPinValidPartialState()
}
