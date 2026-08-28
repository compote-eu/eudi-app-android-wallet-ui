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

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.businesslogic.extension.ifEmptyOrNull
import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.issuance_document_offer_relying_party_default_name
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.shared.wallet.config.SharedAppConfig
import eu.europa.ec.issuancefeature.ui.offer.model.DocumentOfferUi
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.SuccessRoute
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.utils.PERCENTAGE_25
import eu.europa.ec.uilogic.config.ConfigNavigation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.issuance_document_offer_deferred_success_description
import eu.europa.ec.shared.resources.issuance_document_offer_deferred_success_primary_button_text
import eu.europa.ec.shared.resources.issuance_document_offer_deferred_success_text
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.issuance_document_offer_error_invalid_txcode_format
import eu.europa.ec.shared.resources.issuance_document_offer_error_missing_pid_text

/**
 * What this wallet makes of a credential offer.
 *
 * Everything that *decides* is here: which transaction-code shapes are acceptable, whether an offer may
 * be accepted by a wallet holding no PID, how an issuance outcome maps to what the screen shows, and what
 * a deferred issuance navigates to. What is not here is the offer itself — see
 * [DocumentOfferPlatformBridge], which keeps the resolved offer because its type cannot cross into shared
 * code.
 */
class DocumentOfferInteractorImpl(
    private val strings: StringCatalog,
    private val walletEngine: WalletEngine,
    private val platform: DocumentOfferPlatformBridge,
    private val appConfig: SharedAppConfig,
) : DocumentOfferInteractor {

    private val genericErrorMsg
        get() = strings[Res.string.generic_error_message]

    override fun resolveDocumentOffer(offerUri: String): Flow<ResolveDocumentOfferInteractorPartialState> =
        flow {
            // An offer need not name its issuer, but every consumer of the name formats it into
            // user-facing copy ("... issued by %1$s", "%1$s requires verification"). Defaulting it
            // here, in the layer that already holds resolved strings, is what lets
            // DocumentOfferViewModel carry the name as UiText and drop its resolver.
            val defaultIssuerName =
                strings[Res.string.issuance_document_offer_relying_party_default_name]

            emit(
                when (val resolution = platform.resolveOffer(offerUri, platform.localeTag())) {
                    is PlatformOfferResolution.Failure ->
                        ResolveDocumentOfferInteractorPartialState.Failure(resolution.errorMessage)

                    is PlatformOfferResolution.IssuerNotTrusted ->
                        ResolveDocumentOfferInteractorPartialState.IssuerNotTrusted(
                            reason = resolution.reason
                        )

                    is PlatformOfferResolution.NoDocuments ->
                        ResolveDocumentOfferInteractorPartialState.NoDocument(
                            issuerName = resolution.issuerName.ifEmptyOrNull(defaultIssuerName),
                            issuerLogo = resolution.issuerLogoUri,
                            issuerRegistration = resolution.issuerRegistration,
                        )

                    is PlatformOfferResolution.Success -> resolution.toState(defaultIssuerName)
                }
            )
        }.safeAsync {
            ResolveDocumentOfferInteractorPartialState.Failure(
                errorMessage = it.message ?: genericErrorMsg
            )
        }

    /**
     * The two reasons this wallet turns down an offer it could technically accept: a transaction code it
     * cannot ask for, and an offer with no PID in a build that requires one first.
     */
    private suspend fun PlatformOfferResolution.Success.toState(
        defaultIssuerName: String,
    ): ResolveDocumentOfferInteractorPartialState {
        val codeMinLength = 4
        val codeMaxLength = 6

        txCodeLength?.let { length ->
            if (length !in codeMinLength..codeMaxLength || !txCodeIsNumeric) {
                return ResolveDocumentOfferInteractorPartialState.Failure(
                    errorMessage = strings.get(
                        Res.string.issuance_document_offer_error_invalid_txcode_format,
                        codeMinLength,
                        codeMaxLength,
                    )
                )
            }
        }

        // A build that requires a PID first refuses an offer that neither is one nor follows one.
        val walletHasPid = walletEngine.getMainPidDocument() != null
        if (appConfig.forcePidActivation && !walletHasPid && !containsPid) {
            return ResolveDocumentOfferInteractorPartialState.Failure(
                errorMessage = strings[Res.string.issuance_document_offer_error_missing_pid_text]
            )
        }

        return ResolveDocumentOfferInteractorPartialState.Success(
            documents = documentNames.map { DocumentOfferUi(title = it) },
            issuerName = issuerName.ifEmptyOrNull(defaultIssuerName),
            issuerLogo = issuerLogoUri,
            txCodeLength = txCodeLength,
            issuerRegistration = issuerRegistration,
        )
    }

    override fun issueDocuments(
        offerUri: String,
        issuerName: String,
        navigation: ConfigNavigation,
        txCode: SecurePin?
    ): Flow<IssueDocumentsInteractorPartialState> =
        flow {
            platform.issueResolvedOffer(
                offerUri = offerUri,
                txCode = txCode?.getAndClearAsString(),
            ).map { response ->
                    when (response) {
                        is IssueDocumentsPartialState.Failure -> {
                            IssueDocumentsInteractorPartialState.Failure(errorMessage = response.errorMessage)
                        }

                        is IssueDocumentsPartialState.IssuerNotTrusted -> {
                            IssueDocumentsInteractorPartialState.IssuerNotTrusted(
                                reason = response.reason
                            )
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
                                    description = UiText.Resource(
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
        }.safeAsync {
            IssueDocumentsInteractorPartialState.Failure(
                errorMessage = it.message ?: genericErrorMsg
            )
        }

    override fun handleUserAuthentication(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult
    ) = platform.handleUserAuth(
        context = context,
        crypto = crypto,
        notifyOnAuthenticationFailure = notifyOnAuthenticationFailure,
        resultHandler = resultHandler,
    )

    override fun resumeOpenId4VciWithAuthorization(uri: String) =
        platform.resumeOpenId4VciWithAuthorization(uri)

    private fun buildGenericSuccessRouteForDeferred(
        description: UiText,
        navigation: ConfigNavigation
    ): AppRoute {
        return SuccessRoute(getDeferredSuccessConfig(description, navigation))
    }

    private fun getDeferredSuccessConfig(
        description: UiText,
        navigation: ConfigNavigation
    ): SuccessUIConfig {
        val (textElementsConfig, imageConfig, buttonText) = Triple(
            first = SuccessUIConfig.TextElementsConfig(
                text = UiText.Resource(Res.string.issuance_document_offer_deferred_success_text),
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
            third = UiText.Resource(Res.string.issuance_document_offer_deferred_success_primary_button_text)
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