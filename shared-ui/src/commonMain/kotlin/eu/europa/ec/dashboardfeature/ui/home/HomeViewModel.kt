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

// Phase 3b: the third feature view-model in commonMain, and the first whose whole job is navigation —
// every path out of Home (proximity, QR presentation, QR signature, document signing) is now a typed
// `AppRoute` built here, so iOS gets the routing decisions for free and only has to render the cards.
//
// It moved with just one extraction: `ActionCardConfig`, which was co-located with `WrapActionCard` in
// :ui-logic despite being KMP-clean data. Nothing else was in the way — the Phase-3a string seam had
// already made the welcome message and both card configs `UiText`, so no `ResourceProvider` is
// injected, and the BLE state it tracks is plain enums decided by the screen's permission composable
// rather than any Android type reaching in here. Package unchanged, so `HomeScreen` and the dashboard
// `entryProvider` are untouched.
package eu.europa.ec.dashboardfeature.ui.home

import androidx.lifecycle.viewModelScope
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.commonfeature.config.QrScanUiConfig
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.dashboardfeature.interactor.HomeInteractor
import eu.europa.ec.dashboardfeature.interactor.HomeInteractorGetUserNameViaMainPidDocumentPartialState
import eu.europa.ec.dashboardfeature.ui.home.HomeScreenBottomSheetContent.Bluetooth
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentSignRoute
import eu.europa.ec.shared.navigation.ProximityQrRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.home_screen_authenticate
import eu.europa.ec.shared.resources.home_screen_authentication_card_title
import eu.europa.ec.shared.resources.home_screen_learn_more
import eu.europa.ec.shared.resources.home_screen_sign
import eu.europa.ec.shared.resources.home_screen_sign_card_title
import eu.europa.ec.shared.resources.home_screen_welcome
import eu.europa.ec.shared.resources.home_screen_welcome_user_message
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.wrap.ActionCardConfig
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

enum class BleAvailability {
    AVAILABLE, NO_PERMISSION, DISABLED, UNKNOWN
}

data class State(
    val isLoading: Boolean = false,
    val isBottomSheetOpen: Boolean = false,
    val sheetContent: HomeScreenBottomSheetContent = HomeScreenBottomSheetContent.Authenticate,

    val welcomeUserMessage: UiText,
    val authenticateCardConfig: ActionCardConfig,
    val signCardConfig: ActionCardConfig,

    val bleAvailability: BleAvailability = BleAvailability.UNKNOWN,
    val isBleCentralClientModeEnabled: Boolean = false
) : ViewState

sealed class Event : ViewEvent {
    data object StartProximityFlow : Event()

    sealed class AuthenticateCard : Event() {
        data object AuthenticatePressed : Event()
        data object LearnMorePressed : Event()
    }

    sealed class SignDocumentCard : Event() {
        data object SignDocumentPressed : Event()
        data object LearnMorePressed : Event()
    }

    sealed class BottomSheet : Event() {
        data class UpdateBottomSheetState(val isOpen: Boolean) : BottomSheet()
        data object Close : BottomSheet()

        sealed class Authenticate : BottomSheet() {
            data object OpenAuthenticateInPerson : Authenticate()
            data object OpenAuthenticateOnLine : Authenticate()
        }

        sealed class SignDocument : BottomSheet() {
            data object OpenFromDevice : Authenticate()
            data object OpenScanQR : Authenticate()
        }

        sealed class Bluetooth : BottomSheet() {
            data class PrimaryButtonPressed(val availability: BleAvailability) : Bluetooth()
            data object SecondaryButtonPressed : Bluetooth()
        }
    }

    data object OnShowPermissionsRational : Event()
    data class OnPermissionStateChanged(val availability: BleAvailability) : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data class SwitchScreen(
            val route: AppRoute,
            val popUpTo: AppRoute = DashboardRoute,
            val inclusive: Boolean = false,
        ) : Navigation()

