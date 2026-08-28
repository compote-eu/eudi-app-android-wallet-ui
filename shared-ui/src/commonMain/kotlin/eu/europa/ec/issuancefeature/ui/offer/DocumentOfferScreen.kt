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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import eu.europa.ec.shared.resources.UiText
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.ui.issuance.IssuerNotTrustedSheetContent
import eu.europa.ec.commonfeature.ui.issuance.IssuerPartiallyTrustedSheetContent
import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.shared.platform.platformAction
import eu.europa.ec.shared.platform.platformStringExtra
import eu.europa.ec.issuancefeature.util.TestTag
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.uilogic.component.rememberPlatformScreenActions
import eu.europa.ec.uilogic.component.PlatformScreenActions
import eu.europa.ec.uilogic.component.ErrorInfo
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.RelyingPartyDataUi
import eu.europa.ec.uilogic.component.content.BroadcastAction
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.rememberPlatformContextOrNull
import eu.europa.ec.uilogic.component.utils.LifecycleEffect
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.wrap.WrapListItem
import eu.europa.ec.uilogic.component.wrap.WrapModalBottomSheet
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.navigation.helper.navigateReplacingCurrent
import eu.europa.ec.uilogic.navigation.helper.popBackStackTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.request_intended_use_section_title
import eu.europa.ec.shared.resources.request_privacy_policy_section_title
import eu.europa.ec.shared.resources.issuance_document_offer_cancel_button_text
import eu.europa.ec.shared.resources.issuance_document_offer_accept_button_text
import eu.europa.ec.shared.resources.issuance_document_offer_screen_title
import eu.europa.ec.uilogic.component.content.ContentTitle
import eu.europa.ec.uilogic.component.RelyingPartyLayout
import eu.europa.ec.uilogic.component.RelyingParty
import eu.europa.ec.uilogic.component.InfoSection
import eu.europa.ec.uilogic.component.InfoLinkSection
import eu.europa.ec.commonfeature.ui.request.model.RelyingPartyHeaderUi
import eu.europa.ec.commonfeature.ui.request.ConsentStickyBottomSection
import eu.europa.ec.shared.resources.issuance_document_offer_error_no_document
import eu.europa.ec.shared.resources.issuance_document_offer_relying_party_default_name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentOfferScreen(
    navigator: AppNavigator,
    viewModel: DocumentOfferViewModel,
    /**
     * The external deep link waiting to be handled, if any — read once per resume, since reading it
     * *consumes* it on Android (it clears the cached intent). Injected because Android's answer,
     * `Context.getPendingUri()`, lives in `:ui-logic`, which depends on this module.
     */
    pendingDeepLink: () -> String? = { null },
    /**
     * Hands an external deep link to whatever flow owns it, parking it for [AppRoute] first when one is
     * given. Injected for the same reason, and additionally because Android's `handleDeepLinkAction`
     * reaches the Android-only RQES UI SDK. the iOS entry for this screen keeps the no-op default,
     * because nothing on iOS hands a link out from here. Deep links *arriving* are wired: the app
     * delegate hands them to `IosDeepLinks` and the shared classifier routes them.
     */
    onExternalDeepLink: (link: String, routeToPop: AppRoute?) -> Unit = { _, _ -> },
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    // Null on iOS, where issuance does not run through wallet-core: the button is simply inert there.
    val platformContext = rememberPlatformContextOrNull()

    val isBottomSheetOpen = state.isBottomSheetOpen
    val platformActions = rememberPlatformScreenActions()
    val scope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ContentScreen(
        isLoading = state.isLoading,
        contentErrorConfig = state.error,
        navigatableAction = ScreenNavigateAction.BACKABLE,
        onBack = { viewModel.setEvent(Event.BackButtonPressed) },
        stickyBottom = { paddingValues ->
            ConsentStickyBottomSection(
                modifier = Modifier.fillMaxWidth(),
                paddingValues = paddingValues,
                buttonsTestTag = TestTag.DocumentOfferScreen.BUTTON,
                // Issuance has no registration warning: an offer whose registration is refused
                // never reaches this screen — the resolve gate turns it into IssuerNotTrusted.
                warningSection = null,
                primaryButtonText = stringResource(Res.string.issuance_document_offer_accept_button_text),
                cancelButtonText = stringResource(Res.string.issuance_document_offer_cancel_button_text),
                primaryButtonEnabled = !state.isLoading && state.allowAccept,
                onPrimaryButtonClick = {
                    platformContext?.let {
                        viewModel.setEvent(Event.StickyButtonPressed(it))
                    }
                },
                onCancelButtonClick = { viewModel.setEvent(Event.BackButtonPressed) },
            )
        },
        broadcastAction = BroadcastAction(
            intentFilters = listOf(
                CoreActions.VCI_RESUME_ACTION,
                CoreActions.VCI_DYNAMIC_PRESENTATION
            ),
            callback = {
                when (it?.platformAction()) {
                    CoreActions.VCI_RESUME_ACTION -> it.platformStringExtra("uri")?.let { link ->
                        viewModel.setEvent(Event.OnResumeIssuance(link))
                    }

                    CoreActions.VCI_DYNAMIC_PRESENTATION -> it.platformStringExtra("uri")
                        ?.let { link ->
                            viewModel.setEvent(Event.OnDynamicPresentation(link))
                        }
                }
            }
        )
    ) { paddingValues ->
        Content(
            state = state,
            effectFlow = viewModel.effect,
            onEventSend = { viewModel.setEvent(it) },
            onNavigationRequested = { navigationEffect ->
                handleNavigationEffect(
                    navigationEffect = navigationEffect,
                    navigator = navigator,
                    onExternalDeepLink = onExternalDeepLink,
                    platformActions = platformActions,
                )
            },
            paddingValues = paddingValues,
            coroutineScope = scope,
            modalBottomSheetState = bottomSheetState,
        )

        if (isBottomSheetOpen) {
            WrapModalBottomSheet(
                onDismissRequest = {
                    viewModel.setEvent(Event.BottomSheet.Close)
                },
                sheetState = bottomSheetState
            ) {
                when (state.sheetContent) {
                    is DocumentOfferBottomSheetContent.IssuerNotTrusted -> {
                        IssuerNotTrustedSheetContent(
                            onClose = {
                                viewModel.setEvent(Event.BottomSheet.Close)
                            },
                        )
                    }

                    is DocumentOfferBottomSheetContent.PartialSuccessWithUntrustedIssuer -> {
                        IssuerPartiallyTrustedSheetContent(
                            onClose = {
                                viewModel.setEvent(Event.BottomSheet.Close)
                            },
                        )
                    }
                }
            }
        }
    }

    LifecycleEffect(
        lifecycleOwner = LocalLifecycleOwner.current,
        lifecycleEvent = Lifecycle.Event.ON_PAUSE
    ) {
        viewModel.setEvent(Event.OnPause)
    }

    LifecycleEffect(
        lifecycleOwner = LocalLifecycleOwner.current,
        lifecycleEvent = Lifecycle.Event.ON_RESUME
    ) {
        viewModel.setEvent(Event.Init(pendingDeepLink()))
    }
}

