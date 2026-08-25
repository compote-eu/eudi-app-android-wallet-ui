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

// Phase 2: the *contract* is KMP — `DocumentId` was only ever a typealias for String, `IssuanceMethod`
// moved out of the wallet-core controller file, and the auth handle is `PlatformContext` — so it lives
// in commonMain next to `AddDocumentViewModel`. `AddDocumentInteractorImpl` stays in
// :issuance-feature. Package unchanged.
package eu.europa.ec.issuancefeature.interactor

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.corelogic.controller.IssuanceMethod
import eu.europa.ec.corelogic.model.UntrustedIssuerReasonDomain
import eu.europa.ec.issuancefeature.ui.add.model.AddDocumentUi
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow

sealed class AddDocumentInteractorIssueDocumentsPartialState {
    data class Success(val documentIds: List<String>) :
        AddDocumentInteractorIssueDocumentsPartialState()

    data object DeferredSuccess : AddDocumentInteractorIssueDocumentsPartialState()

    data class Failure(val errorMessage: String) : AddDocumentInteractorIssueDocumentsPartialState()

    data class IssuerNotTrusted(
        val reason: UntrustedIssuerReasonDomain,
    ) : AddDocumentInteractorIssueDocumentsPartialState()

    data class UserAuthRequired(
        val crypto: BiometricCrypto,
        val resultHandler: DeviceAuthenticationResult,
    ) : AddDocumentInteractorIssueDocumentsPartialState()
}

sealed class AddDocumentInteractorScopedPartialState {
    data class Success(val options: List<Pair<String, List<AddDocumentUi>>>) :
        AddDocumentInteractorScopedPartialState()

    data class NoOptions(val errorMsg: String) : AddDocumentInteractorScopedPartialState()
    data class Failure(val error: String) : AddDocumentInteractorScopedPartialState()

    data object NoTrustedIssuers : AddDocumentInteractorScopedPartialState()
}

interface AddDocumentInteractor {
    fun getAddDocumentOption(
        flowType: IssuanceFlowType,
    ): Flow<AddDocumentInteractorScopedPartialState>

    fun issueDocuments(
        issuanceMethod: IssuanceMethod,
        configIds: List<String>,
        issuerId: String
    ): Flow<AddDocumentInteractorIssueDocumentsPartialState>

    fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult
    )

    fun buildGenericSuccessRouteForDeferred(flowType: IssuanceFlowType): AppRoute

    fun resumeOpenId4VciWithAuthorization(uri: String)
}
