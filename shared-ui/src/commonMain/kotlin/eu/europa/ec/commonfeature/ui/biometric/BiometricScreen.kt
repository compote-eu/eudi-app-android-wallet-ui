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

package eu.europa.ec.commonfeature.ui.biometric

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.commonfeature.config.BiometricMode
import eu.europa.ec.commonfeature.config.BiometricUiConfig
import eu.europa.ec.commonfeature.config.OnBackNavigationConfig
import eu.europa.ec.commonfeature.util.TestTag
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.biometric_default_mode_text_above_pin_field
import eu.europa.ec.shared.resources.loading_biometry_biometrics_enabled_description
import eu.europa.ec.shared.resources.loading_biometry_biometrics_not_enabled_description
import eu.europa.ec.shared.resources.resolve
import eu.europa.ec.uilogic.component.AppIconAndText
import eu.europa.ec.uilogic.component.AppIconAndTextDataUi
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.content.ContentHeader
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.rememberPlatformContextOrNull
import eu.europa.ec.uilogic.component.rememberPlatformScreenActions
import eu.europa.ec.uilogic.component.content.ImePaddingConfig
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.SIZE_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.wrap.SecurePinTextFieldState
import eu.europa.ec.uilogic.component.wrap.WrapIconButton
import eu.europa.ec.uilogic.component.wrap.WrapSecurePinTextField
import eu.europa.ec.uilogic.component.wrap.rememberSecurePinTextFieldState
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.extension.applyTestTag
import eu.europa.ec.uilogic.extension.paddingFrom
import eu.europa.ec.uilogic.navigation.helper.navigateReplacingCurrent
import eu.europa.ec.uilogic.navigation.helper.popBackStackTo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

@Composable
fun BiometricScreen(
    navigator: AppNavigator,
    viewModel: BiometricViewModel,
    /**
     * Hands an external deep link to whatever flow owns it, parking it for [AppRoute] first when one is
     * given — the pre-authorization case navigates there instead of popping back to it.
     *
     * Injected as on the other shared screens: Android's answers (`cacheUri`, `handleDeepLinkAction`) live
     * in `:ui-logic`, which depends on this module. The iOS default does nothing; iOS deep links reach the
     * screens that own them through `IosDeepLinks`.
     */
    onExternalDeepLink: (link: String, routeToPop: AppRoute?, isPreAuthorization: Boolean) -> Unit =
        { _, _, _ -> },
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    val platformActions = rememberPlatformScreenActions()
    // Null on iOS: the biometric prompt needs an Android context, and iOS has no login prompt yet.
    val platformContext = rememberPlatformContextOrNull()
    val pinInputState = rememberSecurePinTextFieldState(
        expectedPinLength = state.quickPinSize
    )

    ContentScreen(
        isLoading = state.isLoading,
        navigatableAction = if (state.isBackable) {
            ScreenNavigateAction.BACKABLE
        } else {
            ScreenNavigateAction.NONE
        },
        onBack = {
            viewModel.setEvent(Event.OnNavigateBack)
        },
        contentErrorConfig = state.error,
        imePaddingConfig = ImePaddingConfig.ONLY_CONTENT
    ) {
        Body(
            state = state,
            effectFlow = viewModel.effect,
            onEventSent = { event -> viewModel.setEvent(event) },
            onNavigationRequested = { navigationEffect ->
                when (navigationEffect) {
                    is Effect.Navigation.SwitchScreen -> {
                        navigator.navigateReplacingCurrent(navigationEffect.route)
                    }

                    is Effect.Navigation.LaunchBiometricsSystemScreen -> {
                        viewModel.setEvent(Event.LaunchBiometricSystemScreen)
                    }

                    is Effect.Navigation.PopBackStackUpTo -> {
                        navigator.popBackStackTo(
                            route = navigationEffect.route,
                            inclusive = navigationEffect.inclusive
                        )
                    }

                    is Effect.Navigation.Deeplink -> onExternalDeepLink(
                        navigationEffect.link,
                        navigationEffect.routeToPop,
                        navigationEffect.isPreAuthorization,
                    )

                    is Effect.Navigation.Pop -> navigator.pop()
                    is Effect.Navigation.Finish -> platformActions.finishApp()
                }
            },
            padding = it,
            pinInputState = pinInputState,
            platformContext = platformContext,
        )
    }

}

@Composable
private fun Body(
    state: State,
    effectFlow: Flow<Effect>,
    onEventSent: ((event: Event) -> Unit),
    onNavigationRequested: ((navigationEffect: Effect.Navigation) -> Unit),
    padding: PaddingValues,
    pinInputState: SecurePinTextFieldState,
    // Null on iOS, where there is no biometric prompt to raise: the button and the on-create attempt are
    // simply skipped, and the screen is the PIN entry it already contains.
    platformContext: PlatformContext?,
) {

    Column(
        Modifier
            .fillMaxSize()
            .paddingFrom(padding, bottom = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            MainContent(
                state = state,
                onEventSent = onEventSent,
                pinInputState = pinInputState,
            )
        }

        if (state.userBiometricsAreEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 5.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                WrapIconButton(
                    iconData = AppIcons.TouchId,
                    onClick = {
                        onEventSent(
                            Event.OnBiometricsClicked(
                                context = platformContext,
                                shouldThrowErrorIfNotAvailable = true
                            )
                        )
                    }
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> {
                    onNavigationRequested(effect)
                }

                is Effect.InitializeBiometricAuthOnCreate -> {
                    onEventSent(
                        Event.OnBiometricsClicked(
                            context = platformContext,
                            shouldThrowErrorIfNotAvailable = false,
                        )
                    )
                }
            }
        }.collect()
    }
}

