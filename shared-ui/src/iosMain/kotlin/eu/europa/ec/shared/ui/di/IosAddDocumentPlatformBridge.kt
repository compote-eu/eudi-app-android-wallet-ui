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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.corelogic.controller.FetchScopedDocumentsPartialState
import eu.europa.ec.corelogic.controller.IssuanceMethod
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.issuancefeature.interactor.AddDocumentPlatformBridge
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * iOS's [AddDocumentPlatformBridge] — and, for now, an honest refusal.
 *
 * **Why not implemented, when iOS demonstrably can issue.** The issuance spike drives multipaz's
 * `ProvisioningModel` end to end against `dev.issuer-backend.eudiw.dev`: real PAR, real authorization
 * redirect, real credentials, visible to our reader. What is missing is not the protocol but the
 * *catalogue*: [getScopedDocuments] answers "which issuers, and what does each offer", and on Android
 * that comes from `WalletCoreConfig`'s list of VCI configurations, of which iOS has no equivalent — the
 * spike hardcoded its two issuer URLs. Inventing one here would put configuration in a DI file.
 *
 * So this reports a failure the screen already knows how to render, rather than offering documents it
 * cannot then issue. Replacing it is the next step for iOS issuance, and the shared interactor above it
 * needs no change when that happens.
 */
internal class IosAddDocumentPlatformBridge : AddDocumentPlatformBridge {

    override fun localeTag(): String = NSLocale.currentLocale.languageCode

    override suspend fun getScopedDocuments(locale: String): FetchScopedDocumentsPartialState =
        FetchScopedDocumentsPartialState.Failure(errorMessage = NOT_AVAILABLE_ON_IOS)

    override fun issueDocuments(
        issuanceMethod: IssuanceMethod,
        configIds: List<String>,
        issuerId: String,
    ): Flow<IssueDocumentsPartialState> = flow {
        emit(IssueDocumentsPartialState.Failure(errorMessage = NOT_AVAILABLE_ON_IOS))
    }

    /**
     * Nothing to raise: multipaz's `SecureEnclaveSecureArea` presents the LocalAuthentication dialog
     * itself when a key is used, so there is no separate prompt — and no issuance here asks for one yet.
     */
    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) = resultHandler.onAuthenticationFailure()

    /**
     * Deliberately inert. iOS's redirects do arrive — `IosAuthorizationRedirects` receives them from the
     * app delegate — but they are consumed by the provisioning flow that asked for them, not pushed into
     * a wallet-core-style resume.
     */
    override fun resumeOpenId4VciWithAuthorization(uri: String) = Unit

    private companion object {
        const val NOT_AVAILABLE_ON_IOS = "Adding a document is not available on iOS yet."
    }
}
