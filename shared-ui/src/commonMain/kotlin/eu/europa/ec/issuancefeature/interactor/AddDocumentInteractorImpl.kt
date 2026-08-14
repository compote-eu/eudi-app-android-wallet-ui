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
import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.corelogic.controller.FetchScopedDocumentsPartialState
import eu.europa.ec.corelogic.controller.IssuanceMethod
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.issuancefeature.ui.add.model.AddDocumentUi
import eu.europa.ec.resourceslogic.theme.values.ThemeColors
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.SuccessRoute
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.UiText
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
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.issuance_add_document_no_options
import eu.europa.ec.shared.resources.issuance_add_document_pid_combined

// Phase 2: the Android implementation of the (now KMP) `AddDocumentInteractor` contract, which moved
// to :shared-ui/commonMain with `AddDocumentViewModel`.
class AddDocumentInteractorImpl(
    private val strings: StringCatalog,
    private val platform: AddDocumentPlatformBridge,
) : AddDocumentInteractor {

    private val genericErrorMsg
        get() = strings[Res.string.generic_error_message]

    override fun getAddDocumentOption(
        flowType: IssuanceFlowType,
    ): Flow<AddDocumentInteractorScopedPartialState> =
        flow {
            val state = platform.getScopedDocuments(platform.localeTag())

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
                                                        text = strings[Res.string.issuance_add_document_pid_combined]
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
                                errorMsg = strings[Res.string.issuance_add_document_no_options]
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
                error = it.message ?: genericErrorMsg
            )
        }

    override fun issueDocuments(
        issuanceMethod: IssuanceMethod,
        configIds: List<String>,
        issuerId: String
    ): Flow<AddDocumentInteractorIssueDocumentsPartialState> = flow {

        platform.issueDocuments(
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
            errorMessage = it.message ?: genericErrorMsg
        )
    }

    override fun handleUserAuth(
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
        platform.resumeOpenId4VciWithAuthorization(uri)
    }

    private fun getSuccessConfigForDeferred(
        navigation: ConfigNavigation
    ): SuccessUIConfig {
        val (textElementsConfig, imageConfig, buttonText) = Triple(
            first = SuccessUIConfig.TextElementsConfig(
                text = UiText.Resource(Res.string.issuance_add_document_deferred_success_text),
                description = UiText.Resource(Res.string.issuance_add_document_deferred_success_description),
                color = ColorKey.Pending
            ),
            second = SuccessUIConfig.ImageConfig(
                type = SuccessUIConfig.ImageConfig.Type.Drawable(icon = AppIcons.InProgress),
                tint = ColorKey.Primary,
                screenPercentageSize = PERCENTAGE_25,
            ),
            third = UiText.Resource(Res.string.issuance_add_document_deferred_success_primary_button_text)
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