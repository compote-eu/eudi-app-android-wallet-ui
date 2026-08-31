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

package eu.europa.ec.issuancefeature.ui.offer

import androidx.lifecycle.viewModelScope
import eu.europa.ec.corelogic.model.IssuerRegistrationDomain
import eu.europa.ec.commonfeature.ui.request.model.RelyingPartyHeaderUi
import eu.europa.ec.commonfeature.config.IssuanceSuccessUiConfig
import eu.europa.ec.commonfeature.config.OfferCodeUiConfig
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.issuancefeature.di.getOrCreateCredentialOfferScope
import eu.europa.ec.issuancefeature.di.getOrNullCredentialOfferScope
import eu.europa.ec.issuancefeature.interactor.DocumentOfferInteractor
import eu.europa.ec.issuancefeature.interactor.IssueDocumentsInteractorPartialState
import eu.europa.ec.issuancefeature.interactor.ResolveDocumentOfferInteractorPartialState
import eu.europa.ec.issuancefeature.ui.offer.transformer.DocumentOfferTransformer.toListItemDataUiList
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.AppRouteCodec
import eu.europa.ec.shared.navigation.DocumentIssuanceSuccessRoute
import eu.europa.ec.shared.navigation.DocumentOfferCodeRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.RelyingPartyDataUi
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import eu.europa.ec.uilogic.navigation.helper.DeepLinkClassifier
import eu.europa.ec.uilogic.navigation.helper.DeepLinkKind
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

data class State(
    val offerUiConfig: OfferUiConfig,

    val isLoading: Boolean = true,
    val relyingPartyHeader: RelyingPartyHeaderUi? = null,
    val error: ContentErrorConfig? = null,
    val isInitialised: Boolean = false,
    val notifyOnAuthenticationFailure: Boolean = false,

    val documents: List<ListItemDataUi> = emptyList(),
    val noDocument: Boolean = false,
    val txCodeLength: Int? = null,

    /**
     * The issuer's name as plain text, kept alongside [headerConfig] because two consumers need a
     * resolved `String`: the interactor formats it into the deferred-success copy, and it travels
     * on to `OfferCodeUiConfig`. The interactor guarantees it is never blank.
     */
    val issuerName: String = "",

    val isBottomSheetOpen: Boolean = false,
    val bottomSheetClosingInProgress: Boolean = false,
    val sheetContent: DocumentOfferBottomSheetContent = DocumentOfferBottomSheetContent.IssuerNotTrusted,
) : ViewState {
    val allowAccept: Boolean
        get() = !noDocument
}

sealed class Event : ViewEvent {
    data class Init(val deepLink: String?) : Event()
    data object BackButtonPressed : Event()
    data object PrivacyPolicyLinkClicked : Event()
    data object OnPause : Event()
    data class OnResumeIssuance(val uri: String) : Event()
    data class OnDynamicPresentation(val uri: String) : Event()
    data object DismissError : Event()

    /**
     * @param context the handle device authentication needs, and **null on iOS**, which has none.
     *   Nullable rather than absent so the action still reaches the view-model: guarding the
     *   dispatch on it is what made this silently do nothing on iOS.
     */
    data class StickyButtonPressed(val context: PlatformContext?) : Event()

    sealed class BottomSheet : Event() {
        data class UpdateBottomSheetState(val isOpen: Boolean) : BottomSheet()
        data object FinishedClosing : BottomSheet()
        data object Close : BottomSheet()
    }
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data class SwitchScreen(
            val route: AppRoute,
            val shouldPopToSelf: Boolean = true
        ) : Navigation()

        data class PopBackStackUpTo(
            val route: AppRoute,
            val inclusive: Boolean
        ) : Navigation()

        data object Pop : Navigation()
        data class OpenUrlExternally(val url: String) : Navigation()

        data class DeepLink(
            val link: String,
            val routeToPop: AppRoute? = null
        ) : Navigation()
    }

    data object ShowBottomSheet : Effect()
    data object CloseBottomSheet : Effect()
}

sealed class DocumentOfferBottomSheetContent {
    data object IssuerNotTrusted : DocumentOfferBottomSheetContent()

