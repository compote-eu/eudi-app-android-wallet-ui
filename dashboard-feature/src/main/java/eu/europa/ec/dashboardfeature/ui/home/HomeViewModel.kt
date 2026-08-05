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

import androidx.lifecycle.viewModelScope
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.commonfeature.config.QrScanUiConfig
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.dashboardfeature.interactor.HomeInteractor
import eu.europa.ec.dashboardfeature.interactor.HomeInteractorGetUserNameViaMainPidDocumentPartialState
import eu.europa.ec.dashboardfeature.ui.home.HomeScreenBottomSheetContent.Bluetooth
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentSignRoute
import eu.europa.ec.shared.navigation.ProximityQrRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.home_screen_authenticate
import eu.europa.ec.shared.resources.home_screen_authentication_card_title
import eu.europa.ec.shared.resources.home_screen_learn_more
import eu.europa.ec.shared.resources.home_screen_sign
import eu.europa.ec.shared.resources.home_screen_sign_card_title
import eu.europa.ec.shared.resources.home_screen_welcome
import eu.europa.ec.shared.resources.home_screen_welcome_user_message
import eu.europa.ec.shared.resources.presentation_qr_scan_subtitle
import eu.europa.ec.shared.resources.presentation_qr_scan_title
import eu.europa.ec.shared.resources.signature_qr_scan_subtitle
import eu.europa.ec.shared.resources.signature_qr_scan_title
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

    val welcomeUserMessage: String,
    val authenticateCardConfig: ActionCardConfig,
    val signCardConfig: ActionCardConfig,

    val bleAvailability: BleAvailability = BleAvailability.UNKNOWN,
    val isBleCentralClientModeEnabled: Boolean = false
) : ViewState

sealed class Event : ViewEvent {
    data object Init : Event()
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
    private val resourceProvider: ResourceProvider
) : MviViewModel<Event, State, Effect>() {

    override fun setInitialState(): State {
        return State(
            welcomeUserMessage = resourceProvider.getString(Res.string.home_screen_welcome),
            authenticateCardConfig = ActionCardConfig(
                title = resourceProvider.getString(Res.string.home_screen_authentication_card_title),
                icon = AppIcons.IdCards,
                primaryButtonText = resourceProvider.getString(Res.string.home_screen_authenticate),
                secondaryButtonText = resourceProvider.getString(Res.string.home_screen_learn_more)
            ),
            signCardConfig = ActionCardConfig(
                title = resourceProvider.getString(Res.string.home_screen_sign_card_title),
                icon = AppIcons.Contract,
                primaryButtonText = resourceProvider.getString(Res.string.home_screen_sign),
                secondaryButtonText = resourceProvider.getString(Res.string.home_screen_learn_more)
            ),
            isBleCentralClientModeEnabled = homeInteractor.isBleCentralClientModeEnabled(),
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Init -> {
                getUserNameViaMainPidDocument()
            }

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
                config = QrScanUiConfig(
                    title = resourceProvider.getString(Res.string.signature_qr_scan_title),
                    subTitle = resourceProvider.getString(Res.string.signature_qr_scan_subtitle),
                    qrScanFlow = QrScanFlow.Signature
                )
            )
        )
        setEffect {
            navigationEffect
        }
    }

    private fun navigateToQrScan() {
        val navigationEffect = Effect.Navigation.SwitchScreen(
            route = QrScanRoute(
                config = QrScanUiConfig(
                    title = resourceProvider.getString(Res.string.presentation_qr_scan_title),
                    subTitle = resourceProvider.getString(Res.string.presentation_qr_scan_subtitle),
                    qrScanFlow = QrScanFlow.Presentation
                )
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
                                    resourceProvider.getString(
                                        Res.string.home_screen_welcome_user_message,
                                        response.userFirstName
                                    )
                                } else resourceProvider.getString(Res.string.home_screen_welcome)
                            )
                        }
                    }
                }
            }
        }
    }
}