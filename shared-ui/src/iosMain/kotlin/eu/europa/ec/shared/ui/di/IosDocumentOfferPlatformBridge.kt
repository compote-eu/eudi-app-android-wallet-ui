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
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.issuancefeature.interactor.DocumentOfferPlatformBridge
import eu.europa.ec.issuancefeature.interactor.PlatformOfferResolution
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * iOS's [DocumentOfferPlatformBridge] — not yet implemented, and specific about why.
 *
 * Credential offers reach a wallet as `openid-credential-offer://` links, which iOS would receive through
 * the app delegate exactly as it already receives authorization redirects. multipaz can then act on one:
 * `ProvisioningModel` accepts an offer URI, and the issuance spike proves the rest of that path works.
 * Two pieces are missing before this can be honest — the deep link is not routed to this screen, and
 * multipaz's resolve step does not surface the transaction-code spec that [PlatformOfferResolution]
 * reports — so it refuses rather than resolving an offer it could not then issue.
 *
 * Note what is *not* missing: every rule about an offer — acceptable code lengths, the PID requirement,
 * how outcomes map to the screen — is in the shared interactor and will apply unchanged.
 */
internal class IosDocumentOfferPlatformBridge : DocumentOfferPlatformBridge {

    override fun localeTag(): String = NSLocale.currentLocale.languageCode

    /** iOS has no `ConfigLogic` yet; false is the permissive answer, and nothing here can issue anyway. */
    override val forcePidActivation: Boolean = false

    override suspend fun resolveOffer(offerUri: String, locale: String): PlatformOfferResolution =
        PlatformOfferResolution.Failure(errorMessage = NOT_AVAILABLE_ON_IOS)

    override fun issueResolvedOffer(
        offerUri: String,
        txCode: String?,
    ): Flow<IssueDocumentsPartialState> = flow {
        emit(IssueDocumentsPartialState.Failure(errorMessage = NOT_AVAILABLE_ON_IOS))
    }

    /**
     * Nothing to raise: multipaz's `SecureEnclaveSecureArea` presents the LocalAuthentication dialog
     * itself when a key is used.
     */
    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) = resultHandler.onAuthenticationFailure()

    /** Inert: iOS redirects are consumed by the provisioning flow that asked for them. */
    override fun resumeOpenId4VciWithAuthorization(uri: String) = Unit

    private companion object {
        const val NOT_AVAILABLE_ON_IOS = "Credential offers are not available on iOS yet."
    }
}
