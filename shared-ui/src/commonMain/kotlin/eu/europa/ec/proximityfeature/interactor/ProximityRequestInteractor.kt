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

// The contract half of the proximity request interactor, lifted with ProximityRequestViewModel.
// `ProximityRequestInteractorImpl` stays in :proximity-feature — it drives
// WalletCorePresentationController and RequestTransformer, both still Android-side.
package eu.europa.ec.proximityfeature.interactor

import eu.europa.ec.commonfeature.interactor.ScopedPresentationInteractor
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.corelogic.model.RelyingPartyDomain
import kotlinx.coroutines.flow.Flow

sealed class ProximityRequestInteractorPartialState {
    data class Success(
        val relyingParty: RelyingPartyDomain,
        val combinationsUi: List<RequestCombinationUi>,
        val claimsAreSelectable: Boolean,
    ) : ProximityRequestInteractorPartialState()

    data class NoData(
        val relyingParty: RelyingPartyDomain,
    ) : ProximityRequestInteractorPartialState()

    data class Failure(val error: String) : ProximityRequestInteractorPartialState()
    data object VerifierNotTrusted : ProximityRequestInteractorPartialState()
    data object Disconnect : ProximityRequestInteractorPartialState()
}

interface ProximityRequestInteractor : ScopedPresentationInteractor {
    fun getRequestDocuments(): Flow<ProximityRequestInteractorPartialState>
    fun stopPresentation()
    fun updateRequestedDocuments(selectedCombination: RequestCombinationUi?)
}
