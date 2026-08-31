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
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentIssuanceSuccessRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.issuance_add_document_subtitle
import eu.europa.ec.shared.resources.issuance_add_document_title
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import eu.europa.ec.uilogic.navigation.helper.DeepLinkClassifier
import eu.europa.ec.uilogic.navigation.helper.DeepLinkKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

data class State(
    val navigatableAction: ScreenNavigateAction,

    val issuanceConfig: IssuanceUiConfig,

    val isLoading: Boolean = false,
    val error: ContentErrorConfig? = null,
    val isInitialised: Boolean = false,
    val notifyOnAuthenticationFailure: Boolean = false,

    val title: UiText = UiText.Empty,
    val subtitle: UiText = UiText.Empty,
    val options: List<Pair<String, List<AddDocumentUi>>> = emptyList(),
    val noOptions: Boolean = false,

    val isBottomSheetOpen: Boolean = false,
    val bottomSheetClosingInProgress: Boolean = false,
    val sheetContent: AddDocumentBottomSheetContent = AddDocumentBottomSheetContent.IssuerNotTrusted,
) : ViewState

sealed class Event : ViewEvent {
    data class Init(val deepLink: String?) : Event()
    data object GoToQrScan : Event()
    data object OnBack : Event()
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
        val context: PlatformContext?
    ) : Event()

    sealed class BottomSheet : Event() {
        data class UpdateBottomSheetState(val isOpen: Boolean) : BottomSheet()
        data object FinishedClosing : BottomSheet()

        sealed class IssuerNotTrusted : BottomSheet() {
            data object CloseButtonPressed : IssuerNotTrusted()
        }

        sealed class NoTrustedIssuers : BottomSheet() {
            data object Close : NoTrustedIssuers()
        }
    }
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()
        data object Finish : Navigation()
        data class SwitchScreen(val route: AppRoute, val inclusive: Boolean) : Navigation()
        data class OpenDeepLinkAction(val deepLinkUri: String, val route: AppRoute?) : Navigation()
    }

    data object ShowBottomSheet : Effect()
    data object CloseBottomSheet : Effect()
}

sealed class AddDocumentBottomSheetContent {
    data object IssuerNotTrusted : AddDocumentBottomSheetContent()
    data object NoTrustedIssuers : AddDocumentBottomSheetContent()
}

