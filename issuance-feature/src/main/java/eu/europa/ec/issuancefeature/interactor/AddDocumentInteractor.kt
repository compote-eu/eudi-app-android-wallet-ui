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
import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.commonfeature.interactor.DeviceAuthenticationInteractor
import eu.europa.ec.corelogic.controller.FetchScopedDocumentsPartialState
import eu.europa.ec.corelogic.controller.IssuanceMethod
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.eudi.wallet.document.DocumentId
import eu.europa.ec.issuancefeature.ui.add.model.AddDocumentUi
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.resourceslogic.theme.values.ThemeColors
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.SuccessRoute
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.utils.PERCENTAGE_25
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.issuance_add_document_deferred_success_description
import eu.europa.ec.shared.resources.issuance_add_document_deferred_success_primary_button_text
import eu.europa.ec.shared.resources.issuance_add_document_deferred_success_text
import eu.europa.ec.shared.resources.issuance_add_document_no_options
import eu.europa.ec.shared.resources.issuance_add_document_pid_combined

sealed class AddDocumentInteractorIssueDocumentsPartialState {
    data class Success(val documentIds: List<DocumentId>) :
        AddDocumentInteractorIssueDocumentsPartialState()

    data object DeferredSuccess : AddDocumentInteractorIssueDocumentsPartialState()

    data class Failure(val errorMessage: String) : AddDocumentInteractorIssueDocumentsPartialState()

    data object IssuerNotTrusted : AddDocumentInteractorIssueDocumentsPartialState()

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
        context: Context,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult
    )

    fun buildGenericSuccessRouteForDeferred(flowType: IssuanceFlowType): AppRoute

    fun resumeOpenId4VciWithAuthorization(uri: String)
}

