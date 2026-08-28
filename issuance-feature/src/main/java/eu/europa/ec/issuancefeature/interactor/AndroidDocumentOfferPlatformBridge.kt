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

package eu.europa.ec.issuancefeature.interactor

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.interactor.DeviceAuthenticationInteractor
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.corelogic.controller.ResolveDocumentOfferPartialState
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.extension.documentIdentifier
import eu.europa.ec.corelogic.extension.getIssuerLogo
import eu.europa.ec.corelogic.extension.getIssuerName
import eu.europa.ec.corelogic.extension.getName
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.eudi.openid4vci.TxCodeInputMode
import eu.europa.ec.eudi.wallet.issue.openid4vci.Offer
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.Locale

/**
 * Android's offer handling: wallet-core resolves and issues, and this narrows its `Offer` to the neutral
 * [PlatformOfferResolution] the shared interactor reasons about.
 *
 * **It also holds the resolved offers**, which is why this is a `@Scoped` singleton for the duration of a
 * credential-offer flow rather than a factory: wallet-core needs the very `Offer` object it produced in
 * order to issue it, and that object cannot be handed upwards. The map used to live in
 * `DocumentOfferInteractorImpl` for the same reason and moved down here with it.
 */
class AndroidDocumentOfferPlatformBridge(
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
    private val deviceAuthenticationInteractor: DeviceAuthenticationInteractor,
    private val resourceProvider: ResourceProvider,
) : DocumentOfferPlatformBridge {

    private val resolvedOffers: MutableMap<String, Offer> = mutableMapOf()

    override fun localeTag(): String = resourceProvider.getLocale().toLanguageTag()

    override suspend fun resolveOffer(
        offerUri: String,
        locale: String,
    ): PlatformOfferResolution {
        val userLocale = Locale.forLanguageTag(locale)

        // `first()`: the controller's resolve is a callbackFlow that sends one state and then stays open
        // on `awaitClose`, so taking the first value is what ends the fetch instead of leaking it.
        return when (
            val response = walletCoreDocumentsController.resolveDocumentOffer(offerUri).first()
        ) {
            is ResolveDocumentOfferPartialState.Failure ->
                PlatformOfferResolution.Failure(errorMessage = response.errorMessage)

            is ResolveDocumentOfferPartialState.IssuerNotTrusted ->
                PlatformOfferResolution.IssuerNotTrusted(reason = response.reason)

            is ResolveDocumentOfferPartialState.Success -> {
                val offer = response.offer
                resolvedOffers[offerUri] = offer

                if (offer.offeredDocuments.isEmpty()) {
                    PlatformOfferResolution.NoDocuments(
                        issuerName = offer.getIssuerName(userLocale),
                        issuerLogoUri = offer.getIssuerLogo(userLocale)?.toString(),
                        issuerRegistration = response.issuerRegistration,
                    )
                } else {
                    PlatformOfferResolution.Success(
                        documentNames = offer.offeredDocuments.map {
                            it.getName(userLocale).orEmpty()
                        },
                        issuerName = offer.getIssuerName(userLocale),
                        issuerLogoUri = offer.getIssuerLogo(userLocale)?.toString(),
                        containsPid = offer.offeredDocuments.any { offeredDocument ->
                            val id = offeredDocument.documentIdentifier
                            id == DocumentIdentifier.MdocPid || id == DocumentIdentifier.SdJwtPid
                        },
                        txCodeLength = offer.txCodeSpec?.length,
                        // A text code is one this wallet cannot ask for; the shared side decides what to
                        // do about that.
                        txCodeIsNumeric = offer.txCodeSpec?.inputMode != TxCodeInputMode.TEXT,
                        issuerRegistration = response.issuerRegistration,
                    )
                }
            }
        }
    }

    override fun issueResolvedOffer(
        offerUri: String,
        txCode: String?,
    ): Flow<IssueDocumentsPartialState> = flow {
        val offer = resolvedOffers[offerUri]
        if (offer == null) {
            emit(
                IssueDocumentsPartialState.Failure(
                    errorMessage = resourceProvider.genericErrorMessage()
                )
            )
            return@flow
        }
        walletCoreDocumentsController.issueDocumentsByOffer(
            offer = offer,
            txCode = txCode,
        ).collect { emit(it) }
    }

    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) {
        when (deviceAuthenticationInteractor.getBiometricsAvailability()) {
            is BiometricsAvailability.CanAuthenticate -> {
                deviceAuthenticationInteractor.authenticateWithBiometrics(
                    context = context,
                    crypto = crypto,
                    notifyOnAuthenticationFailure = notifyOnAuthenticationFailure,
                    resultHandler = resultHandler
                )
            }

            is BiometricsAvailability.NonEnrolled -> {
                deviceAuthenticationInteractor.launchBiometricSystemScreen()
            }

            is BiometricsAvailability.Failure -> {
                resultHandler.onAuthenticationFailure()
            }
        }
    }

    override fun resumeOpenId4VciWithAuthorization(uri: String) {
        walletCoreDocumentsController.resumeOpenId4VciWithAuthorization(uri)
    }
}
