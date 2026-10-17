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

package eu.europa.ec.uxspike.real

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingObserveResponsePartialState
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingSendRequestedDocumentPartialState
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/**
 * A fake of the real [PresentationLoadingInteractor] interface (the app's own loading fake is private to its test):
 * replays [states], answers [sendRequestedDocuments] with [send] and every key prompt with [authSucceeds].
 */
internal class FakeLoading(
    private vararg val states: PresentationLoadingObserveResponsePartialState,
    private val send: PresentationLoadingSendRequestedDocumentPartialState = PresentationLoadingSendRequestedDocumentPartialState.Success,
    private val authSucceeds: Boolean = true,
) : PresentationLoadingInteractor {
    override var presentationScopeId: String = ""
        private set
    override val initiatorRoute: String = ""
    var sends: Int = 0
        private set
    var prompts: Int = 0
        private set

    override fun setScopeId(scopeId: String) {
        presentationScopeId = scopeId
    }

    override fun observeResponse(): Flow<PresentationLoadingObserveResponsePartialState> = flow { states.forEach { emit(it) } }

    override suspend fun sendRequestedDocuments(): PresentationLoadingSendRequestedDocumentPartialState = send.also { sends++ }

    override fun handleUserAuthentication(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) {
        prompts++
        if (authSucceeds) {
            runBlocking { resultHandler.onAuthenticationSuccess() }
        } else {
            resultHandler.onAuthenticationFailure()
        }
    }
}
