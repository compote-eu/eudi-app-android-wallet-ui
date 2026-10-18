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

// Phase 3b: the interactor *contract* moves to commonMain so `ProximityLoadingViewModel` can live
// there, per the `SplashInteractor` pattern — `ProximityLoadingInteractorImpl` stays in
// :proximity-feature with its Koin provider (wallet-core's WalletCorePresentationController).
//
// Every partial state here was already platform-neutral. Note the contrast with the presentation
// twin, whose states carry a `java.net.URI` redirect and an `android.content.Intent` to send, and so
// still waits on the intent/URI seam. Package unchanged.
package eu.europa.ec.proximityfeature.interactor

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.interactor.ScopedPresentationInteractor
import eu.europa.ec.corelogic.model.AuthenticationData
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow

sealed class ProximityLoadingObserveResponsePartialState {
    data class UserAuthenticationRequired(
        val authenticationData: List<AuthenticationData>,
    ) : ProximityLoadingObserveResponsePartialState()

    data class Failure(val error: String) : ProximityLoadingObserveResponsePartialState()
    data object Success : ProximityLoadingObserveResponsePartialState()
    data object RequestReadyToBeSent : ProximityLoadingObserveResponsePartialState()
}

sealed class ProximityLoadingSendRequestedDocumentPartialState {
    data class Failure(val error: String) : ProximityLoadingSendRequestedDocumentPartialState()
    data object Success : ProximityLoadingSendRequestedDocumentPartialState()
}

interface ProximityLoadingInteractor : ScopedPresentationInteractor {
    fun observeResponse(): Flow<ProximityLoadingObserveResponsePartialState>
    suspend fun sendRequestedDocuments(): ProximityLoadingSendRequestedDocumentPartialState
    fun handleUserAuthentication(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    )
}