        data object OnAppSettings : Navigation()
        data object OnSystemSettings : Navigation()
    }

    data object ShowBottomSheet : Effect()
    data class CloseBottomSheet(val hasNextBottomSheet: Boolean) : Effect()
}

sealed class HomeScreenBottomSheetContent {
    data object Authenticate : HomeScreenBottomSheetContent()
    data object LearnMoreAboutAuthenticate : HomeScreenBottomSheetContent()
    data object LearnMoreAboutSignDocument : HomeScreenBottomSheetContent()
    data object Sign : HomeScreenBottomSheetContent()

    data class Bluetooth(val availability: BleAvailability) : HomeScreenBottomSheetContent()
}

@KoinViewModel
class HomeViewModel(
    private val homeInteractor: HomeInteractor,
) : MviViewModel<Event, State, Effect>() {

    init {
        // Belongs to the ViewModel's lifetime, not the composition's. It used to be started by an
        // `Event.Init` sent from `OneTimeLaunchedEffect`, whose "already ran" flag is
        // `rememberSaveable` — so after process death the flag was restored as `true` and the event
        // was never re-sent to this brand-new ViewModel, which is why Home came back reading
        // "Welcome" instead of "Welcome, <name>". `init` runs exactly once per instance, surviving
        // configuration change and re-running after process death.
        getUserNameViaMainPidDocument()
    }

    override fun setInitialState(): State {
        return State(
            welcomeUserMessage = UiText.Resource(Res.string.home_screen_welcome),
            authenticateCardConfig = ActionCardConfig(
                title = UiText.Resource(Res.string.home_screen_authentication_card_title),
                icon = AppIcons.IdCards,
                primaryButtonText = UiText.Resource(Res.string.home_screen_authenticate),
                secondaryButtonText = UiText.Resource(Res.string.home_screen_learn_more)
            ),
            signCardConfig = ActionCardConfig(
                title = UiText.Resource(Res.string.home_screen_sign_card_title),
                icon = AppIcons.Contract,
                primaryButtonText = UiText.Resource(Res.string.home_screen_sign),
                secondaryButtonText = UiText.Resource(Res.string.home_screen_learn_more)
            ),
            isBleCentralClientModeEnabled = homeInteractor.isBleCentralClientModeEnabled(),
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.AuthenticateCard.AuthenticatePressed -> showBottomSheet(
                sheetContent = HomeScreenBottomSheetContent.Authenticate
            )

            is Event.AuthenticateCard.LearnMorePressed -> showBottomSheet(
                sheetContent = HomeScreenBottomSheetContent.LearnMoreAboutAuthenticate
            )

            is Event.SignDocumentCard.SignDocumentPressed -> showBottomSheet(
                sheetContent = HomeScreenBottomSheetContent.Sign
            )

            is Event.SignDocumentCard.LearnMorePressed -> showBottomSheet(
                sheetContent = HomeScreenBottomSheetContent.LearnMoreAboutSignDocument
            )

            is Event.BottomSheet.UpdateBottomSheetState -> {
                setState {
                    copy(isBottomSheetOpen = event.isOpen)
                }
            }

            is Event.BottomSheet.Close -> {
                hideBottomSheet()
            }

            is Event.BottomSheet.Authenticate.OpenAuthenticateInPerson -> {
                checkIfBluetoothIsEnabled()
            }

            is Event.BottomSheet.Authenticate.OpenAuthenticateOnLine -> {
                hideBottomSheet()
                navigateToQrScan()
            }

            is Event.BottomSheet.SignDocument.OpenFromDevice -> {
                hideBottomSheet()
                navigateToDocumentSign()
            }

            is Event.BottomSheet.SignDocument.OpenScanQR -> {
                hideBottomSheet()
                navigateToQrSignatureScan()
            }

            is Event.OnPermissionStateChanged -> {
                setState { copy(bleAvailability = event.availability) }
            }

            is Event.OnShowPermissionsRational -> {
                setState { copy(bleAvailability = BleAvailability.UNKNOWN) }
                showBottomSheet(
                    sheetContent = Bluetooth(
                        BleAvailability.NO_PERMISSION
                    )
                )
            }

            is Event.StartProximityFlow -> {
                hideBottomSheet()
                startProximityFlow()
            }

            is Event.BottomSheet.Bluetooth.PrimaryButtonPressed -> {
                hideBottomSheet()
                onBleUserAction(event.availability)
            }

            is Event.BottomSheet.Bluetooth.SecondaryButtonPressed -> {
                hideBottomSheet()
            }
        }
    }

    private fun checkIfBluetoothIsEnabled() {
        if (homeInteractor.isBleAvailable()) {
            setState { copy(bleAvailability = BleAvailability.NO_PERMISSION) }
        } else {
            setState { copy(bleAvailability = BleAvailability.DISABLED) }
            hideAndShowNextBottomSheet()
            showBottomSheet(
                sheetContent = Bluetooth(BleAvailability.DISABLED)
            )
        }
    }

    private fun onBleUserAction(availability: BleAvailability) {
        when (availability) {
            BleAvailability.NO_PERMISSION -> {
                setEffect { Effect.Navigation.OnAppSettings }
            }

            BleAvailability.DISABLED -> {
                setEffect { Effect.Navigation.OnSystemSettings }
            }

            else -> {
                // no implementation
            }
        }
    }

    private fun showBottomSheet(sheetContent: HomeScreenBottomSheetContent) {
        setState {
            copy(sheetContent = sheetContent)
        }
        setEffect {
            Effect.ShowBottomSheet
        }
    }

    private fun hideBottomSheet() {
        setEffect {
            Effect.CloseBottomSheet(false)
        }
    }

    private fun hideAndShowNextBottomSheet() {
        setEffect {
            Effect.CloseBottomSheet(true)
        }
    }

    private fun navigateToDocumentSign() {
        setEffect {
            Effect.Navigation.SwitchScreen(
                route = DocumentSignRoute
            )
        }
    }

    private fun startProximityFlow() {
        setState { copy(bleAvailability = BleAvailability.AVAILABLE) }
        setEffect {
            Effect.Navigation.SwitchScreen(
                route = ProximityQrRoute(
                    config = RequestUriConfig(PresentationMode.Ble(DashboardRoute))
                )
            )
        }
    }

    private fun navigateToQrSignatureScan() {
        val navigationEffect = Effect.Navigation.SwitchScreen(
            route = QrScanRoute(
                config = QrScanUiConfig(qrScanFlow = QrScanFlow.Signature)
            )
        )
        setEffect {
            navigationEffect
        }
    }

    private fun navigateToQrScan() {
        val navigationEffect = Effect.Navigation.SwitchScreen(
            route = QrScanRoute(
                config = QrScanUiConfig(qrScanFlow = QrScanFlow.Presentation)
            )
        )
        setEffect {
            navigationEffect
        }
    }

    private fun getUserNameViaMainPidDocument() {
        setState {
            copy(
                isLoading = true
            )
        }
        viewModelScope.launch {
            homeInteractor.getUserNameViaMainPidDocument().collect { response ->
                when (response) {
                    is HomeInteractorGetUserNameViaMainPidDocumentPartialState.Failure -> {
                        setState {
                            copy(
                                isLoading = false,
                            )
                        }
                    }

                    is HomeInteractorGetUserNameViaMainPidDocumentPartialState.Success -> {
                        setState {
                            copy(
                                isLoading = false,
                                welcomeUserMessage = if (response.userFirstName.isNotBlank()) {
                                    UiText.Resource(
                                        Res.string.home_screen_welcome_user_message,
                                        response.userFirstName
                                    )
                                } else UiText.Resource(Res.string.home_screen_welcome)
                            )
                        }
                    }
                }
            }
        }
    }
}