@Composable
private fun MainContent(
    state: State,
    onEventSent: (event: Event) -> Unit,
    pinInputState: SecurePinTextFieldState
) {
    when (val mode = state.config.mode) {
        is BiometricMode.Default -> {
            val description = if (state.userBiometricsAreEnabled) {
                mode.descriptionWhenBiometricsEnabled
            } else {
                mode.descriptionWhenBiometricsNotEnabled
            }
            ContentHeader(
                modifier = Modifier.fillMaxWidth(),
                config = ContentHeaderConfig(description = description)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SPACING_SMALL.dp)
            ) {
                Text(
                    modifier = Modifier
                        .applyTestTag(TestTag.BiometricScreen.PIN_TEXT)
                        .fillMaxWidth()
                        .padding(vertical = SPACING_SMALL.dp),
                    text = mode.textAbovePin.resolve(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                PinFieldLayout(
                    modifier = Modifier.fillMaxWidth(),
                    state = state,
                    pinInputState = pinInputState,
                    onPinInput = { quickPin ->
                        onEventSent(Event.OnQuickPinLengthChanged(quickPin))
                    },
                    onPinComplete = { securePin ->
                        onEventSent(Event.OnQuickPinEntered(securePin))
                    }
                )
            }
        }

        is BiometricMode.Login -> {
            AppIconAndText(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SPACING_LARGE.dp),
                appIconAndTextData = AppIconAndTextDataUi(),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SPACING_LARGE.dp),
                verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp, Alignment.Top)
            ) {
                Text(
                    text = mode.title.resolve(),
                    modifier = Modifier.applyTestTag(TestTag.BiometricScreen.PIN_TITLE),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                val subtitle = if (state.userBiometricsAreEnabled) {
                    mode.subTitleWhenBiometricsEnabled
                } else {
                    mode.subTitleWhenBiometricsNotEnabled
                }
                Text(
                    text = subtitle.resolve(),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            PinFieldLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SPACING_LARGE.dp),
                state = state,
                pinInputState = pinInputState,
                onPinInput = { quickPin ->
                    onEventSent(Event.OnQuickPinLengthChanged(quickPin))
                },
                onPinComplete = { securePin ->
                    onEventSent(Event.OnQuickPinEntered(securePin))
                }
            )
        }
    }
}

@Composable
private fun PinFieldLayout(
    modifier: Modifier = Modifier,
    state: State,
    pinInputState: SecurePinTextFieldState,
    onPinInput: (Int) -> Unit,
    onPinComplete: (SecurePin) -> Unit,
) {
    WrapSecurePinTextField(
        modifier = modifier,
        state = pinInputState,
        onPinLengthChanged = onPinInput,
        onPinComplete = onPinComplete,
        hasError = !state.quickPinError.isNullOrEmpty() || state.isLockedOut,
        errorMessage = state.lockoutMessage?.resolve() ?: state.quickPinError,
        pinWidth = 42.dp,
        focusOnCreate = !state.userBiometricsAreEnabled && !state.isLockedOut,
        shouldHideKeyboardOnCompletion = true,
        enabled = !state.isLoading && !state.isLockedOut
    )
}

/**
 * Preview composable of [Body].
 */
@ThemeModePreviews
@Composable
private fun PreviewBiometricScreen() {
    PreviewTheme {
        Body(
            state = State(
                config = BiometricUiConfig(
                    mode = BiometricMode.Default(
                        descriptionWhenBiometricsEnabled = UiText.Resource(Res.string.loading_biometry_biometrics_enabled_description),
                        descriptionWhenBiometricsNotEnabled = UiText.Resource(Res.string.loading_biometry_biometrics_not_enabled_description),
                        textAbovePin = UiText.Resource(Res.string.biometric_default_mode_text_above_pin_field),
                    ),
                    isPreAuthorization = true,
                    onSuccessNavigation = ConfigNavigation(
                        navigationType = NavigationType.PushRoute(DashboardRoute)
                    ),
                    onBackNavigationConfig = OnBackNavigationConfig(
                        onBackNavigation = ConfigNavigation(
                            navigationType = NavigationType.PushRoute(DashboardRoute),
                        ),
                        hasToolbarBackIcon = true
                    )
                )
            ),
            effectFlow = Channel<Effect>().receiveAsFlow(),
            onEventSent = {},
            onNavigationRequested = {},
            platformContext = rememberPlatformContextOrNull(),
            padding = PaddingValues(SIZE_MEDIUM.dp),
            pinInputState = rememberSecurePinTextFieldState(expectedPinLength = 6)
        )
    }
}