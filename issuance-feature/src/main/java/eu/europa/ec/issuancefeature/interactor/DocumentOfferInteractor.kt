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

import android.content.Context
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.businesslogic.util.safeLet
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.commonfeature.interactor.DeviceAuthenticationInteractor
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.corelogic.controller.ResolveDocumentOfferPartialState
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.corelogic.extension.documentIdentifier
import eu.europa.ec.corelogic.extension.getIssuerLogo
import eu.europa.ec.corelogic.extension.getIssuerName
import eu.europa.ec.corelogic.extension.getName
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.eudi.openid4vci.TxCodeInputMode
import eu.europa.ec.eudi.wallet.document.DocumentId
import eu.europa.ec.eudi.wallet.issue.openid4vci.Offer
import eu.europa.ec.issuancefeature.ui.offer.model.DocumentOfferUi
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.resourceslogic.theme.values.ThemeColors
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.SuccessRoute
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.utils.PERCENTAGE_25
import eu.europa.ec.uilogic.config.ConfigNavigation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.net.URI
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.issuance_document_offer_deferred_success_description
import eu.europa.ec.shared.resources.issuance_document_offer_deferred_success_primary_button_text
import eu.europa.ec.shared.resources.issuance_document_offer_deferred_success_text
import eu.europa.ec.shared.resources.issuance_document_offer_error_invalid_txcode_format
import eu.europa.ec.shared.resources.issuance_document_offer_error_missing_pid_text

sealed class ResolveDocumentOfferInteractorPartialState {
    data class Success(
        val documents: List<DocumentOfferUi>,
        val issuerName: String,
        val issuerLogo: URI?,
        val txCodeLength: Int?
    ) : ResolveDocumentOfferInteractorPartialState()

    data class NoDocument(
        val issuerName: String,
        val issuerLogo: URI?,
    ) : ResolveDocumentOfferInteractorPartialState()

    data class Failure(val errorMessage: String) : ResolveDocumentOfferInteractorPartialState()

    data object IssuerNotTrusted : ResolveDocumentOfferInteractorPartialState()
}

sealed class IssueDocumentsInteractorPartialState {
    data class Success(
        val documentIds: List<DocumentId>,
    ) : IssueDocumentsInteractorPartialState()

