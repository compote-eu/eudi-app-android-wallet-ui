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

package eu.europa.ec.dashboardfeature.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.corelogic.model.RevokedDocumentDataDomain
import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.dashboardfeature.ui.component.BottomNavigationBar
import eu.europa.ec.dashboardfeature.ui.component.BottomNavigationItem
import eu.europa.ec.dashboardfeature.ui.dashboard.sidemenu.SideMenuScreen
import eu.europa.ec.dashboardfeature.ui.documents.list.DocumentsScreen
import eu.europa.ec.dashboardfeature.ui.documents.list.DocumentsViewModel
import eu.europa.ec.dashboardfeature.ui.home.HomeScreen
import eu.europa.ec.dashboardfeature.ui.home.HomeViewModel
import eu.europa.ec.dashboardfeature.ui.transactions.list.TransactionsScreen
import eu.europa.ec.dashboardfeature.ui.transactions.list.TransactionsViewModel
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.dashboard_bottom_sheet_revoked_document_dialog_subtitle
import eu.europa.ec.shared.resources.dashboard_bottom_sheet_revoked_document_dialog_title
import eu.europa.ec.uilogic.component.PlatformScreenActions
import eu.europa.ec.uilogic.component.SystemBroadcastReceiver
import eu.europa.ec.uilogic.component.rememberPlatformScreenActions
import eu.europa.ec.uilogic.component.utils.LifecycleEffect
import eu.europa.ec.uilogic.component.wrap.BottomSheetTextDataUi
import eu.europa.ec.uilogic.component.wrap.BottomSheetWithOptionsList
import eu.europa.ec.uilogic.component.wrap.WrapModalBottomSheet
import eu.europa.ec.uilogic.navigation.helper.IntentAction
import eu.europa.ec.uilogic.navigation.helper.navigateToRoute
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** `NavHost`'s default `fadeIn`/`fadeOut` duration, carried over so tab switches look unchanged. */
private const val TAB_CROSSFADE_DURATION_MS = 700

/**
 * One reading of the host's pending launch intent: the link it carries and, failing that, the app
 * action it is.
 *
 * Both come from a *single* read because taking the intent consumes it — which is also why the screen
 * receives the pair rather than two separate lambdas it could accidentally call twice.
 */
