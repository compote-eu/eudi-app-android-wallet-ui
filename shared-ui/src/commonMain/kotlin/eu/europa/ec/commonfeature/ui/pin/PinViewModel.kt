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

// Phase 3b: the fourth feature view-model in commonMain, and the one that carries the app's PIN rules —
// the enter/re-enter/validate progression, the "PINs do not match" retry, and the lockout countdown are
// now decided once for both platforms.
//
// Getting here needed four contracts lifted rather than any change to this file: `SecurePin` (+ the
// `SecurePinData` handle) and `PinLockoutState` to :shared-logic, and `QuickPinInteractor` plus the
// stranded `ScreenNavigateAction` enum to this module. The PIN implementations deliberately stayed
// Android-side — see the note on `@Synchronized` in :shared-logic's SecurePin.kt. Packages are
// unchanged, so `PinScreen`, `WrapSecurePinTextField` and the entryProviders are untouched.
//
// Note this view-model never sees PIN characters: `SecurePin` reaches it from the screen as an opaque,
// single-use handle that it forwards to the interactor, which is what lets the whole flow be shared
// without the PIN itself becoming platform-neutral data.
package eu.europa.ec.commonfeature.ui.pin

import androidx.lifecycle.viewModelScope
import eu.europa.ec.authenticationlogic.provider.PinLockoutState
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.commonfeature.interactor.QuickPinInteractor
import eu.europa.ec.commonfeature.interactor.QuickPinInteractorPinValidPartialState
import eu.europa.ec.commonfeature.interactor.QuickPinInteractorSetPinPartialState
import eu.europa.ec.commonfeature.model.PinFlow
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.QuickPinRoute
import eu.europa.ec.shared.navigation.SuccessRoute
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
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
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.quick_pin_change_enter_new_subtitle
import eu.europa.ec.shared.resources.quick_pin_change_reenter_new_subtitle
import eu.europa.ec.shared.resources.quick_pin_change_success_btn
import eu.europa.ec.shared.resources.quick_pin_change_success_description
import eu.europa.ec.shared.resources.quick_pin_change_success_text
import eu.europa.ec.shared.resources.quick_pin_change_title
import eu.europa.ec.shared.resources.quick_pin_change_validate_current_subtitle
import eu.europa.ec.shared.resources.quick_pin_create_enter_pin_input_label
import eu.europa.ec.shared.resources.quick_pin_create_reenter_pin_input_label
import eu.europa.ec.shared.resources.quick_pin_create_subtitle
import eu.europa.ec.shared.resources.quick_pin_create_success_btn
import eu.europa.ec.shared.resources.quick_pin_create_success_description
import eu.europa.ec.shared.resources.quick_pin_create_success_no_activation_btn
import eu.europa.ec.shared.resources.quick_pin_create_success_no_activation_description
import eu.europa.ec.shared.resources.quick_pin_create_success_text
import eu.europa.ec.shared.resources.quick_pin_create_title
import eu.europa.ec.shared.resources.quick_pin_locked_out

enum class PinValidationState {
    ENTER,
    REENTER,
    VALIDATE
}

data class State(
    private val pinFlow: PinFlow,
    val isLoading: Boolean = false,
    val quickPinError: String? = null,
    val subtitle: UiText = UiText.Empty,
    val title: UiText = UiText.Empty,
    val pinInputLabel: UiText? = null,
    val resetPin: Boolean = false,
    val pinState: PinValidationState,
    val isBottomSheetOpen: Boolean = false,
    val quickPinSize: Int = 6,
    val isLockedOut: Boolean = false,
    val lockoutMessage: UiText? = null
) : ViewState {
    val action: ScreenNavigateAction
        get() {
            return when (pinFlow) {
                PinFlow.CREATE_WITH_ACTIVATION, PinFlow.CREATE_WITHOUT_ACTIVATION -> ScreenNavigateAction.NONE
                PinFlow.UPDATE -> ScreenNavigateAction.CANCELABLE
            }
        }

    val onBackEvent: Event
        get() {
            return when (pinFlow) {
                PinFlow.CREATE_WITH_ACTIVATION, PinFlow.CREATE_WITHOUT_ACTIVATION -> Event.Finish
                PinFlow.UPDATE -> Event.CancelPressed
            }
        }
}

