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

// Lifted out of WalletCoreDocumentsController.kt, like `IssuanceMethod` before them, so the shared
// issuance interactors can name what a platform reports back to them. Nothing here is wallet-core: the
// ids were `DocumentId`, which is `typealias DocumentId = String` in the Android-only document manager,
// and the two auth handles already live in :shared-logic. Package unchanged, so no Android call site
// moves.
package eu.europa.ec.corelogic.controller

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.corelogic.model.ScopedDocumentDomain
import eu.europa.ec.corelogic.model.UntrustedIssuerReasonDomain

/** What an issuer offers, or why it could not be asked. */
sealed class FetchScopedDocumentsPartialState {
    data class Success(val documents: List<ScopedDocumentDomain>) :
        FetchScopedDocumentsPartialState()

    data class Failure(val errorMessage: String) : FetchScopedDocumentsPartialState()

    /**
     * Every configured issuer was refused on trust grounds, so there is nothing to offer — as
     * distinct from [Failure], which is a fault the user can retry.
     */
    data object NoTrustedIssuers : FetchScopedDocumentsPartialState()
}

/** How an issuance attempt ended, including the two ways it can end without documents. */
sealed class IssueDocumentsPartialState {
    data class Success(val documentIds: List<String>) : IssueDocumentsPartialState()

    data class DeferredSuccess(val deferredDocuments: Map<String, FormatType>) :
        IssueDocumentsPartialState()

    data class PartialSuccess(
        val documentIds: List<String>,
        val nonIssuedDocuments: Map<String, String>,
    ) : IssueDocumentsPartialState()

    data class PartialSuccessWithUntrustedIssuer(
        val issuedDocumentIds: List<String>,
        val untrustedDocuments: Map<FormatType, String>,
    ) : IssueDocumentsPartialState()

    data class Failure(val errorMessage: String) : IssueDocumentsPartialState()

    /** @property reason which of the two trust layers refused — the screens word it differently. */
    data class IssuerNotTrusted(
        val reason: UntrustedIssuerReasonDomain,
    ) : IssueDocumentsPartialState()

    /**
     * The issuer's keys need the user present. The platform raises the prompt (see
     * `AddDocumentPlatformBridge.handleUserAuth`) and issuance continues through [resultHandler].
     */
    data class UserAuthRequired(
        val crypto: BiometricCrypto,
        val resultHandler: DeviceAuthenticationResult,
    ) : IssueDocumentsPartialState()
}
