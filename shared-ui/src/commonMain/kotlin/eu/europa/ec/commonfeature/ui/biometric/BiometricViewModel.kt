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

// Phase 3b. Only two things held this view-model back, and both already had an established answer:
//
//  * `android.content.Context`, forwarded to `BiometricInteractor.authenticateWithBiometrics` — which
//    was ALREADY declared in commonMain taking a `PlatformContext`. On Android that resolved through
//    `actual typealias PlatformIntent`-style aliasing, so this VM was the only thing still naming the
//    Android type. It is now the opaque handle, carried and never inspected.
//  * `android.net.Uri` on the deep-link effect. Per the `SuccessViewModel.Effect.Navigation.DeepLink`
//    precedent, a URI crossing into shared code becomes a `String` — and here that *removes* a hop
//    rather than adding one: `NavigationType.Deeplink.link` was already a `String`, which this VM was
//    converting with `.toUri()` only for `BiometricScreen` to hand it back to Android APIs. The
//    conversion now happens once, at the screen, where the Android types actually live.
package eu.europa.ec.commonfeature.ui.biometric

import androidx.lifecycle.viewModelScope
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.provider.PinLockoutState
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.commonfeature.config.BiometricUiConfig
import eu.europa.ec.commonfeature.interactor.BiometricInteractor
import eu.europa.ec.commonfeature.interactor.QuickPinInteractorPinValidPartialState
import eu.europa.ec.commonfeature.ui.pin.buildPinLockoutMessage
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.AppRouteCodec
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

sealed class Event : ViewEvent {
    /**
     * @param context the host context, or null where there is none — iOS, whose [PlatformContext] is
     * uninhabited. Sent either way: iOS's interactor ignores the handle and raises the system prompt
     * itself, so dropping this event was the only thing that had ever made Face ID unreachable there.
     */
    data class OnBiometricsClicked(
        val context: PlatformContext?,
        val shouldThrowErrorIfNotAvailable: Boolean
    ) : Event()

    data object LaunchBiometricSystemScreen : Event()
    data object OnNavigateBack : Event()
    data object OnErrorDismiss : Event()
    data object Init : Event()
    data class OnQuickPinEntered(val quickPin: SecurePin) : Event()
    data class OnQuickPinLengthChanged(val length: Int) : Event()
}

data class State(
    val isLoading: Boolean = false,
    val error: ContentErrorConfig? = null,
    val config: BiometricUiConfig,
    val quickPinError: String? = null,
    val userBiometricsAreEnabled: Boolean = false,
    val isBackable: Boolean = false,
    val notifyOnAuthenticationFailure: Boolean = true,
    val quickPinSize: Int = 6,
    val isLockedOut: Boolean = false,
    val lockoutMessage: UiText? = null
) : ViewState

sealed class Effect : ViewSideEffect {
    data object InitializeBiometricAuthOnCreate : Effect()
    sealed class Navigation : Effect() {
        data class SwitchScreen(
            val route: AppRoute
        ) : Navigation()

        data class PopBackStackUpTo(
            val route: AppRoute,
            val inclusive: Boolean
        ) : Navigation()

        data object LaunchBiometricsSystemScreen : Navigation()
        data class Deeplink(
            val link: String,
            val isPreAuthorization: Boolean,
            val routeToPop: AppRoute? = null
        ) : Navigation()

        data object Pop : Navigation()
        data object Finish : Navigation()
    }
}

