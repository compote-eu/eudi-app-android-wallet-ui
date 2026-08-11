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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.shared.resources.resolve
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
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
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.wrap.ActionCardConfig
import eu.europa.ec.uilogic.component.wrap.BottomSheetTextDataUi
import eu.europa.ec.uilogic.component.wrap.BottomSheetWithTwoBigIcons
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.wrap.DialogBottomSheet
import eu.europa.ec.uilogic.component.wrap.GenericBottomSheet
import eu.europa.ec.uilogic.component.wrap.WrapActionCard
import eu.europa.ec.uilogic.component.wrap.WrapIcon
import eu.europa.ec.uilogic.component.wrap.WrapIconButton
import eu.europa.ec.uilogic.component.wrap.WrapModalBottomSheet
import eu.europa.ec.uilogic.extension.paddingFrom
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

    ContentScreen(
        isLoading = state.isLoading,
        navigatableAction = ScreenNavigateAction.NONE,
        onBack = { platformActions.finishApp() },
        topBar = {
            TopBar(
                onEventSent = onDashboardEventSent
            )
        }
    ) { paddingValues ->
        Content(
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
            paddingValues = paddingValues
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

@Composable
private fun TopBar(
    onEventSent: (DashboardEvent) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                all = SPACING_SMALL.dp
            )
    ) {
        // home menu icon
        WrapIconButton(
            modifier = Modifier.align(Alignment.CenterStart),
            iconData = AppIcons.Menu,
            customTint = MaterialTheme.colorScheme.onSurface,
        ) {
            onEventSent(OpenSideMenuEvent)
        }

        // wallet logo
        AppIconAndText(
            modifier = Modifier.align(Alignment.Center),
            appIconAndTextData = AppIconAndTextDataUi()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    state: State,
    effectFlow: Flow<Effect>,
    onEventSent: ((event: Event) -> Unit),
    onNavigationRequested: (navigationEffect: Effect.Navigation) -> Unit,
    coroutineScope: CoroutineScope,
    modalBottomSheetState: SheetState,
    paddingValues: PaddingValues
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .paddingFrom(paddingValues, bottom = false)
            .verticalScroll(scrollState)
            .padding(vertical = SPACING_MEDIUM.dp),
        verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp)
    ) {
        Text(
            text = state.welcomeUserMessage.resolve(),
            style = MaterialTheme.typography.headlineMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            )
        )

        WrapActionCard(
            config = state.authenticateCardConfig,
            onActionClick = {
                onEventSent(
                    Event.AuthenticateCard.AuthenticatePressed
                )
            },
            onLearnMoreClick = {
                onEventSent(
                    Event.AuthenticateCard.LearnMorePressed
                )
            }
        )

        WrapActionCard(
            config = state.signCardConfig,
            onActionClick = {
                onEventSent(
                    Event.SignDocumentCard.SignDocumentPressed
                )
            },
            onLearnMoreClick = {
                onEventSent(
                    Event.SignDocumentCard.LearnMorePressed
                )
            }
        )
    }

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
            topBar = {
                TopBar(
                    onEventSent = {}
                )
            }
        ) { paddingValues ->
            Content(
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
                paddingValues = paddingValues,
            )
        }
    }
}