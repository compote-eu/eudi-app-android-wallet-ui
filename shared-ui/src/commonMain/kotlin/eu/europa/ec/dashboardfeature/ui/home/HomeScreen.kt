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

package eu.europa.ec.dashboardfeature.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.shared.resources.resolve
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.uilogic.component.AppIconAndText
import eu.europa.ec.uilogic.component.AppIconAndTextDataUi
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.EnsureProximityPermissions
import eu.europa.ec.uilogic.component.ModalOptionUi
import eu.europa.ec.uilogic.component.PlatformScreenActions
import eu.europa.ec.uilogic.component.ProximityPermissionsOutcome
import eu.europa.ec.uilogic.component.rememberPlatformScreenActions
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.HSpacer
import eu.europa.ec.uilogic.component.utils.LifecycleEffect
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.wrap.ActionCardConfig
import eu.europa.ec.uilogic.component.wrap.BottomSheetTextDataUi
import eu.europa.ec.uilogic.component.wrap.BottomSheetWithTwoBigIcons
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.wrap.DialogBottomSheet
import eu.europa.ec.uilogic.component.wrap.GenericBottomSheet
import eu.europa.ec.uilogic.component.wrap.WrapIcon
import eu.europa.ec.uilogic.component.wrap.WrapModalBottomSheet
import eu.europa.ec.uilogic.component.ux.UxActionCard
import eu.europa.ec.uilogic.component.ux.UxAppTheme
import eu.europa.ec.uilogic.component.wrap.WrapImage
import eu.europa.ec.ux.elements.UxAppBar
import eu.europa.ec.ux.elements.UxBarLeading
import eu.europa.ec.ux.elements.UxText
import eu.europa.ec.ux.elements.UxTextVariant
import eu.europa.ec.ux.elements.templates.TplHome
import androidx.compose.ui.layout.ContentScale
import eu.europa.ec.shared.resources.content_description_menu_icon
import eu.europa.ec.uilogic.navigation.helper.navigateToRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.dashboard_bottom_sheet_bluetooth_primary_button_text
import eu.europa.ec.shared.resources.dashboard_bottom_sheet_bluetooth_secondary_button_text
import eu.europa.ec.shared.resources.dashboard_bottom_sheet_bluetooth_subtitle
import eu.europa.ec.shared.resources.dashboard_bottom_sheet_bluetooth_title
import eu.europa.ec.shared.resources.home_screen_add_document_option_online
import eu.europa.ec.shared.resources.home_screen_authenticate
import eu.europa.ec.shared.resources.home_screen_authenticate_description
import eu.europa.ec.shared.resources.home_screen_authenticate_learn_more_description
import eu.europa.ec.shared.resources.home_screen_authenticate_learn_more_inner_title
import eu.europa.ec.shared.resources.home_screen_authenticate_option_in_person
import eu.europa.ec.shared.resources.home_screen_authentication_card_title
import eu.europa.ec.shared.resources.home_screen_learn_more
import eu.europa.ec.shared.resources.home_screen_sign
import eu.europa.ec.shared.resources.home_screen_sign_card_title
import eu.europa.ec.shared.resources.home_screen_sign_document
import eu.europa.ec.shared.resources.home_screen_sign_document_description
import eu.europa.ec.shared.resources.home_screen_sign_document_option_from_device
import eu.europa.ec.shared.resources.home_screen_sign_document_option_scan_qr
import eu.europa.ec.shared.resources.home_screen_sign_learn_more_description
import eu.europa.ec.shared.resources.home_screen_sign_learn_more_inner_title

