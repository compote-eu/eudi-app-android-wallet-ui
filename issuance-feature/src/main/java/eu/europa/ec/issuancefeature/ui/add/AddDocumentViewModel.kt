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

package eu.europa.ec.issuancefeature.ui.add

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceSuccessUiConfig
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.commonfeature.config.QrScanUiConfig
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.corelogic.controller.IssuanceMethod
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractor
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractorIssueDocumentsPartialState
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractorScopedPartialState
import eu.europa.ec.issuancefeature.ui.add.model.AddDocumentUi
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentIssuanceSuccessRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import eu.europa.ec.uilogic.navigation.helper.DeepLinkAction
import eu.europa.ec.uilogic.navigation.helper.DeepLinkType
import eu.europa.ec.uilogic.navigation.helper.hasDeepLink
import eu.europa.ec.uilogic.navigation.helper.toLegacyArguments
import eu.europa.ec.uilogic.serializer.UiSerializer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

data class State(
    val navigatableAction: ScreenNavigateAction,
    val onBackAction: (() -> Unit)? = null,

    val issuanceConfig: IssuanceUiConfig,

    val isLoading: Boolean = false,
    val error: ContentErrorConfig? = null,
    val isInitialised: Boolean = false,
    val notifyOnAuthenticationFailure: Boolean = false,

    val title: String = "",
    val subtitle: String = "",
    val options: List<Pair<String, List<AddDocumentUi>>> = emptyList(),
    val noOptions: Boolean = false,

    val isBottomSheetOpen: Boolean = false,
) : ViewState

sealed class Event : ViewEvent {
    data class Init(val deepLink: Uri?) : Event()
    data object GoToQrScan : Event()
    data object Pop : Event()
    data object OnPause : Event()
    data class OnResumeIssuance(val uri: String) : Event()
    data class OnDynamicPresentation(val uri: String) : Event()
    data object Finish : Event()
    data object DismissError : Event()
    data class IssueDocument(
        val issuanceMethod: IssuanceMethod,
        val issuerId: String,
        val configIds: List<String>,
        val context: Context
    ) : Event()

    sealed class BottomSheet : Event() {
        data class UpdateBottomSheetState(val isOpen: Boolean) : BottomSheet()
        data object Close : BottomSheet()
    }
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()
        data object Finish : Navigation()
        data class SwitchScreen(val route: AppRoute, val inclusive: Boolean) : Navigation()
        data class OpenDeepLinkAction(val deepLinkUri: Uri, val arguments: String?) : Navigation()
    }

    data object ShowBottomSheet : Effect()
    data object CloseBottomSheet : Effect()
}