@KoinViewModel
class AddDocumentViewModel(
    private val addDocumentInteractor: AddDocumentInteractor,
    private val deepLinkClassifier: DeepLinkClassifier,
    @InjectedParam private val issuanceConfig: IssuanceUiConfig,
) : MviViewModel<Event, State, Effect>() {

    private var issuanceJob: Job? = null

    override fun setInitialState(): State {
        return State(
            issuanceConfig = issuanceConfig,
            navigatableAction = getNavigatableAction(issuanceConfig.flowType),
            title = UiText.Resource(Res.string.issuance_add_document_title),
            subtitle = UiText.Resource(Res.string.issuance_add_document_subtitle),
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

            is Event.OnBack -> {
                // The NoTrustedIssuers sheet navigates from FinishedClosing; a back press landing
                // in the hide animation's window would navigate a second time.
                if (viewState.value.bottomSheetClosingInProgress) return
                getOnBackAction(viewState.value.issuanceConfig.flowType).invoke()
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
                setState {
                    copy(
                        isBottomSheetOpen = event.isOpen,
                        bottomSheetClosingInProgress = if (event.isOpen) false
                        else bottomSheetClosingInProgress,
                    )
                }
            }

            is Event.BottomSheet.FinishedClosing -> {
                when (viewState.value.sheetContent) {
                    is AddDocumentBottomSheetContent.NoTrustedIssuers -> {
                        val onClosed = getOnNoTrustedIssuersClosedAction(
                            flowType = viewState.value.issuanceConfig.flowType
                        )

                        if (onClosed != null) {
                            onClosed.invoke()
                        } else {
                            setState { copy(bottomSheetClosingInProgress = false) }
                        }
                    }

                    is AddDocumentBottomSheetContent.IssuerNotTrusted -> Unit
                }
            }

            is Event.BottomSheet.IssuerNotTrusted.CloseButtonPressed -> {
                hideBottomSheet()
            }

            is Event.BottomSheet.NoTrustedIssuers.Close -> {
                if (!viewState.value.bottomSheetClosingInProgress) {
                    setState { copy(bottomSheetClosingInProgress = true) }
                    hideBottomSheet()
                }
            }
        }
    }

    private fun getOptions(event: Event, deepLink: String?) {

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
                        handleDeepLink(deepLink)
                    }

                    is AddDocumentInteractorScopedPartialState.Failure -> {

                        val deepLinkAction = deepLinkAction(deepLink)

                        setState {
                            copy(
                                error = if (deepLinkAction == null) {
                                    ContentErrorConfig(
                                        onRetry = { setEvent(event) },
                                        errorSubTitle = response.error.asUiText(),
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
                        // The options failed to load, but a deep link still has somewhere to go —
                        // hence no error card above when one arrived.
                        deepLinkAction?.let { (link, kind) ->
                            handleDeepLink(link, kind)
                        }
                    }

                    is AddDocumentInteractorScopedPartialState.NoTrustedIssuers -> {

                        val deepLinkAction = deepLinkAction(deepLink)

                        setState {
                            copy(
                                error = null,
                                options = emptyList(),
                                noOptions = false,
                                isInitialised = true,
                                isLoading = false
                            )
                        }

                        if (deepLinkAction == null) {
                            showBottomSheet(
                                sheetContent = AddDocumentBottomSheetContent.NoTrustedIssuers
                            )
                        } else {
                            handleDeepLink(deepLinkAction.first, deepLinkAction.second)
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
        context: PlatformContext?
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
                                    errorSubTitle = response.errorMessage.asUiText(),
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
                        showBottomSheet(
                            sheetContent = AddDocumentBottomSheetContent.IssuerNotTrusted
                        )
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
                        // See DocumentOfferViewModel: no handle, no prompt, so say so.
                        if (context == null) {
                            setState {
                                copy(
                                    error = ContentErrorConfig(
                                        onRetry = null,
                                        errorSubTitle = UiText.Resource(Res.string.generic_error_message),
                                        onCancel = { setEvent(Event.DismissError) }
                                    ),
                                    isLoading = false
                                )
                            }
                            return@collect
                        }
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

    /**
     * The link paired with its kind, or null when there is no link or it is not a deep link at all.
     * Callers use the null case to mean "nothing arrived by deep link", which is what decides whether
     * a failure to load the options is worth an error card.
     */
    /**
     * What to run once the "no verified issuer" notice has closed and the list is empty, or `null`
     * to stay on this screen. This is deliberately not [getOnBackAction]. With no document yet,
     * this screen is where issuance starts, so backing out of it closes the app. It would also
     * take away the toolbar's scan action, which is the only remaining way to get a document. That
     * action still works, because a credential offer carries its own issuer.
     */
    private fun getOnNoTrustedIssuersClosedAction(flowType: IssuanceFlowType): (() -> Unit)? {
        return when (flowType) {
            is IssuanceFlowType.NoDocument -> null

            // the dashboard behind this screen offers its own add-document and scan entry points,
            // so backing out loses nothing
            is IssuanceFlowType.ExtraDocument -> {
                { setEvent(Event.Pop) }
            }
        }
    }

    private fun showBottomSheet(sheetContent: AddDocumentBottomSheetContent) {
        setState {
            copy(sheetContent = sheetContent)
        }
        setEffect {
            Effect.ShowBottomSheet
        }
    }

    private fun hideBottomSheet() {
        setEffect {
            Effect.CloseBottomSheet
        }
    }

    private fun deepLinkAction(deepLink: String?): Pair<String, DeepLinkKind>? =
        deepLink?.let { link ->
            deepLinkClassifier.classify(link)?.let { kind -> link to kind }
        }

    private fun handleDeepLink(deepLink: String?) {
        deepLinkAction(deepLink)?.let { (link, kind) ->
            handleDeepLink(link, kind)
        }
    }

    private fun handleDeepLink(link: String, kind: DeepLinkKind) {
        when (kind) {
            DeepLinkKind.CREDENTIAL_OFFER -> {
                setEffect {
                    Effect.Navigation.OpenDeepLinkAction(
                        deepLinkUri = link,
                        route = DocumentOfferRoute(
                            OfferUiConfig(
                                offerUri = link,
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
                        )
                    )
                }
            }

            DeepLinkKind.EXTERNAL -> {
                setEffect {
                    Effect.Navigation.OpenDeepLinkAction(
                        deepLinkUri = link,
                        route = null
                    )
                }
            }

            else -> {}
        }
    }
}