typealias DashboardEvent = eu.europa.ec.dashboardfeature.ui.dashboard.Event
typealias OpenSideMenuEvent = eu.europa.ec.dashboardfeature.ui.dashboard.Event.SideMenu.Open

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navigator: AppNavigator,
    viewModel: HomeViewModel,
    onDashboardEventSent: (DashboardEvent) -> Unit
) {
    val platformActions = rememberPlatformScreenActions()
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    val isBottomSheetOpen = state.isBottomSheetOpen
    val scope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    // RESKIN PROBE (option B): the export's brand over the whole screen; Material parts it still draws are bridged.
    UxAppTheme {
        ContentScreen(
            isLoading = state.isLoading,
            navigatableAction = ScreenNavigateAction.NONE,
            onBack = { platformActions.finishApp() },
        ) { _ ->
            Content(
                onDashboardEventSent = onDashboardEventSent,
                state = state,
                effectFlow = viewModel.effect,
                onEventSent = { event ->
                    viewModel.setEvent(event)
                },
                onNavigationRequested = {
                    handleNavigationEffect(it, navigator, platformActions)
                },
                coroutineScope = scope,
                modalBottomSheetState = bottomSheetState,
            )
        }

        if (isBottomSheetOpen) {
            WrapModalBottomSheet(
                onDismissRequest = {
                    viewModel.setEvent(
                        Event.BottomSheet.UpdateBottomSheetState(
                            isOpen = false
                        )
                    )
                },
                sheetState = bottomSheetState
            ) {
                HomeScreenSheetContent(
                    sheetContent = state.sheetContent,
                    onEventSent = { event -> viewModel.setEvent(event) },
                )
            }
        }
    }

    LifecycleEffect(
        lifecycleOwner = LocalLifecycleOwner.current,
        lifecycleEvent = Lifecycle.Event.ON_RESUME
    ) {
        viewModel.setEvent(Event.OnResume)
    }
}

/** RESKIN PROBE: EL-app-bar (menu) as TPL-home's band; the logo lockup stays the app's (the library has no logo element). */
@Composable
private fun TopBar(
    onEventSent: (DashboardEvent) -> Unit
) {
    UxAppBar(
        id = "home_screen_app_bar",
        leading = UxBarLeading.Menu,
        leadingLabel = stringResource(Res.string.content_description_menu_icon),
        onLeading = { onEventSent(OpenSideMenuEvent) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    onDashboardEventSent: (DashboardEvent) -> Unit,
    state: State,
    effectFlow: Flow<Effect>,
    onEventSent: ((event: Event) -> Unit),
    onNavigationRequested: (navigationEffect: Effect.Navigation) -> Unit,
    coroutineScope: CoroutineScope,
    modalBottomSheetState: SheetState,
) {
    // RESKIN PROBE (option B): TPL-home with generated parts under the export's brand. ContentScreen stays the shell
    // (back = finish, loading overlay); its paddings are not applied because the template owns the insets.
    TplHome(
        id = "home_screen",
        appbar = { TopBar(onEventSent = onDashboardEventSent) },
        header = { AppIconAndText(appIconAndTextData = AppIconAndTextDataUi()) },
        body = {
            UxText("home_screen_welcome", state.welcomeUserMessage.resolve(), UxTextVariant.Title)
            HomeActionCard("home_screen_authenticate_card", state.authenticateCardConfig,
                onAction = { onEventSent(Event.AuthenticateCard.AuthenticatePressed) },
                onLearnMore = { onEventSent(Event.AuthenticateCard.LearnMorePressed) },
            )
            // Omitted rather than disabled where the platform cannot sign: `DocumentSignRoute` has no
            // entry on iOS, so a visible card would navigate nowhere. See `HomeInteractor.canSignDocuments`.
            state.signCardConfig?.let { signCardConfig ->
                HomeActionCard("home_screen_sign_card", signCardConfig,
                    onAction = { onEventSent(Event.SignDocumentCard.SignDocumentPressed) },
                    onLearnMore = { onEventSent(Event.SignDocumentCard.LearnMorePressed) },
                )
            }
        },
    )

    if (state.bleAvailability == BleAvailability.NO_PERMISSION) {
        RequiredPermissionsAsk(state, onEventSent)
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)

                is Effect.CloseBottomSheet -> {
                    coroutineScope.launch {
                        if (effect.hasNextBottomSheet.not()) {
                            modalBottomSheetState.hide()
                        } else {
                            modalBottomSheetState.hide().also {
                                modalBottomSheetState.show()
                                onEventSent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
                            }
                        }
                    }.invokeOnCompletion {
                        if (!modalBottomSheetState.isVisible) {
                            onEventSent(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
                        }
                    }
                }

                is Effect.ShowBottomSheet -> {
                    onEventSent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
                }
            }
        }.collect()
    }
}