/**
 * The who-is-issuing header: the provider's identity and the registration sections a verified
 * registration certificate supplies.
 */
@Composable
private fun IssuerHeaderSection(
    modifier: Modifier,
    header: RelyingPartyHeaderUi,
    onEventSend: (Event) -> Unit,
) {
    Column(modifier = modifier) {
        RelyingParty(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SPACING_SMALL.dp),
            relyingPartyData = header.relyingParty,
            layout = RelyingPartyLayout.InlineStart,
        )

        header.privacyPolicyUrl?.let { safePrivacyPolicyUrl ->
            InfoLinkSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SPACING_SMALL.dp),
                title = stringResource(Res.string.request_privacy_policy_section_title),
                linkText = safePrivacyPolicyUrl,
                onLinkClick = { onEventSend(Event.PrivacyPolicyLinkClicked) },
            )
        }

        header.intendedUse?.let { safeIntendedUse ->
            InfoSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SPACING_SMALL.dp),
                title = stringResource(Res.string.request_intended_use_section_title),
                body = safeIntendedUse,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    state: State,
    effectFlow: Flow<Effect>,
    onEventSend: (Event) -> Unit,
    onNavigationRequested: (Effect.Navigation) -> Unit,
    paddingValues: PaddingValues,
    coroutineScope: CoroutineScope,
    modalBottomSheetState: SheetState,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        // Screen Header.
        ContentTitle(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(Res.string.issuance_document_offer_screen_title),
        )

        state.relyingPartyHeader?.let { safeRelyingPartyHeader ->
            IssuerHeaderSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SPACING_SMALL.dp),
                header = safeRelyingPartyHeader,
                onEventSend = onEventSend,
            )
        }

        if (state.noDocument) {
            ErrorInfo(
                modifier = Modifier.fillMaxSize(),
                informativeText = stringResource(Res.string.issuance_document_offer_error_no_document)
            )
        } else {
            // Screen Main Content
            MainContent(
                modifier = Modifier.fillMaxSize(),
                documents = state.documents,
            )
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)

                is Effect.ShowBottomSheet -> {
                    onEventSend(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
                }

                is Effect.CloseBottomSheet -> {
                    coroutineScope.launch {
                        modalBottomSheetState.hide()
                    }.invokeOnCompletion {
                        if (!modalBottomSheetState.isVisible) {
                            onEventSend(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
                            onEventSend(Event.BottomSheet.FinishedClosing)
                        } else {
                            onEventSend(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
                        }
                    }
                }
            }
        }.collect()
    }
}