sealed class Event : ViewEvent {
    data object Init : Event()
    data class PinEntered(val pin: SecurePin) : Event()
    data object OnQuickPinLengthChanged : Event()
    data object CancelPressed : Event()
    data object Finish : Event()
    sealed class BottomSheet : Event() {
        data class UpdateBottomSheetState(val isOpen: Boolean) : BottomSheet()

        sealed class Cancel : BottomSheet() {
            data object PrimaryButtonPressed : Cancel()
            data object SecondaryButtonPressed : Cancel()
        }
    }
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data class SwitchScreen(val route: AppRoute) : Navigation()

        data object Pop : Navigation()
        data object Finish : Navigation()
    }

    data object ShowBottomSheet : Effect()
    data object CloseBottomSheet : Effect()
}

@KoinViewModel
class PinViewModel(
    private val interactor: QuickPinInteractor,
    @InjectedParam private val pinFlow: PinFlow
) : MviViewModel<Event, State, Effect>() {

    private var enteredPin: SecurePin? = null
    private var lockoutTickJob: Job? = null

    init {
        // Tied to the ViewModel's lifetime, not the composition's — see the note on
        // `OneTimeLaunchedEffect`'s saveable guard in HomeViewModel. Without this an active PIN
        // lockout was not surfaced after process death.
        restorePinLockoutState()
    }

    private fun restorePinLockoutState() {
        if (viewState.value.pinState != PinValidationState.VALIDATE) return
        viewModelScope.launch {
            when (val lockoutState = interactor.getPinLockoutState()) {
                is PinLockoutState.Active -> startLockoutTick(lockoutState.remaining.inWholeMilliseconds)
                PinLockoutState.Idle -> Unit
            }
        }
    }

    override fun setInitialState(): State {
        val title: UiText
        val subtitle: UiText
        val pinInputLabel: UiText?
        val pinState: PinValidationState

        when (pinFlow) {
            PinFlow.CREATE_WITH_ACTIVATION, PinFlow.CREATE_WITHOUT_ACTIVATION -> {
                title = UiText.Resource(Res.string.quick_pin_create_title)
                subtitle = UiText.Resource(Res.string.quick_pin_create_subtitle)
                pinState = PinValidationState.ENTER
                pinInputLabel = calculatePinInputLabel(pinState)
            }

            PinFlow.UPDATE -> {
                title = UiText.Resource(Res.string.quick_pin_change_title)
                subtitle = UiText.Resource(Res.string.quick_pin_change_validate_current_subtitle)
                pinState = PinValidationState.VALIDATE
                pinInputLabel = calculatePinInputLabel(pinState)
            }
        }

        return State(
            isLoading = false,
            title = title,
            subtitle = subtitle,
            pinInputLabel = pinInputLabel,
            pinState = pinState,
            pinFlow = pinFlow
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Init -> restorePinLockoutState()

            is Event.OnQuickPinLengthChanged -> {
                if (viewState.value.isLockedOut) return
                handleQuickPinLengthChanged()
            }

            is Event.PinEntered -> {
                val state = viewState.value
                if (state.isLockedOut) {
                    event.pin.close()
                    return
                }

                when (state.pinState) {
                    PinValidationState.ENTER -> {
                        // Set state for re-enter phase
                        setupReenterPhase(enteredPin = event.pin)
                    }

                    PinValidationState.REENTER -> {
                        // Save the new pin
                        saveNewPin(newPin = event.pin)
                    }

                    PinValidationState.VALIDATE -> {
                        validatePin(currentPin = event.pin)
                    }
                }
            }

            is Event.CancelPressed -> {
                showBottomSheet()
            }

            is Event.BottomSheet.UpdateBottomSheetState -> {
                setState {
                    copy(isBottomSheetOpen = event.isOpen)
                }
            }

            is Event.BottomSheet.Cancel.PrimaryButtonPressed -> {
                hideBottomSheet()
            }

            is Event.BottomSheet.Cancel.SecondaryButtonPressed -> {
                viewModelScope.launch {
                    clearPendingPin()
                    hideBottomSheet()
                    artificialDelay(time = 200L)
                    setEffect { Effect.Navigation.Pop }
                }
            }

            is Event.Finish -> {
                clearPendingPin()
                setEffect { Effect.Navigation.Finish }
            }
        }
    }

    override fun onCleared() {
        clearPendingPin()
        lockoutTickJob?.cancel()
        lockoutTickJob = null
        super.onCleared()
    }

    private fun validatePin(currentPin: SecurePin) {
        setState { copy(isLoading = true) }
        viewModelScope.launch {
            interactor.isCurrentPinValid(
                pin = currentPin
            ).collect {
                when (it) {
                    is QuickPinInteractorPinValidPartialState.Failed -> {
                        artificialDelay()
                        when (val lockoutState = interactor.recordPinFailure()) {
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

                    QuickPinInteractorPinValidPartialState.Success -> {
                        interactor.resetPinThrottle()
                        stopLockoutTick()
                        setupEnterPhase()
                    }
                }
            }
        }
    }

    private fun setupEnterPhase() {
        val newPinState = PinValidationState.ENTER
        setState {
            copy(
                quickPinError = null,
                pinState = newPinState,
                resetPin = true,
                subtitle = calculateSubtitle(newPinState),
                pinInputLabel = calculatePinInputLabel(newPinState),
                isLoading = false
            )
        }
    }

    private fun setupReenterPhase(enteredPin: SecurePin) {
        viewModelScope.launch {
            setState {
                copy(isLoading = true)
            }

            artificialDelay()

            val newPinState = PinValidationState.REENTER
            replacePendingPin(enteredPin)
            setState {
                copy(
                    quickPinError = null,
                    pinState = PinValidationState.REENTER,
                    resetPin = true,
                    subtitle = calculateSubtitle(newPinState),
                    pinInputLabel = calculatePinInputLabel(newPinState),
                    isLoading = false
                )
            }
        }
    }

    private fun saveNewPin(newPin: SecurePin) {

        val initialPin = enteredPin ?: run {
            newPin.close()
            return
        }

        setState { copy(isLoading = true) }

        viewModelScope.launch {
            interactor.setPin(
                newPin = newPin,
                initialPin = initialPin
            ).collect {
                when (it) {
                    is QuickPinInteractorSetPinPartialState.Failed -> {
                        artificialDelay()
                        setState {
                            copy(
                                quickPinError = it.errorMessage,
                                isLoading = false
                            )
                        }
                    }

                    is QuickPinInteractorSetPinPartialState.Success -> {
                        clearPendingPin()
                        setEffect {
                            Effect.Navigation.SwitchScreen(getNextRoute())
                        }
                    }
                }
            }
        }
    }

    private fun handleQuickPinLengthChanged() {
        setState {
            copy(
                quickPinError = null,
                resetPin = false
            )
        }
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
            maxFailedPinAttempts = interactor.maxFailedPinAttempts,
        )

    private fun calculateSubtitle(pinState: PinValidationState): UiText {
        return UiText.Resource(
            when (pinFlow) {
                PinFlow.UPDATE -> {
                    when (pinState) {
                        PinValidationState.ENTER -> Res.string.quick_pin_change_enter_new_subtitle
                        PinValidationState.REENTER -> Res.string.quick_pin_change_reenter_new_subtitle
                        PinValidationState.VALIDATE -> Res.string.quick_pin_change_validate_current_subtitle
                    }
                }

                PinFlow.CREATE_WITH_ACTIVATION,
                PinFlow.CREATE_WITHOUT_ACTIVATION -> Res.string.quick_pin_create_subtitle
            }
        )
    }

    private fun calculatePinInputLabel(pinState: PinValidationState): UiText? {
        return when (pinFlow) {
            PinFlow.CREATE_WITHOUT_ACTIVATION,
            PinFlow.CREATE_WITH_ACTIVATION -> {
                when (pinState) {
                    PinValidationState.ENTER -> UiText.Resource(Res.string.quick_pin_create_enter_pin_input_label)
                    PinValidationState.REENTER -> UiText.Resource(Res.string.quick_pin_create_reenter_pin_input_label)
                    PinValidationState.VALIDATE -> null
                }
            }

            PinFlow.UPDATE -> null
        }
    }

    /**
     * Suspends the coroutine for a specified amount of time to simulate processing or
     * to ensure UI transitions occur at a natural pace.
     *
     * @param time The duration of the delay in milliseconds. Defaults to 500ms.
     */
    private suspend fun artificialDelay(time: Long = 500L) {
        delay(time)
    }

    private fun getNextRoute(): AppRoute {

        val navigationAfterCreate = ConfigNavigation(
            navigationType = NavigationType.PushRoute(
                route = AddDocumentRoute(
                    config = IssuanceUiConfig(flowType = IssuanceFlowType.NoDocument)
                ),
                popUpTo = QuickPinRoute(pinFlow)
            ),
        )

        val navigationAfterUpdate = ConfigNavigation(
            navigationType = NavigationType.PopTo(DashboardRoute),
        )

        val navigationAfterCreateNoActivation = ConfigNavigation(
            navigationType = NavigationType.PushRoute(
                route = DashboardRoute,
                popUpTo = QuickPinRoute(pinFlow)
            ),
        )

        return SuccessRoute(
            config = SuccessUIConfig(
                textElementsConfig = SuccessUIConfig.TextElementsConfig(
                    text = UiText.Resource(
                        when (pinFlow) {
                            PinFlow.CREATE_WITH_ACTIVATION,
                            PinFlow.CREATE_WITHOUT_ACTIVATION -> Res.string.quick_pin_create_success_text

                            PinFlow.UPDATE -> Res.string.quick_pin_change_success_text
                        }
                    ),
                    description = UiText.Resource(
                        when (pinFlow) {
                            PinFlow.CREATE_WITH_ACTIVATION -> Res.string.quick_pin_create_success_description
                            PinFlow.CREATE_WITHOUT_ACTIVATION -> Res.string.quick_pin_create_success_no_activation_description
                            PinFlow.UPDATE -> Res.string.quick_pin_change_success_description
                        }
                    )
                ),
                imageConfig = when (pinFlow) {
                    PinFlow.CREATE_WITH_ACTIVATION, PinFlow.CREATE_WITHOUT_ACTIVATION -> SuccessUIConfig.ImageConfig(
                        type = SuccessUIConfig.ImageConfig.Type.Drawable(
                            icon = AppIcons.WalletSecured
                        ),
                        tint = null,
                    )

                    PinFlow.UPDATE -> SuccessUIConfig.ImageConfig()
                },
                buttonConfig = listOf(
                    SuccessUIConfig.ButtonConfig(
                        text = UiText.Resource(
                            when (pinFlow) {
                                PinFlow.CREATE_WITH_ACTIVATION -> Res.string.quick_pin_create_success_btn
                                PinFlow.CREATE_WITHOUT_ACTIVATION -> Res.string.quick_pin_create_success_no_activation_btn
                                PinFlow.UPDATE -> Res.string.quick_pin_change_success_btn
                            }
                        ),
                        style = SuccessUIConfig.ButtonConfig.Style.PRIMARY,
                        navigation = when (pinFlow) {
                            PinFlow.CREATE_WITH_ACTIVATION -> navigationAfterCreate
                            PinFlow.CREATE_WITHOUT_ACTIVATION -> navigationAfterCreateNoActivation
                            PinFlow.UPDATE -> navigationAfterUpdate
                        }
                    )
                ),
                onBackScreenToNavigate = when (pinFlow) {
                    PinFlow.CREATE_WITH_ACTIVATION -> navigationAfterCreate
                    PinFlow.CREATE_WITHOUT_ACTIVATION -> navigationAfterCreateNoActivation
                    PinFlow.UPDATE -> navigationAfterUpdate
                },
            )
        )
    }

    private fun showBottomSheet() {
        setEffect {
            Effect.ShowBottomSheet
        }
    }

    private fun hideBottomSheet() {
        setEffect {
            Effect.CloseBottomSheet
        }
    }

    private fun replacePendingPin(pin: SecurePin) {
        clearPendingPin()
        enteredPin = pin
    }

    private fun clearPendingPin() {
        enteredPin?.close()
        enteredPin = null
    }
}