@Composable
private fun HomeActionCard(id: String, config: ActionCardConfig, onAction: () -> Unit, onLearnMore: () -> Unit) {
    UxActionCard(
        id = id,
        title = config.title.resolve(),
        primaryLabel = config.primaryButtonText.resolve(),
        learnMoreLabel = config.secondaryButtonText.resolve(),
        onAction = onAction,
        onLearnMore = onLearnMore,
        art = { WrapImage(iconData = config.icon, contentScale = ContentScale.Fit) },
    )
}

private fun handleNavigationEffect(
    navigationEffect: Effect.Navigation,
    navigator: AppNavigator,
    platformActions: PlatformScreenActions,
) {
    when (navigationEffect) {
        is Effect.Navigation.SwitchScreen -> {
            navigator.navigateToRoute(
                route = navigationEffect.route,
                popUpTo = navigationEffect.popUpTo,
                popUpToInclusive = navigationEffect.inclusive,
            )
        }

        is Effect.Navigation.OnAppSettings -> platformActions.openAppSettings()
        is Effect.Navigation.OnSystemSettings -> platformActions.openBluetoothSettings()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenSheetContent(
    sheetContent: HomeScreenBottomSheetContent,
    onEventSent: (event: Event) -> Unit,
) {
    when (sheetContent) {
        is HomeScreenBottomSheetContent.Authenticate -> {
            BottomSheetWithTwoBigIcons(
                textData = BottomSheetTextDataUi(
                    title = UiText.Resource(Res.string.home_screen_authenticate),
                    message = UiText.Resource(Res.string.home_screen_authenticate_description)
                ),
                options = listOf(
                    ModalOptionUi(
                        title = stringResource(Res.string.home_screen_authenticate_option_in_person),
                        leadingIcon = AppIcons.PresentDocumentInPerson,
                        event = Event.BottomSheet.Authenticate.OpenAuthenticateInPerson,
                    ),
                    ModalOptionUi(
                        title = stringResource(Res.string.home_screen_add_document_option_online),
                        leadingIcon = AppIcons.PresentDocumentOnline,
                        event = Event.BottomSheet.Authenticate.OpenAuthenticateOnLine,
                    )
                ),
                onEventSent = { event ->
                    onEventSent(event)
                }
            )
        }

        is HomeScreenBottomSheetContent.Sign -> {
            BottomSheetWithTwoBigIcons(
                textData = BottomSheetTextDataUi(
                    title = UiText.Resource(Res.string.home_screen_sign_document),
                    message = UiText.Resource(Res.string.home_screen_sign_document_description)
                ),
                options = listOf(
                    ModalOptionUi(
                        title = stringResource(Res.string.home_screen_sign_document_option_from_device),
                        leadingIcon = AppIcons.SignDocumentFromDevice,
                        leadingIconTint = ColorKey.Primary,
                        event = Event.BottomSheet.SignDocument.OpenFromDevice,
                    ),
                    ModalOptionUi(
                        title = stringResource(Res.string.home_screen_sign_document_option_scan_qr),
                        leadingIcon = AppIcons.SignDocumentFromQr,
                        leadingIconTint = ColorKey.Primary,
                        event = Event.BottomSheet.SignDocument.OpenScanQR,
                    )
                ),
                onEventSent = { event ->
                    onEventSent(event)
                }
            )
        }

        is HomeScreenBottomSheetContent.LearnMoreAboutAuthenticate -> {
            GenericBottomSheet(
                titleContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WrapIcon(
                            iconData = AppIcons.Info,
                            customTint = MaterialTheme.colorScheme.primary
                        )
                        HSpacer.Small()
                        Text(
                            text = stringResource(Res.string.home_screen_authenticate),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                        )
                    }
                },
                bodyContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp)) {
                        Text(
                            stringResource(Res.string.home_screen_sign_learn_more_inner_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Text(
                            stringResource(Res.string.home_screen_sign_learn_more_description),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            )
        }

        is HomeScreenBottomSheetContent.LearnMoreAboutSignDocument -> {
            GenericBottomSheet(
                titleContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WrapIcon(
                            iconData = AppIcons.Info,
                            customTint = MaterialTheme.colorScheme.primary
                        )
                        HSpacer.Small()
                        Text(
                            stringResource(Res.string.home_screen_sign),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                },
                bodyContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp)) {
                        Text(
                            stringResource(Res.string.home_screen_authenticate_learn_more_inner_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Text(
                            stringResource(Res.string.home_screen_authenticate_learn_more_description),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            )
        }

        is HomeScreenBottomSheetContent.Bluetooth -> {
            DialogBottomSheet(
                textData = BottomSheetTextDataUi(
                    title = UiText.Resource(Res.string.dashboard_bottom_sheet_bluetooth_title),
                    message = UiText.Resource(Res.string.dashboard_bottom_sheet_bluetooth_subtitle),
                    positiveButtonText = UiText.Resource(Res.string.dashboard_bottom_sheet_bluetooth_primary_button_text),
                    negativeButtonText = UiText.Resource(Res.string.dashboard_bottom_sheet_bluetooth_secondary_button_text),
                ),
                onPositiveClick = {
                    onEventSent(
                        Event.BottomSheet.Bluetooth.PrimaryButtonPressed(
                            sheetContent.availability
                        )
                    )
                },
                onNegativeClick = { onEventSent(Event.BottomSheet.Bluetooth.SecondaryButtonPressed) }
            )
        }
    }
}

/**
 * Bridges the platform permission seam to this screen's events. The Android/iOS split lives in
 * `EnsureProximityPermissions`; what each outcome *means* to Home stays here.
 */
@Composable
private fun RequiredPermissionsAsk(
    state: State,
    onEventSend: (Event) -> Unit
) {
    EnsureProximityPermissions(
        isBleCentralClientModeEnabled = state.isBleCentralClientModeEnabled,
    ) { outcome ->
        when (outcome) {
            ProximityPermissionsOutcome.Granted -> onEventSend(Event.StartProximityFlow)
            ProximityPermissionsOutcome.NeedsRationale -> onEventSend(Event.OnShowPermissionsRational)
            ProximityPermissionsOutcome.Pending ->
                onEventSend(Event.OnPermissionStateChanged(BleAvailability.UNKNOWN))
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@ThemeModePreviews
@Composable
private fun HomeScreenContentPreview() {
    PreviewTheme {
        ContentScreen(
            isLoading = false,
            navigatableAction = ScreenNavigateAction.NONE,
            onBack = { },
        ) { _ ->
            Content(
                onDashboardEventSent = {},
                state = State(
                    isBottomSheetOpen = false,
                    welcomeUserMessage = "Welcome back, Alex".asUiText(),
                    authenticateCardConfig = ActionCardConfig(
                        title = UiText.Resource(Res.string.home_screen_authentication_card_title),
                        icon = AppIcons.WalletActivated,
                        primaryButtonText = UiText.Resource(Res.string.home_screen_authenticate),
                        secondaryButtonText = UiText.Resource(Res.string.home_screen_learn_more),
                    ),
                    signCardConfig = ActionCardConfig(
                        title = UiText.Resource(Res.string.home_screen_sign_card_title),
                        icon = AppIcons.Contract,
                        primaryButtonText = UiText.Resource(Res.string.home_screen_sign),
                        secondaryButtonText = UiText.Resource(Res.string.home_screen_learn_more),
                    )

                ),
                effectFlow = Channel<Effect>().receiveAsFlow(),
                onNavigationRequested = {},
                coroutineScope = rememberCoroutineScope(),
                modalBottomSheetState = rememberModalBottomSheetState(),
                onEventSent = {},
            )
        }
    }
}