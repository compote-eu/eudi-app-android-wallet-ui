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

package eu.europa.ec.dashboardfeature.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsItemUi
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsMenuItemType
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.generic_cancel
import eu.europa.ec.shared.resources.resolve
import eu.europa.ec.shared.resources.settings_intent_chooser_logs_share_title
import eu.europa.ec.shared.resources.settings_screen_biometrics_not_enrolled_message
import eu.europa.ec.shared.resources.settings_screen_biometrics_not_enrolled_primary_button
import eu.europa.ec.shared.resources.settings_screen_biometrics_not_enrolled_title
import eu.europa.ec.shared.resources.settings_screen_option_retrieve_logs
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemLeadingContentDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.PlatformScreenActions
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ContentTitle
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.rememberPlatformContextOrNull
import eu.europa.ec.uilogic.component.rememberPlatformScreenActions
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.wrap.BottomSheetTextDataUi
import eu.europa.ec.uilogic.component.wrap.DialogBottomSheet
import eu.europa.ec.uilogic.component.wrap.WrapListItem
import eu.europa.ec.uilogic.component.wrap.WrapModalBottomSheet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navigator: AppNavigator,
    viewModel: SettingsViewModel,
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    // Null on iOS, where there is no host context to raise a biometric prompt with. Only the
    // biometrics row needs one, and it is passed on rather than used as a gate: that row IS shown on
    // iOS, whose prompt the system owns and raises without a host.
    val platformContext = rememberPlatformContextOrNull()
    val platformActions = rememberPlatformScreenActions()
    val snackbarHostState = remember { SnackbarHostState() }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ContentScreen(
        navigatableAction = ScreenNavigateAction.BACKABLE,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                snackbar = { snackbarData ->
                    Snackbar(snackbarData = snackbarData)
                }
            )
        },
        isLoading = state.isLoading,
        onBack = { viewModel.setEvent(Event.Pop) }
    ) { paddingValues ->
        Content(
            state = state,
            effectFlow = viewModel.effect,
            onEventSend = { viewModel.setEvent(it) },
            onNavigationRequested = { navigationEffect ->
                handleNavigationEffect(
                    navigationEffect = navigationEffect,
                    navigator = navigator,
                    platformActions = platformActions,
                    onLaunchBiometricSystemScreen = {
                        viewModel.setEvent(Event.LaunchBiometricSystemScreen)
                    }
                )
            },
            snackbarHostState = snackbarHostState,
            platformContext = platformContext,
            platformActions = platformActions,
            paddingValues = paddingValues,
        )
    }

    if (state.isBottomSheetOpen) {
        WrapModalBottomSheet(
            onDismissRequest = {
                viewModel.setEvent(Event.UpdateBottomSheetState(isOpen = false))
            },
            sheetState = bottomSheetState,
        ) {
            DialogBottomSheet(
                textData = BottomSheetTextDataUi(
                    title = UiText.Resource(Res.string.settings_screen_biometrics_not_enrolled_title),
                    message = UiText.Resource(
                        Res.string.settings_screen_biometrics_not_enrolled_message
                    ),
                    positiveButtonText = UiText.Resource(
                        Res.string.settings_screen_biometrics_not_enrolled_primary_button
                    ),
                    negativeButtonText = UiText.Resource(Res.string.generic_cancel),
                ),
                leadingIcon = AppIcons.TouchId,
                onPositiveClick = {
                    viewModel.setEvent(Event.BiometricEnrolmentSettingsPressed)
                },
                onNegativeClick = {
                    viewModel.setEvent(Event.UpdateBottomSheetState(isOpen = false))
                },
            )
        }
    }
}

private fun handleNavigationEffect(
    navigationEffect: Effect.Navigation,
    navigator: AppNavigator,
    platformActions: PlatformScreenActions,
    onLaunchBiometricSystemScreen: () -> Unit,
) {
    when (navigationEffect) {
        is Effect.Navigation.Pop -> navigator.pop()

        is Effect.Navigation.LaunchBiometricsSystemScreen -> onLaunchBiometricSystemScreen()

        is Effect.Navigation.OpenUrlExternally -> platformActions.openUrlExternally(
            navigationEffect.url
        )
    }
}

@Composable
private fun Content(
    state: State,
    effectFlow: Flow<Effect>,
    onEventSend: (Event) -> Unit,
    onNavigationRequested: (Effect.Navigation) -> Unit,
    snackbarHostState: SnackbarHostState,
    platformContext: PlatformContext?,
    platformActions: PlatformScreenActions,
    paddingValues: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ContentTitle(
                modifier = Modifier.fillMaxWidth(),
                title = state.screenTitle.resolve(),
            )

            SettingsItems(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                items = state.settingsItems,
                onEventSent = onEventSend,
                platformContext = platformContext,
            )
        }

        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SPACING_MEDIUM.dp),
            text = state.appVersion,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }

    // Resolved here rather than in the view-model: the effect collector below is a coroutine, not a
    // composable, so `UiText.resolve()` is unavailable there — and a chooser title is pure presentation.
    val logShareChooserTitle = stringResource(Res.string.settings_intent_chooser_logs_share_title)

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)
                is Effect.ShareLogFiles -> {
                    platformActions.shareFiles(
                        paths = effect.paths,
                        title = logShareChooserTitle,
                    )
                }

                is Effect.ShowSnackbar -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch {
                        snackbarHostState.showSnackbar(
                            message = effect.message,
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            }
        }.collect()
    }
}

@Composable
private fun SettingsItems(
    modifier: Modifier = Modifier,
    items: List<SettingsItemUi>,
    onEventSent: (Event) -> Unit,
    platformContext: PlatformContext?,
) {
    Column(
        modifier = modifier
    ) {
        items.forEachIndexed { index, settingsItemUi ->
            WrapListItem(
                modifier = Modifier.fillMaxWidth(),
                item = settingsItemUi.data,
                onItemClick = {
                    onEventSent(
                        Event.ItemClicked(
                            itemType = settingsItemUi.type,
                            context = platformContext
                        )
                    )
                },
                throttleClicks = false,
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                mainContentVerticalPadding = SPACING_MEDIUM.dp,
            )


            if (index != items.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = SPACING_SMALL.dp)
                )
            }
        }
    }
}

@ThemeModePreviews
@Composable
private fun SettingsScreenPreview() {
    PreviewTheme {
        val settingsItems = listOf(
            SettingsItemUi(
                type = SettingsMenuItemType.RETRIEVE_LOGS,
                data = ListItemDataUi(
                    itemId = SettingsMenuItemType.RETRIEVE_LOGS.itemId,
                    mainContentData = ListItemMainContentDataUi.Text(
                        text = stringResource(Res.string.settings_screen_option_retrieve_logs)
                    ),
                    leadingContentData = ListItemLeadingContentDataUi.Icon(
                        iconData = AppIcons.OpenNew
                    ),
                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                        iconData = AppIcons.KeyboardArrowRight
                    )
                )
            )
        )

        Content(
            state = State(
                settingsItems = settingsItems,
                appVersion = "1.0.0",
                changelogUrl = "",
            ),
            effectFlow = emptyFlow(),
            onEventSend = {},
            onNavigationRequested = {},
            snackbarHostState = remember { SnackbarHostState() },
            platformContext = rememberPlatformContextOrNull(),
            platformActions = rememberPlatformScreenActions(),
            paddingValues = PaddingValues(SPACING_MEDIUM.dp)
        )
    }
}
