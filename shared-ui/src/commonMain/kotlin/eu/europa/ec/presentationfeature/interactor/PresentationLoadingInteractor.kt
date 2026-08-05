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

// Phase 3b: the interactor *contract* moves to commonMain so `PresentationLoadingViewModel` can join its
// proximity twin there, per the `SplashInteractor` pattern — `PresentationLoadingInteractorImpl` stays in
// :presentation-feature with its Koin provider (wallet-core's WalletCorePresentationController).
//
// Two payloads had to be retyped, each the way its kind of value should cross the seam: the redirect is a
// `String` rather than a `java.net.URI` (a URI is representable as neutral data — the same choice
// `SuccessViewModel.Effect.Navigation.DeepLink` already made), while the intent becomes the opaque
// `PlatformIntent` handle, since an intent is not. On Android that handle is a typealias, so the
// implementation's `intent = response.intent` is unchanged.
//
// NOTE both payloads are write-only in production: the implementation fills them in and
// `PresentationLoadingViewModel` matches on the branch but reads neither, calling `onSuccess()` for both.
// They are kept rather than dropped — this looks like an unfinished upstream feature, and the redirect is
// read from `PresentationSuccessInteractor.redirectUri` on a different path.
package eu.europa.ec.presentationfeature.interactor

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.interactor.ScopedPresentationInteractor
import eu.europa.ec.corelogic.model.AuthenticationData
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.platform.PlatformIntent
import kotlinx.coroutines.flow.Flow

sealed class PresentationLoadingObserveResponsePartialState {
    data class UserAuthenticationRequired(
        val authenticationData: List<AuthenticationData>,
    ) : PresentationLoadingObserveResponsePartialState()

    data class Failure(val error: String) : PresentationLoadingObserveResponsePartialState()
    data object Success : PresentationLoadingObserveResponsePartialState()
    data class Redirect(val uri: String) : PresentationLoadingObserveResponsePartialState()
    data object RequestReadyToBeSent : PresentationLoadingObserveResponsePartialState()
    data class IntentToSend(val intent: PlatformIntent) :
        PresentationLoadingObserveResponsePartialState()
}

sealed class PresentationLoadingSendRequestedDocumentPartialState {
    data class Failure(val error: String) : PresentationLoadingSendRequestedDocumentPartialState()
    data object Success : PresentationLoadingSendRequestedDocumentPartialState()
}

interface PresentationLoadingInteractor : ScopedPresentationInteractor {
    fun observeResponse(): Flow<PresentationLoadingObserveResponsePartialState>
    suspend fun sendRequestedDocuments(): PresentationLoadingSendRequestedDocumentPartialState
    fun handleUserAuthentication(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    )
}
