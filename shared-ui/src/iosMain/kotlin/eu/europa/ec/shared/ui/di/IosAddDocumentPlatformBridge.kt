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
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.corelogic.model.ScopedDocumentDomain
import eu.europa.ec.corelogic.model.toDocumentIdentifier
import eu.europa.ec.issuancefeature.interactor.AddDocumentPlatformBridge
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.wallet.multipaz.IosOfferableCredentialsReader
import eu.europa.ec.shared.wallet.multipaz.OfferableCredentialsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * iOS's [AddDocumentPlatformBridge]: a real catalogue, and an issuance step that is still missing.
 *
 * [getScopedDocuments] now answers for real — [IosOfferableCredentialsReader] asks each configured issuer
 * what it can issue, the same `.well-known` metadata Android reads through wallet-core. Only the *decision*
 * layer above it is shared, so an issuer's configurations are grouped, PID-folded and sorted by exactly
 * the code Android runs.
 *
 * [issueDocuments] is what remains. The protocol works — the issuance spike drives multipaz's
 * `ProvisioningModel` through PAR, authorization and credential issuance against
 * `dev.issuer-backend.eudiw.dev`, and the documents it produces are visible to our reader — but driving it
 * from a screen needs the authorization browser, the Secure Enclave key policy and the deferred cases
 * wired in, so this still refuses rather than half-starting an issuance.
 */
internal class IosAddDocumentPlatformBridge(
    private val offerableCredentials: IosOfferableCredentialsReader,
) : AddDocumentPlatformBridge {

    override fun localeTag(): String = NSLocale.currentLocale.languageCode

    override suspend fun getScopedDocuments(locale: String): FetchScopedDocumentsPartialState =
        when (val result = offerableCredentials.read(locale)) {
            is OfferableCredentialsResult.Failure ->
                FetchScopedDocumentsPartialState.Failure(errorMessage = result.message)

            is OfferableCredentialsResult.Success -> FetchScopedDocumentsPartialState.Success(
                documents = result.credentials.map { credential ->
                    ScopedDocumentDomain(
                        name = credential.name,
                        configurationId = credential.configurationId,
                        credentialIssuerId = credential.issuerUrl,
                        credentialIssuerOrder = credential.issuerOrder,
                        formatType = credential.formatType,
                        // Which format types are PIDs is app knowledge, not the issuer's: the reader
                        // reports the type, and this decides — as Android's controller does.
                        isPid = credential.formatType?.toDocumentIdentifier()
                            .let { it == DocumentIdentifier.MdocPid || it == DocumentIdentifier.SdJwtPid },
                    )
                }
            )
        }

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
        const val NOT_AVAILABLE_ON_IOS = "Issuing a document is not available on iOS yet."
    }
}