@KoinViewModel
class BiometricViewModel(
    private val biometricInteractor: BiometricInteractor,
    @InjectedParam private val config: BiometricUiConfig
) : MviViewModel<Event, State, Effect>() {

    private val biometricUiConfig
        get() = viewState.value.config

    init {
        // Tied to the ViewModel's lifetime, not the composition's — see the note on
        // `OneTimeLaunchedEffect`'s saveable guard in HomeViewModel. Without this, after process
        // death the login screen came up with `userBiometricsAreEnabled` still false, no lockout
        // tick, and no automatic biometric prompt.
        initializeBiometricState()
    }

    private fun initializeBiometricState() {
        viewModelScope.launch {
            val userBiometricsAreEnabled = biometricInteractor.getBiometricUserSelection()
            setState {
                copy(userBiometricsAreEnabled = userBiometricsAreEnabled)
            }
            when (val lockoutState = biometricInteractor.getPinLockoutState()) {
                is PinLockoutState.Active -> startLockoutTick(lockoutState.remaining.inWholeMilliseconds)
                PinLockoutState.Idle -> Unit
            }
            if (
                biometricUiConfig.shouldInitializeBiometricAuthOnCreate
                && userBiometricsAreEnabled
            ) {
                setEffect {
                    Effect.InitializeBiometricAuthOnCreate
                }
            }
        }
    }

    private var lockoutTickJob: Job? = null

    override fun setInitialState(): State {
        return State(
            config = config,
            userBiometricsAreEnabled = false,
            isBackable = config.onBackNavigationConfig.isBackable
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Init -> initializeBiometricState()

            is Event.OnBiometricsClicked -> {
                setState { copy(error = null) }
                when (val availability = biometricInteractor.getBiometricsAvailability()) {
                    is BiometricsAvailability.CanAuthenticate -> authenticate(
                        event.context
                    )

                    is BiometricsAvailability.NonEnrolled -> {
                        if (!event.shouldThrowErrorIfNotAvailable) {
                            return
                        }
                        setEffect {
                            Effect.Navigation.LaunchBiometricsSystemScreen
                        }
                    }

                    is BiometricsAvailability.Failure -> {
                        if (!event.shouldThrowErrorIfNotAvailable) {
                            return
                        }
                        setState {
                            copy(
                                error = ContentErrorConfig(
                                    errorSubTitle = availability.errorMessage.asUiText(),
                                    onCancel = { setEvent(Event.OnErrorDismiss) }
                                )
                            )
                        }
                    }
                }
            }

            is Event.LaunchBiometricSystemScreen -> {
                setState { copy(error = null) }
                biometricInteractor.launchBiometricSystemScreen()
            }

            is Event.OnNavigateBack -> {
                setState { copy(error = null) }
                biometricUiConfig.onBackNavigationConfig.onBackNavigation?.let {
                    doNavigation(navigation = it)
                }
            }

            is Event.OnErrorDismiss -> setState {
                copy(error = null)
            }

            is Event.OnQuickPinEntered -> {
                if (viewState.value.isLockedOut) {
                    event.quickPin.close()
                    return
                }
                setState {
                    copy(
                        quickPinError = null
                    )
                }
                authorizeWithPin(event.quickPin)
            }

            is Event.OnQuickPinLengthChanged -> {
                if (viewState.value.isLockedOut) {
                    return
                }
                setState {
                    copy(
                        quickPinError = null
                    )
                }
            }
        }
    }

    override fun onCleared() {
        lockoutTickJob?.cancel()
        lockoutTickJob = null
        super.onCleared()
    }

    private fun authorizeWithPin(pin: SecurePin) {

        if (pin.length != viewState.value.quickPinSize) {
            pin.close()
            return
        }

        setState {
            copy(
                isLoading = true
            )
        }

        viewModelScope.launch {
            biometricInteractor.isPinValid(pin)
                .collect {
                    when (it) {
                        is QuickPinInteractorPinValidPartialState.Failed -> {
                            when (val lockoutState = biometricInteractor.recordPinFailure()) {
                                is PinLockoutState.Active -> {
                                    setState { copy(isLoading = false) }
                                    startLockoutTick(lockoutState.remaining.inWholeMilliseconds)
                                }

                                PinLockoutState.Idle -> {
                                    setState {
                                        copy(
                                            quickPinError = it.errorMessage,
                                            isLoading = false
                                        )
                                    }
                                }
                            }
                        }

                        is QuickPinInteractorPinValidPartialState.Success -> {
                            biometricInteractor.resetPinThrottle()
                            stopLockoutTick()
                            authenticationSuccess()
                        }
                    }
                }
        }
    }

    private fun authenticate(context: PlatformContext?) {
        biometricInteractor.authenticateWithBiometrics(
            context = context,
            notifyOnAuthenticationFailure = viewState.value.notifyOnAuthenticationFailure
        ) {
            when (it) {
                is BiometricsAuthenticate.Success -> {
                    viewModelScope.launch {
                        biometricInteractor.resetPinThrottle()
                        stopLockoutTick()
                        authenticationSuccess()
                    }
                }

                else -> {}
            }
        }
    }

    private fun authenticationSuccess() {
        doNavigation(navigation = biometricUiConfig.onSuccessNavigation)
    }

    private fun startLockoutTick(initialRemainingMs: Long) {
        lockoutTickJob?.cancel()
        if (initialRemainingMs <= 0L) {
            stopLockoutTick()
            return
        }
        setState {
            copy(
                isLockedOut = true,
                isLoading = false,
                quickPinError = null,
                lockoutMessage = buildLockoutMessage(initialRemainingMs)
            )
        }
        lockoutTickJob = viewModelScope.launch {
            var remaining = initialRemainingMs
            while (remaining > 0L) {
                delay(1_000L)
                remaining -= 1_000L
                if (remaining <= 0L) break
                setState {
                    copy(lockoutMessage = buildLockoutMessage(remaining))
                }
            }
            stopLockoutTick()
        }
    }

    private fun stopLockoutTick() {
        lockoutTickJob?.cancel()
        lockoutTickJob = null
        setState {
            copy(
                isLockedOut = false,
                lockoutMessage = null
            )
        }
    }

    private fun buildLockoutMessage(remainingMs: Long): UiText =
        buildPinLockoutMessage(
            remainingMs = remainingMs,
            maxFailedPinAttempts = biometricInteractor.maxFailedPinAttempts,
        )

    private fun doNavigation(navigation: ConfigNavigation) {
        val navigationEffect: Effect.Navigation = when (val nav = navigation.navigationType) {

            is NavigationType.PopTo -> {
                Effect.Navigation.PopBackStackUpTo(
                    route = nav.route,
                    inclusive = false
                )
            }

            is NavigationType.PushRoute -> {
                Effect.Navigation.SwitchScreen(route = nav.route)
            }

            is NavigationType.Deeplink -> Effect.Navigation.Deeplink(
                link = nav.link,
                isPreAuthorization = viewState.value.config.isPreAuthorization,
                // `routeToPop` is the opaque token that travelled through :core-logic — see
                // AppRouteCodec. It becomes a destination again here, at the edge of the UI layer.
                routeToPop = AppRouteCodec.decode(nav.routeToPop)
            )

            is NavigationType.Pop -> Effect.Navigation.Pop
            is NavigationType.Finish -> Effect.Navigation.Finish
        }

        setEffect {
            navigationEffect
        }
    }
}