    data class PartialSuccessWithUntrustedIssuer(
        val issuedDocumentIds: List<DocumentId>,
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

    val credentialOffers: MutableMap<String, Offer>

    fun resolveDocumentOffer(offerUri: String): Flow<ResolveDocumentOfferInteractorPartialState>

    fun issueDocuments(
        offerUri: String,
        issuerName: String,
        navigation: ConfigNavigation,
        txCode: SecurePin? = null
    ): Flow<IssueDocumentsInteractorPartialState>

    fun handleUserAuthentication(
        context: Context,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult
    )

    fun resumeOpenId4VciWithAuthorization(uri: String)
}

class DocumentOfferInteractorImpl(
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
    private val walletEngine: WalletEngine,
    private val deviceAuthenticationInteractor: DeviceAuthenticationInteractor,
    private val resourceProvider: ResourceProvider,
    private val configLogic: ConfigLogic
) : DocumentOfferInteractor {

    private val genericErrorMsg
        get() = resourceProvider.genericErrorMessage()

    override val credentialOffers: MutableMap<String, Offer> = mutableMapOf()

    override fun resolveDocumentOffer(offerUri: String): Flow<ResolveDocumentOfferInteractorPartialState> =
        flow {
            val userLocale = resourceProvider.getLocale()
            walletCoreDocumentsController.resolveDocumentOffer(
                offerUri = offerUri
            ).map { response ->
                when (response) {
                    is ResolveDocumentOfferPartialState.Failure -> {
                        ResolveDocumentOfferInteractorPartialState.Failure(errorMessage = response.errorMessage)
                    }

                    is ResolveDocumentOfferPartialState.IssuerNotTrusted -> {
                        ResolveDocumentOfferInteractorPartialState.IssuerNotTrusted
                    }

                    is ResolveDocumentOfferPartialState.Success -> {

                        credentialOffers[offerUri] = response.offer

                        val offerHasNoDocuments = response.offer.offeredDocuments.isEmpty()
                        if (offerHasNoDocuments) {
                            ResolveDocumentOfferInteractorPartialState.NoDocument(
                                issuerName = response.offer.getIssuerName(userLocale),
                                issuerLogo = response.offer.getIssuerLogo(userLocale),
                            )
                        } else {

                            val codeMinLength = 4
                            val codeMaxLength = 6

                            safeLet(
                                response.offer.txCodeSpec?.inputMode,
                                response.offer.txCodeSpec?.length
                            ) { inputMode, length ->

                                if ((length !in codeMinLength..codeMaxLength) || inputMode == TxCodeInputMode.TEXT) {
                                    return@map ResolveDocumentOfferInteractorPartialState.Failure(
                                        errorMessage = resourceProvider.getString(
                                            Res.string.issuance_document_offer_error_invalid_txcode_format,
                                            codeMinLength,
                                            codeMaxLength
                                        )
                                    )
                                }
                            }

                            val hasMainPid =
                                walletEngine.getMainPidDocument() != null

                            val hasPidInOffer =
                                response.offer.offeredDocuments.any { offeredDocument ->
                                    val id = offeredDocument.documentIdentifier
                                    id == DocumentIdentifier.MdocPid || id == DocumentIdentifier.SdJwtPid
                                }

                            if (hasMainPid || hasPidInOffer || !configLogic.forcePidActivation) {

                                ResolveDocumentOfferInteractorPartialState.Success(
                                    documents = response.offer.offeredDocuments.map { offeredDocument ->
                                        DocumentOfferUi(
                                            title = offeredDocument.getName(userLocale).orEmpty(),
                                        )
                                    },
                                    issuerName = response.offer.getIssuerName(userLocale),
                                    issuerLogo = response.offer.getIssuerLogo(userLocale),
                                    txCodeLength = response.offer.txCodeSpec?.length
                                )
                            } else {
                                ResolveDocumentOfferInteractorPartialState.Failure(
                                    errorMessage = resourceProvider.getString(
                                        Res.string.issuance_document_offer_error_missing_pid_text
                                    )
                                )
                            }
                        }
                    }
                }
            }.collect {
                emit(it)
            }
        }.safeAsync {
            ResolveDocumentOfferInteractorPartialState.Failure(
                errorMessage = it.localizedMessage ?: genericErrorMsg
            )
        }

    override fun issueDocuments(
        offerUri: String,
        issuerName: String,
        navigation: ConfigNavigation,
        txCode: SecurePin?
    ): Flow<IssueDocumentsInteractorPartialState> =
        flow {
            credentialOffers[offerUri]?.let { offer ->
                walletCoreDocumentsController.issueDocumentsByOffer(
                    offer = offer,
                    txCode = txCode?.getAndClearAsString()
                ).map { response ->
                    when (response) {
                        is IssueDocumentsPartialState.Failure -> {
                            IssueDocumentsInteractorPartialState.Failure(errorMessage = response.errorMessage)
                        }

                        is IssueDocumentsPartialState.IssuerNotTrusted -> {
                            IssueDocumentsInteractorPartialState.IssuerNotTrusted
                        }

                        is IssueDocumentsPartialState.PartialSuccess -> {
                            IssueDocumentsInteractorPartialState.Success(
                                documentIds = response.documentIds
                            )
                        }

                        is IssueDocumentsPartialState.PartialSuccessWithUntrustedIssuer -> {
                            IssueDocumentsInteractorPartialState.PartialSuccessWithUntrustedIssuer(
                                issuedDocumentIds = response.issuedDocumentIds
                            )
                        }

                        is IssueDocumentsPartialState.Success -> {
                            IssueDocumentsInteractorPartialState.Success(
                                documentIds = response.documentIds
                            )
                        }

                        is IssueDocumentsPartialState.UserAuthRequired -> {
                            IssueDocumentsInteractorPartialState.UserAuthRequired(
                                crypto = response.crypto,
                                resultHandler = response.resultHandler
                            )
                        }

                        is IssueDocumentsPartialState.DeferredSuccess -> {
                            IssueDocumentsInteractorPartialState.DeferredSuccess(
                                successRoute = buildGenericSuccessRouteForDeferred(
                                    description = resourceProvider.getString(
                                        Res.string.issuance_document_offer_deferred_success_description,
                                        issuerName
                                    ),
                                    navigation = navigation
                                )
                            )
                        }
                    }
                }.collect {
                    emit(it)
                }
            } ?: emit(
                IssueDocumentsInteractorPartialState.Failure(
                    errorMessage = genericErrorMsg
                )
            )
        }.safeAsync {
            IssueDocumentsInteractorPartialState.Failure(
                errorMessage = it.localizedMessage ?: genericErrorMsg
            )
        }

    override fun handleUserAuthentication(
        context: Context,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult
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

    private fun buildGenericSuccessRouteForDeferred(
        description: String,
        navigation: ConfigNavigation
    ): AppRoute {
        return SuccessRoute(getDeferredSuccessConfig(description, navigation))
    }

    private fun getDeferredSuccessConfig(
        description: String,
        navigation: ConfigNavigation
    ): SuccessUIConfig {
        val (textElementsConfig, imageConfig, buttonText) = Triple(
            first = SuccessUIConfig.TextElementsConfig(
                text = resourceProvider.getString(Res.string.issuance_document_offer_deferred_success_text),
                description = description,
                color = ColorKey.Pending
            ),
            second = SuccessUIConfig.ImageConfig(
                type = SuccessUIConfig.ImageConfig.Type.Drawable(
                    icon = AppIcons.InProgress,
                ),
                tint = ColorKey.Primary,
                screenPercentageSize = PERCENTAGE_25,
            ),
            third = resourceProvider.getString(Res.string.issuance_document_offer_deferred_success_primary_button_text)
        )

        return SuccessUIConfig(
            textElementsConfig = textElementsConfig,
            imageConfig = imageConfig,
            buttonConfig = listOf(
                SuccessUIConfig.ButtonConfig(
                    text = buttonText,
                    style = SuccessUIConfig.ButtonConfig.Style.PRIMARY,
                    navigation = navigation
                )
            ),
            onBackScreenToNavigate = navigation,
        )
    }
}