@KoinViewModel
class AddDocumentViewModel(
    private val addDocumentInteractor: AddDocumentInteractor,
    private val resourceProvider: ResourceProvider,
    private val uiSerializer: UiSerializer,
    @InjectedParam private val issuanceConfig: String,
) : MviViewModel<Event, State, Effect>() {

    private var issuanceJob: Job? = null

    override fun setInitialState(): State {
        val deserializedConfig: IssuanceUiConfig = uiSerializer.fromBase64(
            payload = issuanceConfig,
            model = IssuanceUiConfig::class.java,
            parser = IssuanceUiConfig.Parser
        ) ?: throw RuntimeException("IssuanceUiConfig:: is Missing or invalid")

        return State(
            issuanceConfig = deserializedConfig,
            navigatableAction = getNavigatableAction(deserializedConfig.flowType),
            onBackAction = getOnBackAction(deserializedConfig.flowType),
            title = resourceProvider.getString(R.string.issuance_add_document_title),
            subtitle = resourceProvider.getString(R.string.issuance_add_document_subtitle),
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Init -> {
                if (viewState.value.options.isEmpty()) {
                    getOptions(event, event.deepLink)
                } else {
                    handleDeepLink(event.deepLink)
                }
            }

            is Event.Pop -> setEffect { Effect.Navigation.Pop }

            is Event.DismissError -> {
                setState { copy(error = null) }
            }

            is Event.IssueDocument -> {
                issueDocument(
                    issuanceMethod = event.issuanceMethod,
                    configIds = event.configIds,
                    issuerId = event.issuerId,
                    context = event.context
                )
            }

            is Event.Finish -> setEffect { Effect.Navigation.Finish }

            is Event.OnPause -> {
                if (viewState.value.isInitialised) {
                    setState { copy(isLoading = false) }
                }
            }

            is Event.OnResumeIssuance -> {
                setState {
                    copy(isLoading = true)
                }
                addDocumentInteractor.resumeOpenId4VciWithAuthorization(event.uri)
            }

            is Event.OnDynamicPresentation -> {
                setEffect {
                    Effect.Navigation.SwitchScreen(
                        PresentationRequestRoute(
                            RequestUriConfig(
                                PresentationMode.OpenId4Vp(
                                    event.uri,
                                    AddDocumentRoute(viewState.value.issuanceConfig)
                                )
                            )
                        ),
                        inclusive = false
                    )
                }
            }

            is Event.GoToQrScan -> {
                navigateToQrScanScreen()
            }

            is Event.BottomSheet.UpdateBottomSheetState -> {
                setState { copy(isBottomSheetOpen = event.isOpen) }
            }

            is Event.BottomSheet.Close -> {
                setEffect { Effect.CloseBottomSheet }
            }
        }
    }

    private fun getOptions(event: Event, deepLinkUri: Uri?) {

        setState {
            copy(
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            addDocumentInteractor.getAddDocumentOption(
                flowType = viewState.value.issuanceConfig.flowType
            ).collect { response ->
                when (response) {
                    is AddDocumentInteractorScopedPartialState.Success -> {
                        setState {
                            copy(
                                error = null,
                                options = response.options,
                                noOptions = false,
                                isInitialised = true,
                                isLoading = false
                            )
                        }
                        handleDeepLink(deepLinkUri)
                    }

                    is AddDocumentInteractorScopedPartialState.Failure -> {

                        val deepLinkAction = getDeepLinkAction(deepLinkUri)

                        setState {
                            copy(
                                error = if (deepLinkAction == null) {
                                    ContentErrorConfig(
                                        onRetry = { setEvent(event) },
                                        errorSubTitle = response.error,
                                        onCancel = { setEvent(Event.DismissError) }
                                    )
                                } else {
                                    null
                                },
                                options = emptyList(),
                                noOptions = false,
                                isInitialised = true,
                                isLoading = false
                            )
                        }
                        deepLinkAction?.let {
                            handleDeepLink(it.first, it.second)
                        }
                    }

                    is AddDocumentInteractorScopedPartialState.NoOptions -> {
                        setState {
                            copy(
                                error = null,
                                options = emptyList(),
                                noOptions = true,
                                isInitialised = true,
                                isLoading = false
                            )
                        }
                    }
                }
            }
        }
    }

    private fun issueDocument(
        issuanceMethod: IssuanceMethod,
        issuerId: String,
        configIds: List<String>,
        context: Context
    ) {
        issuanceJob?.cancel()
        issuanceJob = viewModelScope.launch {

            setState {
                copy(
                    isLoading = true,
                    error = null
                )
            }

            addDocumentInteractor.issueDocuments(
                issuanceMethod = issuanceMethod,
                issuerId = issuerId,
                configIds = configIds
            ).collect { response ->
                when (response) {
                    is AddDocumentInteractorIssueDocumentsPartialState.Failure -> {
                        setState {
                            copy(
                                error = ContentErrorConfig(
                                    onRetry = null,
                                    errorSubTitle = response.errorMessage,
                                    onCancel = { setEvent(Event.DismissError) }
                                ),
                                isLoading = false
                            )
                        }
                    }

                    is AddDocumentInteractorIssueDocumentsPartialState.IssuerNotTrusted -> {
                        setState {
                            copy(
                                error = null,
                                isLoading = false
                            )
                        }
                        setEffect { Effect.ShowBottomSheet }
                    }

                    is AddDocumentInteractorIssueDocumentsPartialState.Success -> {
                        setState {
                            copy(
                                error = null,
                                isLoading = false
                            )
                        }
                        navigateToDocumentIssuanceSuccessScreen(
                            documentIds = response.documentIds
                        )
                    }

                    is AddDocumentInteractorIssueDocumentsPartialState.DeferredSuccess -> {
                        setState {
                            copy(
                                error = null,
                                isLoading = false
                            )
                        }
                        navigateToGenericSuccessScreen(
                            route = addDocumentInteractor.buildGenericSuccessRouteForDeferred(
                                viewState.value.issuanceConfig.flowType
                            )
                        )
                    }

                    is AddDocumentInteractorIssueDocumentsPartialState.UserAuthRequired -> {
                        addDocumentInteractor.handleUserAuth(
                            context = context,
                            crypto = response.crypto,
                            notifyOnAuthenticationFailure = viewState.value.notifyOnAuthenticationFailure,
                            resultHandler = DeviceAuthenticationResult(
                                onAuthenticationSuccess = {
                                    response.resultHandler.onAuthenticationSuccess()
                                },
                                onAuthenticationError = {
                                    response.resultHandler.onAuthenticationError()
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    private fun navigateToDocumentIssuanceSuccessScreen(documentIds: List<String>) {
        val onSuccessNavigation = when (viewState.value.issuanceConfig.flowType) {
            is IssuanceFlowType.NoDocument -> ConfigNavigation(
                navigationType = NavigationType.PushRoute(
                    route = DashboardRoute,
                    popUpTo = AddDocumentRoute(viewState.value.issuanceConfig)
                )
            )

            is IssuanceFlowType.ExtraDocument -> ConfigNavigation(
                navigationType = NavigationType.PopTo(
                    route = DashboardRoute
                )
            )
        }

        setEffect {
            Effect.Navigation.SwitchScreen(
                route = DocumentIssuanceSuccessRoute(
                    IssuanceSuccessUiConfig(
                        documentIds = documentIds,
                        onSuccessNavigation = onSuccessNavigation,
                    )
                ),
                inclusive = false
            )
        }
    }

    private fun navigateToGenericSuccessScreen(route: AppRoute) {
        setEffect {
            Effect.Navigation.SwitchScreen(
                route = route,
                inclusive = true
            )
        }
    }

    private fun navigateToQrScanScreen() {
        setEffect {
            Effect.Navigation.SwitchScreen(
                route = QrScanRoute(
                    QrScanUiConfig(
                        title = resourceProvider.getString(R.string.issuance_qr_scan_title),
                        subTitle = resourceProvider.getString(R.string.issuance_qr_scan_subtitle),
                        qrScanFlow = QrScanFlow.Issuance(viewState.value.issuanceConfig.flowType)
                    )
                ),
                inclusive = false
            )
        }
    }

    private fun getNavigatableAction(flowType: IssuanceFlowType): ScreenNavigateAction {
        return when (flowType) {
            is IssuanceFlowType.NoDocument -> ScreenNavigateAction.NONE
            is IssuanceFlowType.ExtraDocument -> ScreenNavigateAction.BACKABLE
        }
    }

    private fun getOnBackAction(flowType: IssuanceFlowType): (() -> Unit) {
        return when (flowType) {
            is IssuanceFlowType.NoDocument -> {
                { setEvent(Event.Finish) }
            }

            is IssuanceFlowType.ExtraDocument -> {
                { setEvent(Event.Pop) }
            }
        }
    }

    private fun getDeepLinkAction(deepLinkUri: Uri?): Pair<Uri, DeepLinkAction>? {
        return deepLinkUri?.let { uri ->
            hasDeepLink(uri)?.let {
                uri to it
            }
        }
    }

    private fun handleDeepLink(deepLinkUri: Uri?) {
        getDeepLinkAction(deepLinkUri)?.let { pair ->
            handleDeepLink(pair.first, pair.second)
        }
    }

    private fun handleDeepLink(uri: Uri, action: DeepLinkAction) {
        when (action.type) {
            DeepLinkType.CREDENTIAL_OFFER -> {
                setEffect {
                    Effect.Navigation.OpenDeepLinkAction(
                        deepLinkUri = uri,
                        arguments = DocumentOfferRoute(
                            OfferUiConfig(
                                offerUri = action.link.toString(),
                                onSuccessNavigation = ConfigNavigation(
                                    navigationType = NavigationType.PushRoute(
                                        route = DashboardRoute,
                                        popUpTo = AddDocumentRoute(viewState.value.issuanceConfig)
                                    )
                                ),
                                onCancelNavigation = ConfigNavigation(
                                    navigationType = NavigationType.Pop
                                )
                            )
                        ).toLegacyArguments()
                    )
                }
            }

            DeepLinkType.EXTERNAL -> {
                setEffect {
                    Effect.Navigation.OpenDeepLinkAction(
                        deepLinkUri = uri,
                        arguments = null
                    )
                }
            }

            else -> {}
        }
    }
}