@Composable
private fun MainContent(
    modifier: Modifier = Modifier,
    documents: List<ListItemDataUi>,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = SPACING_SMALL.dp),
        verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp),
    ) {
        items(documents.size) { index ->
            WrapListItem(
                modifier = Modifier.fillMaxWidth(),
                item = documents[index],
                onItemClick = null,
                mainContentVerticalPadding = SPACING_LARGE.dp,
            )
        }
    }
}

private fun handleNavigationEffect(
    navigationEffect: Effect.Navigation,
    navigator: AppNavigator,
    onExternalDeepLink: (link: String, routeToPop: AppRoute?) -> Unit,
    platformActions: PlatformScreenActions,
) {
    when (navigationEffect) {
        is Effect.Navigation.OpenUrlExternally -> {
            platformActions.openUrlExternally(url = navigationEffect.url)
        }

        is Effect.Navigation.SwitchScreen -> {
            navigator.navigateReplacingCurrent(
                route = navigationEffect.route,
                popUpToCurrent = navigationEffect.shouldPopToSelf
            )
        }

        is Effect.Navigation.PopBackStackUpTo -> {
            navigator.popBackStackTo(
                route = navigationEffect.route,
                inclusive = navigationEffect.inclusive
            )
        }

        is Effect.Navigation.DeepLink -> onExternalDeepLink(
            navigationEffect.link,
            navigationEffect.routeToPop,
        )

        is Effect.Navigation.Pop -> navigator.pop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@ThemeModePreviews
@Composable
private fun ContentPreview() {
    PreviewTheme {
        val previewState = State(
            isLoading = false,
            error = null,
            isInitialised = true,
            documents = listOf(
                ListItemDataUi(
                    itemId = "doc_1",
                    mainContentData = ListItemMainContentDataUi.Text(text = "PID")
                )
            ),
            noDocument = false,
            relyingPartyHeader = RelyingPartyHeaderUi(
                relyingParty = RelyingPartyDataUi(
                    isVerified = true,
                    name = UiText.Resource(Res.string.issuance_document_offer_relying_party_default_name),
                    uniqueId = "issuer:preview:prod",
                ),
                intendedUse = "Issuing your personal identification document.",
                privacyPolicyUrl = "https://issuer.example/privacy",
            ),
            offerUiConfig = OfferUiConfig(
                offerUri = "",
                onSuccessNavigation = ConfigNavigation(
                    navigationType = NavigationType.PushRoute(
                        route = DashboardRoute,
                        popUpTo = AddDocumentRoute(
                            IssuanceUiConfig(flowType = IssuanceFlowType.NoDocument)
                        )
                    )
                ),
                onCancelNavigation = ConfigNavigation(
                    navigationType = NavigationType.Pop
                )
            )
        )

        Content(
            state = previewState,
            effectFlow = Channel<Effect>().receiveAsFlow(),
            onEventSend = {},
            onNavigationRequested = {},
            paddingValues = PaddingValues(SPACING_MEDIUM.dp),
            coroutineScope = rememberCoroutineScope(),
            modalBottomSheetState = rememberModalBottomSheetState(),
        )
    }
}