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

// Phase 2: the *contract* is KMP — `URI` becomes a String (the only consumer already stringified it),
// `DocumentId` was only ever a typealias for String, and the auth handle is `PlatformContext` — so it
// lives in commonMain next to `DocumentOfferViewModel`. `DocumentOfferInteractorImpl` stays in
// :issuance-feature with wallet-core.
//
// `credentialOffers` did NOT come along: it is the implementation's own resolve-then-issue cache,
// typed with wallet-core's `Offer`, and nothing outside the implementation reads it. Package unchanged.
package eu.europa.ec.issuancefeature.interactor

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.issuancefeature.ui.offer.model.DocumentOfferUi
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.uilogic.config.ConfigNavigation
import kotlinx.coroutines.flow.Flow

sealed class ResolveDocumentOfferInteractorPartialState {
    data class Success(
        val documents: List<DocumentOfferUi>,
        val issuerName: String,
        val issuerLogo: String?,
        val txCodeLength: Int?
    ) : ResolveDocumentOfferInteractorPartialState()

    data class NoDocument(
        val issuerName: String,
        val issuerLogo: String?,
    ) : ResolveDocumentOfferInteractorPartialState()

    data class Failure(val errorMessage: String) : ResolveDocumentOfferInteractorPartialState()

    data object IssuerNotTrusted : ResolveDocumentOfferInteractorPartialState()
}

sealed class IssueDocumentsInteractorPartialState {
    data class Success(
        val documentIds: List<String>,
    ) : IssueDocumentsInteractorPartialState()

    data class PartialSuccessWithUntrustedIssuer(
        val issuedDocumentIds: List<String>,
    ) : IssueDocumentsInteractorPartialState()

    data class DeferredSuccess(
        val successRoute: AppRoute,
    ) : IssueDocumentsInteractorPartialState()

    data class Failure(val errorMessage: String) : IssueDocumentsInteractorPartialState()

    data object IssuerNotTrusted : IssueDocumentsInteractorPartialState()

    data class UserAuthRequired(
        val crypto: BiometricCrypto,
        val resultHandler: DeviceAuthenticationResult
    ) : IssueDocumentsInteractorPartialState()
}

interface DocumentOfferInteractor {

    fun resolveDocumentOffer(offerUri: String): Flow<ResolveDocumentOfferInteractorPartialState>

    fun issueDocuments(
        offerUri: String,
        issuerName: String,
        navigation: ConfigNavigation,
        txCode: SecurePin? = null
    ): Flow<IssueDocumentsInteractorPartialState>

    fun handleUserAuthentication(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult
    )

    fun resumeOpenId4VciWithAuthorization(uri: String)
}