data class PendingLaunchIntent(
    val deepLink: String? = null,
    val intentAction: IntentAction? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navigator: AppNavigator,
    viewModel: DashboardViewModel,
    documentsViewModel: DocumentsViewModel,
    homeViewModel: HomeViewModel,
    transactionsViewModel: TransactionsViewModel,
    /**
     * The intent the app was (re)launched with, read once per resume. Injected rather than reached
     * through a seam because Android's answer — `Context.getPendingIntent()` on the one-shot slot in
     * `EudiComponentActivity` — lives in `:ui-logic`, which depends on this module.
     */
    pendingLaunchIntent: () -> PendingLaunchIntent = { PendingLaunchIntent() },
    /**
     * Hands an external deep link to whatever owns it, navigating to [AppRoute] when the link resolves
     * to one.
     *
     * Injected for the same reason as [pendingLaunchIntent], and additionally because Android's
     * `handleDeepLinkAction` reaches the Android-only RQES UI SDK. The iOS default does nothing: iOS
     * deep links arrive through the app delegate and are not wired yet.
     */
    onExternalDeepLink: (link: String, route: AppRoute?) -> Unit = { _, _ -> },
    /**
     * Parks an [IntentAction] for [AppRoute] and goes there — the DC API hand-off. Injected because
     * Android's `handleIntentAction` writes to the same activity-scoped slot in `:ui-logic`. Unreachable
     * on iOS, where [IntentAction] cannot be constructed.
     */
    onIntentAction: (action: IntentAction, route: AppRoute?) -> Unit = { _, _ -> },
    /**
     * Reads the revoked documents out of a revocation broadcast, or null when the intent carries no
     * such extra — the same distinction `Intent.getParcelableArrayListExtra` makes, kept because an
     * *empty* list is a broadcast that fires the event with nothing in it.
     *
     * Injected rather than added to the `PlatformIntent` accessor vocabulary in :shared-logic: the
     * extra is an `ArrayList<RevokedDocumentParcel>`, and that `Parcelable` transport lives in
     * `:core-logic` — a module neither shared module can see. Which broadcast means which event stays
     * here, as app logic; only the field access is the host's. Never called on iOS, where
     * `SystemBroadcastReceiver` is a no-op.
     */
    revokedDocumentsFromBroadcast: (PlatformIntent) -> List<RevokedDocumentDataDomain>? = { null },
) {
    val platformActions = rememberPlatformScreenActions()

    var selectedTab by rememberSaveable(stateSaver = BottomNavigationItem.Saver) {
        mutableStateOf(BottomNavigationItem.Home)
    }
    // What the nested NavHost's `saveState`/`restoreState` pair used to provide: each tab's
    // `rememberSaveable`-backed UI state (scroll positions, the documents FAB visibility) is kept
    // under its own key while the tab is off screen, and handed back when it returns.
    val tabStateHolder = rememberSaveableStateHolder()

    val state: State by viewModel.viewState.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    Scaffold(
        bottomBar = {
            BottomNavigationBar(
                selectedItem = selectedTab,
                onItemSelected = { selectedTab = it },
            )
        }
    ) { padding ->
        val paddingValues = PaddingValues(
            bottom = padding.calculateBottomPadding()
        )

        // `Crossfade` matches what `NavHost` did by default between destinations: a plain
        // 700ms fade, with the outgoing tab left composed until it finishes.
        Crossfade(
            targetState = selectedTab,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues),
            animationSpec = tween(TAB_CROSSFADE_DURATION_MS),
            label = "bottomNavigationTab",
        ) { tab ->
            tabStateHolder.SaveableStateProvider(tab.id) {
                when (tab) {
                    BottomNavigationItem.Home -> HomeScreen(
                        navigator,
                        homeViewModel,
                        onDashboardEventSent = { event ->
                            viewModel.setEvent(event)
                        }
                    )

                    BottomNavigationItem.Documents -> DocumentsScreen(
                        navigator,
                        documentsViewModel,
                        onDashboardEventSent = { event ->
                            viewModel.setEvent(event)
                        }
                    )

                    BottomNavigationItem.Transactions -> TransactionsScreen(
                        navigator,
                        transactionsViewModel,
                        onDashboardEventSent = { event ->
                            viewModel.setEvent(event)
                        }
                    )
                }
            }
        }

        if (state.isBottomSheetOpen) {
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
                DashboardSheetContent(
                    sheetContent = state.sheetContent,
                    onEventSent = {
                        viewModel.setEvent(it)
                    }
                )
            }
        }
    }

    AnimatedVisibility(
        visible = state.isSideMenuVisible,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = when (state.sideMenuAnimation) {
            SideMenuAnimation.SLIDE -> slideOutHorizontally(targetOffsetX = { it })
            SideMenuAnimation.FADE -> fadeOut(animationSpec = tween(state.menuAnimationDuration))
        }
    ) {
        SideMenuScreen(
            state = state,
            onEventSent = { event -> viewModel.setEvent(event) }
        )
    }

    LifecycleEffect(
        lifecycleOwner = LocalLifecycleOwner.current,
        lifecycleEvent = Lifecycle.Event.ON_RESUME
    ) {
        // The pending intent is one-shot, so it is read once and both readings of it are handed to the
        // view-model: the link it carries and, failing that, the app action it is.
        val pending = pendingLaunchIntent()
        viewModel.setEvent(
            Event.Init(
                deepLink = pending.deepLink,
                intentAction = pending.intentAction,
            )
        )
    }

    LaunchedEffect(Unit) {
        viewModel.effect.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> handleNavigationEffect(
                    navigationEffect = effect,
                    navigator = navigator,
                    platformActions = platformActions,
                    onExternalDeepLink = onExternalDeepLink,
                    onIntentAction = onIntentAction,
                )

                is Effect.CloseBottomSheet -> {
                    scope.launch {
                        bottomSheetState.hide()
                    }.invokeOnCompletion {
                        if (!bottomSheetState.isVisible) {
                            viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
                        }
                    }
                }

                is Effect.ShowBottomSheet -> {
                    viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
                }

            }
        }.collect()
    }

    SystemBroadcastReceiver(
        intentFilters = listOf(
            CoreActions.REVOCATION_WORK_MESSAGE_ACTION
        )
    ) { intent ->
        intent?.let { revokedDocumentsFromBroadcast(it) }?.let { revokedDocuments ->
            viewModel.setEvent(
                Event.DocumentRevocationNotificationReceived(revokedDocuments)
            )
        }
    }
}

private fun handleNavigationEffect(
    navigationEffect: Effect.Navigation,
    navigator: AppNavigator,
    platformActions: PlatformScreenActions,
    onExternalDeepLink: (link: String, route: AppRoute?) -> Unit,
    onIntentAction: (action: IntentAction, route: AppRoute?) -> Unit,
) {
    when (navigationEffect) {
        is Effect.Navigation.Pop -> platformActions.finishApp()
        is Effect.Navigation.SwitchScreen -> {
            navigator.navigateToRoute(
                route = navigationEffect.route,
                popUpTo = navigationEffect.popUpTo,
                popUpToInclusive = navigationEffect.inclusive,
            )
        }

        is Effect.Navigation.OpenDeepLinkAction -> onExternalDeepLink(
            navigationEffect.deepLinkUri,
            navigationEffect.route,
        )

        is Effect.Navigation.OpenIntentAction -> onIntentAction(
            navigationEffect.intentAction,
            navigationEffect.route,
        )

        is Effect.Navigation.OnAppSettings -> platformActions.openAppSettings()
        is Effect.Navigation.OnSystemSettings -> platformActions.openBluetoothSettings()
        is Effect.Navigation.OpenUrlExternally -> platformActions.openUrlExternally(
            navigationEffect.url
        )
    }
}

@Composable
private fun DashboardSheetContent(
    sheetContent: DashboardBottomSheetContent,
    onEventSent: (even: Event) -> Unit,
) {
    when (sheetContent) {
        is DashboardBottomSheetContent.DocumentRevocation -> {
            BottomSheetWithOptionsList(
                textData = BottomSheetTextDataUi(
                    title = UiText.Resource(
                        Res.string.dashboard_bottom_sheet_revoked_document_dialog_title
                    ),
                    message = UiText.Resource(
                        Res.string.dashboard_bottom_sheet_revoked_document_dialog_subtitle
                    ),
                ),
                options = sheetContent.options,
                onEventSent = onEventSent,
            )
        }
    }
}
