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

// The contract half of the presentation request interactor, lifted with
// PresentationRequestViewModel. `PresentationRequestInteractorImpl` stays in :presentation-feature.
//
// `setConfig` is the signature that used to block this whole seam: it needs the *real* Intent,
// because wallet-core builds the DC API request from it. It survives the move unchanged now that
// IntentAction carries a `PlatformIntent` — the impl, which is where the intent is finally unwrapped
// (via RequestUriConfigMapper.toDomainConfig), is Android-side and still sees an `android.content.Intent`.
package eu.europa.ec.presentationfeature.interactor

import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.interactor.ScopedPresentationInteractor
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.uilogic.navigation.helper.IntentAction
import kotlinx.coroutines.flow.Flow

sealed class PresentationRequestInteractorPartialState {
    data class Success(
        val verifierName: String?,
        val verifierIsTrusted: Boolean,
        val combinationsUi: List<RequestCombinationUi>,
        val claimsAreSelectable: Boolean,
    ) : PresentationRequestInteractorPartialState()

    data class NoData(
        val verifierName: String?,
        val verifierIsTrusted: Boolean,
    ) : PresentationRequestInteractorPartialState()

    data class Failure(val error: String) : PresentationRequestInteractorPartialState()
    data object VerifierNotTrusted : PresentationRequestInteractorPartialState()
    data object Disconnect : PresentationRequestInteractorPartialState()
}

interface PresentationRequestInteractor : ScopedPresentationInteractor {
    fun getRequestDocuments(): Flow<PresentationRequestInteractorPartialState>
    fun stopPresentation()
    fun updateRequestedDocuments(selectedCombination: RequestCombinationUi?)
    fun setConfig(config: RequestUriConfig, intentAction: IntentAction?)
}
