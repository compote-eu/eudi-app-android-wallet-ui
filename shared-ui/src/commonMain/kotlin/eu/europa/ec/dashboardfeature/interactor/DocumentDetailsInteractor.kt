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

// Phase 2: the *contract* is KMP — the document/claim models moved to shared, `DocumentId` was only
// ever a typealias for String, and the auth handle is `PlatformContext` — so it lives in commonMain
// next to `DocumentDetailsViewModel`. `DocumentDetailsInteractorImpl` stays in :dashboard-feature: it
// reads wallet-core documents, resolves strings through ResourceProvider and drives re-issuance.
// Package unchanged.
package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.corelogic.model.UntrustedIssuerReasonDomain
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.dashboardfeature.ui.documents.detail.model.DocumentDetailsDomain
import eu.europa.ec.dashboardfeature.ui.documents.model.DocumentCredentialsInfoUi
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.uilogic.component.IssuerDetailsCardDataUi
import kotlinx.coroutines.flow.Flow

sealed class DocumentDetailsInteractorIssuancePartialState {
    data object Success : DocumentDetailsInteractorIssuancePartialState()

    data class Failure(val errorMessage: String) : DocumentDetailsInteractorIssuancePartialState()

    /** @property reason which of the two trust layers refused the re-issue. */
    data class IssuerNotTrusted(
        val reason: UntrustedIssuerReasonDomain,
    ) : DocumentDetailsInteractorIssuancePartialState()

    data class UserAuthRequired(
        val crypto: BiometricCrypto,
        val resultHandler: DeviceAuthenticationResult,
    ) : DocumentDetailsInteractorIssuancePartialState()
}

sealed class DocumentDetailsInteractorPartialState {
    data class Success(
        val issuerDetails: IssuerDetailsCardDataUi,
        val documentDetailsDomain: DocumentDetailsDomain,
        val documentIsBookmarked: Boolean,
        val documentCredentialsInfoUi: DocumentCredentialsInfoUi?,
    ) : DocumentDetailsInteractorPartialState()

    data class Failure(val error: String) : DocumentDetailsInteractorPartialState()
}

sealed class DocumentDetailsInteractorDeleteDocumentPartialState {
    data object SingleDocumentDeleted : DocumentDetailsInteractorDeleteDocumentPartialState()
    data object AllDocumentsDeleted : DocumentDetailsInteractorDeleteDocumentPartialState()
    data class Failure(
        val errorMessage: String
    ) : DocumentDetailsInteractorDeleteDocumentPartialState()
}

sealed class DocumentDetailsInteractorStoreBookmarkPartialState {
    data class Success(
        val bookmarkId: String
    ) : DocumentDetailsInteractorStoreBookmarkPartialState()

    data object Failure : DocumentDetailsInteractorStoreBookmarkPartialState()
}

sealed class DocumentDetailsInteractorDeleteBookmarkPartialState {
    data object Success : DocumentDetailsInteractorDeleteBookmarkPartialState()
    data object Failure : DocumentDetailsInteractorDeleteBookmarkPartialState()
}

interface DocumentDetailsInteractor {
    fun getDocumentDetails(
        documentId: String,
        wasIssuerDetailsExpanded: Boolean?
    ): Flow<DocumentDetailsInteractorPartialState>

    fun deleteDocument(
        documentId: String
    ): Flow<DocumentDetailsInteractorDeleteDocumentPartialState>

    fun storeBookmark(
        documentId: String
    ): Flow<DocumentDetailsInteractorStoreBookmarkPartialState>

    fun deleteBookmark(
        documentId: String
    ): Flow<DocumentDetailsInteractorDeleteBookmarkPartialState>

    fun reIssueDocument(
        documentId: String,
        issuerId: String
    ): Flow<DocumentDetailsInteractorIssuancePartialState>

    fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult
    )

    fun resumeOpenId4VciWithAuthorization(uri: String)
}
