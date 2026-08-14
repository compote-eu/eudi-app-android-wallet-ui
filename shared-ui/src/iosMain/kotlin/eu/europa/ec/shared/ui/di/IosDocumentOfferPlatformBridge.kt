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
import eu.europa.ec.shared.wallet.multipaz.IosCredentialIssuer
import eu.europa.ec.shared.wallet.multipaz.IosCredentialOffer
import eu.europa.ec.shared.wallet.multipaz.IosCredentialOfferReader
import eu.europa.ec.shared.wallet.multipaz.IosIssuanceProgress
import eu.europa.ec.shared.wallet.multipaz.IosOfferResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * iOS's [DocumentOfferPlatformBridge]: offers are read and issued through multipaz.
 *
 * **It holds the resolved offers**, as the contract requires, though for a different reason than Android:
 * there the cached object is wallet-core's `Offer`, which cannot cross into shared code, while here the
 * offer link alone would be enough to issue from. Keeping the map anyway is what makes
 * [issueResolvedOffer]'s promise true — an offer nobody resolved is refused rather than quietly fetched a
 * second time, which would risk issuing something other than what the user was shown.
 */
internal class IosDocumentOfferPlatformBridge(
    private val offers: IosCredentialOfferReader,
    private val credentialIssuer: IosCredentialIssuer,
) : DocumentOfferPlatformBridge {

    private val resolvedOffers: MutableMap<String, IosCredentialOffer> = mutableMapOf()

    override fun localeTag(): String = NSLocale.currentLocale.languageCode

    /**
     * False, because iOS has no `ConfigLogic` to read it from. The consequence is narrow and worth being
     * clear about: an offer that contains no PID is accepted here even when the wallet holds none, where
     * the Android `dev` flavour would refuse it. The rule itself lives in the shared interactor and starts
     * applying the moment iOS grows a configuration layer.
     */
    override val forcePidActivation: Boolean = false

    override suspend fun resolveOffer(offerUri: String, locale: String): PlatformOfferResolution =
        when (val resolution = offers.resolve(offerUri = offerUri, locale = locale)) {
            is IosOfferResolution.Failure ->
                PlatformOfferResolution.Failure(errorMessage = resolution.message)

            is IosOfferResolution.Resolved -> {
                resolvedOffers[offerUri] = resolution.offer

                if (resolution.documentNames.isEmpty()) {
                    PlatformOfferResolution.NoDocuments(
                        issuerName = resolution.issuerName,
                        issuerLogoUri = resolution.issuerLogoUri,
                    )
                } else {
                    PlatformOfferResolution.Success(
                        documentNames = resolution.documentNames,
                        issuerName = resolution.issuerName,
                        issuerLogoUri = resolution.issuerLogoUri,
                        containsPid = resolution.containsPid,
                        txCodeLength = resolution.offer.txCodeLength,
                        txCodeIsNumeric = resolution.offer.txCodeIsNumeric,
                    )
                }
            }
        }

    override fun issueResolvedOffer(
        offerUri: String,
        txCode: String?,
    ): Flow<IssueDocumentsPartialState> {
        val offer = resolvedOffers[offerUri]
            ?: return flow {
                emit(IssueDocumentsPartialState.Failure(errorMessage = OFFER_NOT_RESOLVED))
            }

        return credentialIssuer.issueOffer(offerUri = offer.offerUri, txCode = txCode).map { progress ->
            when (progress) {
                is IosIssuanceProgress.Failure ->
                    IssueDocumentsPartialState.Failure(errorMessage = progress.message)

                is IosIssuanceProgress.Issued ->
                    IssueDocumentsPartialState.Success(documentIds = progress.documentIds)
            }
        }
    }

    /**
     * Nothing to raise: multipaz's `SecureEnclaveSecureArea` presents the LocalAuthentication dialog
     * itself when a key is used, so there is no separate prompt — and an offer's own secret is the
     * transaction code, which the offer-code screen collects rather than this.
     */
    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) = resultHandler.onAuthenticationFailure()

    /**
     * Inert, because the issuer consumes its own redirect: `IosCredentialIssuer` awaits
     * `IosAuthorizationRedirects` for the flow it started, rather than being pushed a URL from outside as
     * wallet-core's resume does.
     */
    override fun resumeOpenId4VciWithAuthorization(uri: String) = Unit

    private companion object {
        const val OFFER_NOT_RESOLVED = "This offer was not read; open it again."
    }
}