    data class PartialSuccessWithUntrustedIssuer(
        val issuedDocumentIds: List<String>,
    ) : DocumentOfferBottomSheetContent()
}

@KoinViewModel
class DocumentOfferViewModel(
    private val deepLinkClassifier: DeepLinkClassifier,
    @InjectedParam private val offerUiConfig: OfferUiConfig,
    documentOfferInteractor: DocumentOfferInteractor? = null
) : MviViewModel<Event, State, Effect>() {

    private var _documentOfferInteractor: DocumentOfferInteractor? = documentOfferInteractor

    private val documentOfferInteractor: DocumentOfferInteractor
        get() = _documentOfferInteractor
            ?: getOrCreateCredentialOfferScope().get<DocumentOfferInteractor>().also {
                _documentOfferInteractor = it
            }

    override fun setInitialState(): State {
        return State(
            offerUiConfig = offerUiConfig,
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Init -> {
                if (viewState.value.documents.isEmpty()) {
                    resolveDocumentOffer(
                        offerUri = viewState.value.offerUiConfig.offerUri,
                        deepLink = event.deepLink
                    )
                } else {
                    handleDeepLink(event.deepLink)
                }
            }

            is Event.BackButtonPressed -> {
                if (viewState.value.bottomSheetClosingInProgress) return
                setState { copy(error = null) }
                doNavigation(viewState.value.offerUiConfig.onCancelNavigation)
            }

            is Event.PrivacyPolicyLinkClicked -> {
                viewState.value.relyingPartyHeader?.privacyPolicyUrl?.let { safeUrl ->
                    setEffect {
                        Effect.Navigation.OpenUrlExternally(url = safeUrl)
                    }
                }
            }

            is Event.DismissError -> {
                setState { copy(error = null) }
            }

            is Event.StickyButtonPressed -> {
                issueDocuments(
                    context = event.context,
                    offerUri = viewState.value.offerUiConfig.offerUri,
                    issuerName = viewState.value.issuerName,
                    onSuccessNavigation = viewState.value.offerUiConfig.onSuccessNavigation,
                    txCodeLength = viewState.value.txCodeLength
                )
            }

            is Event.OnPause -> {
                if (viewState.value.isInitialised) {
                    setState { copy(isLoading = false) }
                }
            }

            is Event.OnResumeIssuance -> {
                setState {
                    copy(isLoading = true)
                }
                documentOfferInteractor.resumeOpenId4VciWithAuthorization(event.uri)
            }

            is Event.OnDynamicPresentation -> {
                setEffect {
                    Effect.Navigation.SwitchScreen(
                        PresentationRequestRoute(
                            RequestUriConfig(
                                PresentationMode.OpenId4Vp(
                                    event.uri,
                                    DocumentOfferRoute(viewState.value.offerUiConfig)
                                )
                            )
                        ),
                        shouldPopToSelf = false
                    )
                }
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
                when (val content = viewState.value.sheetContent) {
                    is DocumentOfferBottomSheetContent.IssuerNotTrusted -> {
                        doNavigation(viewState.value.offerUiConfig.onCancelNavigation)
                    }

                    is DocumentOfferBottomSheetContent.PartialSuccessWithUntrustedIssuer -> {
                        goToDocumentIssuanceSuccessScreen(
                            documentIds = content.issuedDocumentIds,
                            onSuccessNavigation = viewState.value.offerUiConfig.onSuccessNavigation,
                        )
                    }
                }
            }

            is Event.BottomSheet.Close -> {
                if (!viewState.value.bottomSheetClosingInProgress) {
                    setState { copy(bottomSheetClosingInProgress = true) }
                    hideBottomSheet()
                }
            }
        }
    }

    private fun resolveDocumentOffer(offerUri: String, deepLink: String? = null) {
        setState {
            copy(
                isLoading = documents.isEmpty(),
                error = null
            )
        }
        viewModelScope.launch {
            documentOfferInteractor.resolveDocumentOffer(
                offerUri = offerUri
            ).collect { response ->
                when (response) {
                    is ResolveDocumentOfferInteractorPartialState.Failure -> {
                        setState {
                            copy(
                                isLoading = false,
                                isInitialised = false,
                                error = ContentErrorConfig(
                                    errorSubTitle = response.errorMessage.asUiText(),
                                    onCancel = {
                                        setEvent(Event.DismissError)
                                        doNavigation(viewState.value.offerUiConfig.onCancelNavigation)
                                    }
                                )
                            )
                        }
                    }

                    is ResolveDocumentOfferInteractorPartialState.IssuerNotTrusted -> {
                        setState {
                            copy(
                                isLoading = false,
                                isInitialised = false,
                                error = null
                            )
                        }
                        setEffect { Effect.ShowBottomSheet }
                    }

                    is ResolveDocumentOfferInteractorPartialState.Success -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = null,
                                documents = response.documents.toListItemDataUiList(),
                                isInitialised = true,
                                noDocument = false,
                                txCodeLength = response.txCodeLength,
                                issuerName = response.issuerName,
                                relyingPartyHeader = buildRelyingPartyHeader(
                                    issuerName = response.issuerName,
                                    issuerLogo = response.issuerLogo,
                                    issuerRegistration = response.issuerRegistration,
                                ),
                            )
                        }

                        handleDeepLink(deepLink)
                    }

                    is ResolveDocumentOfferInteractorPartialState.NoDocument -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = null,
                                documents = emptyList(),
                                isInitialised = true,
                                noDocument = true,
                                issuerName = response.issuerName,
                                relyingPartyHeader = buildRelyingPartyHeader(
                                    issuerName = response.issuerName,
                                    issuerLogo = response.issuerLogo,
                                    issuerRegistration = response.issuerRegistration,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The offer screen's who-is-issuing header: the provider's identity, and the registration
     * sections from the registration-certificate evaluation. Only a verified registration renders
     * details, and only a verified one earns the badge.
     */
    private fun buildRelyingPartyHeader(
        issuerName: String,
        issuerLogo: String?,
        issuerRegistration: IssuerRegistrationDomain,
    ): RelyingPartyHeaderUi {
        val details = when (issuerRegistration) {
            is IssuerRegistrationDomain.Verified -> issuerRegistration.details

            is IssuerRegistrationDomain.Blocked,
            is IssuerRegistrationDomain.NotVerified,
            is IssuerRegistrationDomain.NotEvaluated -> null
        }

        return RelyingPartyHeaderUi(
            relyingParty = RelyingPartyDataUi(
                logo = issuerLogo,
                isVerified = issuerRegistration is IssuerRegistrationDomain.Verified,
                name = issuerName.asUiText(),
                uniqueId = details?.uniqueId,
                description = null,
            ),
            intendedUse = details?.intendedUse,
            privacyPolicyUrl = details?.privacyPolicyUrl,
        )
    }

    private fun issueDocuments(
        context: PlatformContext?,
        offerUri: String,
        issuerName: String,
        onSuccessNavigation: ConfigNavigation,
        txCodeLength: Int?
    ) {
        viewModelScope.launch {

            txCodeLength?.let {
                navigateToOfferCodeScreen(
                    offerUri = offerUri,
                    issuerName = issuerName,
                    txCodeLength = txCodeLength,
                    onSuccessNavigation = onSuccessNavigation
                )
                return@launch
            }

            setState {
                copy(
                    isLoading = true,
                    error = null
                )
            }

            documentOfferInteractor.issueDocuments(
                offerUri = offerUri,
                issuerName = issuerName,
                navigation = onSuccessNavigation
            ).collect { response ->
                when (response) {
                    is IssueDocumentsInteractorPartialState.Failure -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = ContentErrorConfig(
                                    errorSubTitle = response.errorMessage.asUiText(),
                                    onCancel = { setEvent(Event.DismissError) }
                                )
                            )
                        }
                    }

                    is IssueDocumentsInteractorPartialState.IssuerNotTrusted -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = null,
                                sheetContent = DocumentOfferBottomSheetContent.IssuerNotTrusted
                            )
                        }
                        setEffect { Effect.ShowBottomSheet }
                    }

                    is IssueDocumentsInteractorPartialState.PartialSuccessWithUntrustedIssuer -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = null,
                                sheetContent = DocumentOfferBottomSheetContent.PartialSuccessWithUntrustedIssuer(
                                    issuedDocumentIds = response.issuedDocumentIds
                                )
                            )
                        }
                        setEffect { Effect.ShowBottomSheet }
                    }

                    is IssueDocumentsInteractorPartialState.Success -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = null,
                            )
                        }

                        goToDocumentIssuanceSuccessScreen(
                            documentIds = response.documentIds,
                            onSuccessNavigation = onSuccessNavigation,
                        )
                    }

                    is IssueDocumentsInteractorPartialState.DeferredSuccess -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = null,
                            )
                        }

                        goToSuccessScreen(route = response.successRoute)
                    }

                    is IssueDocumentsInteractorPartialState.UserAuthRequired -> {
                        // No handle means no way to raise the prompt — iOS. Report it rather than
                        // dropping the outcome on the floor, which is what the old UI-level guard did.
                        if (context == null) {
                            setState {
                                copy(
                                    isLoading = false,
                                    error = ContentErrorConfig(
                                        errorSubTitle = UiText.Resource(Res.string.generic_error_message),
                                        onCancel = { setEvent(Event.DismissError) }
                                    )
                                )
                            }
                            return@collect
                        }
                        documentOfferInteractor.handleUserAuthentication(
                            context = context,
                            crypto = response.crypto,
                            notifyOnAuthenticationFailure = viewState.value.notifyOnAuthenticationFailure,
                            resultHandler = response.resultHandler
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        getOrNullCredentialOfferScope()?.close()
        super.onCleared()
    }

    private fun goToDocumentIssuanceSuccessScreen(
        documentIds: List<String>,
        onSuccessNavigation: ConfigNavigation,
    ) {
        setEffect {
            Effect.Navigation.SwitchScreen(
                route = DocumentIssuanceSuccessRoute(
                    IssuanceSuccessUiConfig(
                        documentIds = documentIds,
                        onSuccessNavigation = onSuccessNavigation,
                    )
                )
            )
        }
    }

    private fun goToSuccessScreen(route: AppRoute) {
        setEffect {
            Effect.Navigation.SwitchScreen(
                route = route
            )
        }
    }

    private fun doNavigation(navigation: ConfigNavigation) {
        val navigationEffect: Effect.Navigation = when (val nav = navigation.navigationType) {
            is NavigationType.PopTo -> {
                Effect.Navigation.PopBackStackUpTo(
                    route = nav.route,
                    inclusive = false
                )
            }

            is NavigationType.Deeplink -> Effect.Navigation.DeepLink(
                nav.link,
                AppRouteCodec.decode(nav.routeToPop)
            )

            is NavigationType.Pop, NavigationType.Finish -> Effect.Navigation.Pop

            is NavigationType.PushRoute -> Effect.Navigation.SwitchScreen(nav.route)
        }

        setEffect {
            navigationEffect
        }
    }

    private fun navigateToOfferCodeScreen(
        offerUri: String,
        issuerName: String,
        txCodeLength: Int,
        onSuccessNavigation: ConfigNavigation
    ) {
        setEffect {
            Effect.Navigation.SwitchScreen(
                route = DocumentOfferCodeRoute(
                    OfferCodeUiConfig(
                        offerUri = offerUri,
                        txCodeLength = txCodeLength,
                        issuerName = issuerName,
                        onSuccessNavigation = onSuccessNavigation
                    )
                ),
                shouldPopToSelf = false
            )
        }
    }

    private fun handleDeepLink(deepLink: String?) {
        deepLink?.let { link ->
            // Only an external link concerns this screen; any other kind belongs to the flow already
            // handling it.
            if (deepLinkClassifier.classify(link) == DeepLinkKind.EXTERNAL) {
                setEffect {
                    Effect.Navigation.DeepLink(link)
                }
            }
        }
    }

    private fun hideBottomSheet() {
        setEffect {
            Effect.CloseBottomSheet
        }
    }
}