class AddDocumentInteractorImpl(
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
    private val deviceAuthenticationInteractor: DeviceAuthenticationInteractor,
    private val resourceProvider: ResourceProvider,
) : AddDocumentInteractor {

    private val genericErrorMsg
        get() = resourceProvider.genericErrorMessage()

    override fun getAddDocumentOption(
        flowType: IssuanceFlowType,
    ): Flow<AddDocumentInteractorScopedPartialState> =
        flow {
            val state =
                walletCoreDocumentsController.getScopedDocuments(resourceProvider.getLocale())

            when (state) {
                is FetchScopedDocumentsPartialState.Failure -> emit(
                    AddDocumentInteractorScopedPartialState.Failure(
                        error = state.errorMessage
                    )
                )

                is FetchScopedDocumentsPartialState.Success -> {

                    val formatType: FormatType? =
                        (flowType as? IssuanceFlowType.ExtraDocument)?.formatType

                    val options: List<Pair<String, List<AddDocumentUi>>> =
                        state.documents
                            .asSequence()
                            .filter { doc ->
                                (formatType == null || doc.formatType == formatType) &&
                                        (flowType !is IssuanceFlowType.NoDocument || doc.isPid)
                            }
                            .sortedBy { it.credentialIssuerOrder }
                            .groupBy { it.credentialIssuerId }
                            .map { (issuer, docs) ->

                                val (pidDocs, otherDocs) = docs.partition { it.isPid }
                                val pidIds = pidDocs.map { it.configurationId }

                                val combinedPid: List<AddDocumentUi> =
                                    if (pidDocs.isNotEmpty()) {
                                        listOf(
                                            AddDocumentUi(
                                                credentialIssuerId = issuer,
                                                configurationIds = pidIds,
                                                itemData = ListItemDataUi(
                                                    itemId = "${issuer}_${pidIds.joinToString(",")}",
                                                    mainContentData = ListItemMainContentDataUi.Text(
                                                        text = resourceProvider.getString(
                                                            Res.string.issuance_add_document_pid_combined
                                                        )
                                                    ),
                                                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                                                        iconData = AppIcons.Add
                                                    )
                                                )
                                            )
                                        )
                                    } else {
                                        emptyList()
                                    }

                                val mappedOthers: List<AddDocumentUi> =
                                    otherDocs.map { doc ->
                                        AddDocumentUi(
                                            credentialIssuerId = issuer,
                                            configurationIds = listOf(doc.configurationId),
                                            itemData = ListItemDataUi(
                                                itemId = doc.configurationId,
                                                mainContentData = ListItemMainContentDataUi.Text(
                                                    text = doc.name
                                                ),
                                                trailingContentData = ListItemTrailingContentDataUi.Icon(
                                                    iconData = AppIcons.Add
                                                )
                                            )
                                        )
                                    }

                                val items = (combinedPid + mappedOthers)
                                    .sortedBy {
                                        (it.itemData.mainContentData as ListItemMainContentDataUi.Text)
                                            .text
                                            .lowercase()
                                    }
                                issuer to items
                            }

                    if (options.isEmpty()) {
                        emit(
                            AddDocumentInteractorScopedPartialState.NoOptions(
                                errorMsg = resourceProvider.getString(Res.string.issuance_add_document_no_options)
                            )
                        )
                    } else {
                        emit(
                            AddDocumentInteractorScopedPartialState.Success(
                                options = options
                            )
                        )
                    }
                }
            }
        }.safeAsync {
            AddDocumentInteractorScopedPartialState.Failure(
                error = it.localizedMessage ?: genericErrorMsg
            )
        }

    override fun issueDocuments(
        issuanceMethod: IssuanceMethod,
        configIds: List<String>,
        issuerId: String
    ): Flow<AddDocumentInteractorIssueDocumentsPartialState> = flow {

        walletCoreDocumentsController.issueDocuments(
            issuanceMethod = issuanceMethod,
            configIds = configIds,
            issuerId = issuerId
        ).collect { state ->

            val successIds: MutableList<String> = mutableListOf()
            var isDeferred = false
            var issuerNotTrusted = false
            var error: String? = null
            var authenticationData: Pair<BiometricCrypto, DeviceAuthenticationResult>? = null

            when (state) {
                is IssueDocumentsPartialState.DeferredSuccess -> {
                    isDeferred = true
                }

                is IssueDocumentsPartialState.Failure -> {
                    error = state.errorMessage
                }

                is IssueDocumentsPartialState.IssuerNotTrusted -> {
                    issuerNotTrusted = true
                }

                is IssueDocumentsPartialState.PartialSuccess -> {
                    successIds.addAll(state.documentIds)
                }

                is IssueDocumentsPartialState.PartialSuccessWithUntrustedIssuer -> {
                    successIds.addAll(state.issuedDocumentIds)
                }

                is IssueDocumentsPartialState.Success -> {
                    successIds.addAll(state.documentIds)
                }

                is IssueDocumentsPartialState.UserAuthRequired -> {
                    authenticationData = state.crypto to state.resultHandler
                }
            }

            val state = if (issuerNotTrusted) {
                AddDocumentInteractorIssueDocumentsPartialState.IssuerNotTrusted
            } else if (isDeferred) {
                AddDocumentInteractorIssueDocumentsPartialState.DeferredSuccess
            } else if (successIds.isNotEmpty()) {
                AddDocumentInteractorIssueDocumentsPartialState.Success(successIds)
            } else if (error != null) {
                AddDocumentInteractorIssueDocumentsPartialState.Failure(error)
            } else if (authenticationData != null) {
                AddDocumentInteractorIssueDocumentsPartialState.UserAuthRequired(
                    authenticationData.first,
                    authenticationData.second
                )
            } else {
                AddDocumentInteractorIssueDocumentsPartialState.Failure(genericErrorMsg)
            }

            emit(state)
        }
    }.safeAsync {
        AddDocumentInteractorIssueDocumentsPartialState.Failure(
            errorMessage = it.localizedMessage ?: genericErrorMsg
        )
    }

    override fun handleUserAuth(
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

    override fun buildGenericSuccessRouteForDeferred(flowType: IssuanceFlowType): AppRoute {
        val navigation = when (flowType) {
            is IssuanceFlowType.NoDocument -> ConfigNavigation(
                navigationType = NavigationType.PushRoute(
                    route = DashboardRoute,
                    popUpTo = AddDocumentRoute(IssuanceUiConfig(flowType = flowType))
                ),
            )

            is IssuanceFlowType.ExtraDocument -> ConfigNavigation(
                navigationType = NavigationType.PopTo(
                    route = DashboardRoute
                )
            )
        }
        return SuccessRoute(getSuccessConfigForDeferred(navigation))
    }

    override fun resumeOpenId4VciWithAuthorization(uri: String) {
        walletCoreDocumentsController.resumeOpenId4VciWithAuthorization(uri)
    }

    private fun getSuccessConfigForDeferred(
        navigation: ConfigNavigation
    ): SuccessUIConfig {
        val (textElementsConfig, imageConfig, buttonText) = Triple(
            first = SuccessUIConfig.TextElementsConfig(
                text = resourceProvider.getString(Res.string.issuance_add_document_deferred_success_text),
                description = resourceProvider.getString(Res.string.issuance_add_document_deferred_success_description),
                color = ColorKey.Pending
            ),
            second = SuccessUIConfig.ImageConfig(
                type = SuccessUIConfig.ImageConfig.Type.Drawable(icon = AppIcons.InProgress),
                tint = ColorKey.Primary,
                screenPercentageSize = PERCENTAGE_25,
            ),
            third = resourceProvider.getString(Res.string.issuance_add_document_deferred_success_primary_button_text)
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