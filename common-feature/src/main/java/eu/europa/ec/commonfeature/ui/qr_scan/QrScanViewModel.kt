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

package eu.europa.ec.commonfeature.ui.qr_scan

import android.content.Context
import androidx.lifecycle.viewModelScope
import eu.europa.ec.businesslogic.validator.Form
import eu.europa.ec.businesslogic.validator.Rule
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.commonfeature.config.QrScanUiConfig
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.interactor.QrScanInteractor
import eu.europa.ec.eudi.rqesui.domain.extension.toUriOrEmpty
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

private const val MAX_ALLOWED_FAILED_SCANS = 5

data class State(
    val hasCameraPermission: Boolean = false,
    val shouldShowPermissionRational: Boolean = false,
    val finishedScanning: Boolean = false,
    val qrScannedConfig: QrScanUiConfig,

    val failedScanAttempts: Int = 0,
    val showInformativeText: Boolean = false,
    val informativeText: String,
) : ViewState

sealed class Event : ViewEvent {
    data object GoBack : Event()
    data class OnQrScanned(val context: Context, val resultQr: String) : Event()
    data object CameraAccessGranted : Event()
    data object ShowPermissionRational : Event()
    data object GoToAppSettings : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data class SwitchScreen(val route: AppRoute) : Navigation()
        data object Pop : Navigation()
        data object GoToAppSettings : Navigation()
    }
}

@KoinViewModel
class QrScanViewModel(
    private val interactor: QrScanInteractor,
    private val resourceProvider: ResourceProvider,
    @InjectedParam private val qrScannedConfig: QrScanUiConfig,
) : MviViewModel<Event, State, Effect>() {

    override fun setInitialState(): State {
        return State(
            qrScannedConfig = qrScannedConfig,
            informativeText = calculateInformativeText(qrScannedConfig.qrScanFlow)
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.GoBack -> setEffect { Effect.Navigation.Pop }

            is Event.OnQrScanned -> {
                if (viewState.value.finishedScanning) {
                    return
                }
                setState {
                    copy(finishedScanning = true)
                }

                handleScannedQr(context = event.context, scannedQr = event.resultQr)
            }

            is Event.CameraAccessGranted -> {
                setState {
                    copy(hasCameraPermission = true)
                }
            }

            is Event.ShowPermissionRational -> {
                setState {
                    copy(shouldShowPermissionRational = true)
                }
            }

            is Event.GoToAppSettings -> setEffect { Effect.Navigation.GoToAppSettings }
        }
    }

    private fun handleScannedQr(context: Context, scannedQr: String) {
        viewModelScope.launch {
            val currentState = viewState.value

            // Validate the scanned QR code
            val urlIsValid = validateForm(
                form = Form(
                    inputs = mapOf(
                        listOf(
                            Rule.ValidateUrl(
                                errorMessage = "",
                                shouldValidateSchema = true,
                                shouldValidateHost = false,
                                shouldValidatePath = false,
                                shouldValidateQuery = true,
                            )
                        ) to scannedQr
                    )
                )
            )

            // Handle valid QR code
            if (urlIsValid) {
                calculateNextStep(
                    context = context,
                    qrScanFlow = currentState.qrScannedConfig.qrScanFlow,
                    scanResult = scannedQr
                )
            } else {
                // Increment failed attempts
                val updatedFailedAttempts = currentState.failedScanAttempts + 1
                val maxFailedAttemptsExceeded = updatedFailedAttempts > MAX_ALLOWED_FAILED_SCANS

                setState {
                    copy(
                        failedScanAttempts = updatedFailedAttempts,
                        showInformativeText = maxFailedAttemptsExceeded,
                        finishedScanning = false,
                    )
                }
            }
        }
    }

    private suspend fun validateForm(form: Form): Boolean {
        val validationResult = interactor.validateForm(
            form = form,
        )
        return validationResult.isValid
    }

    private fun calculateNextStep(
        context: Context,
        qrScanFlow: QrScanFlow,
        scanResult: String,
    ) {
        when (qrScanFlow) {
            is QrScanFlow.Presentation -> navigateToPresentationRequest(scanResult)
            is QrScanFlow.Issuance -> navigateToDocumentOffer(
                scanResult = scanResult,
                issuanceFlowType = qrScanFlow.issuanceFlowType
            )

            is QrScanFlow.Signature -> navigateToRqesSdk(context, scanResult)
        }
    }

    private fun calculateInformativeText(
        qrScanFlow: QrScanFlow,
    ): String {
        return with(resourceProvider) {
            when (qrScanFlow) {
                is QrScanFlow.Presentation -> getString(R.string.qr_scan_informative_text_presentation_flow)
                is QrScanFlow.Issuance -> getString(R.string.qr_scan_informative_text_issuance_flow)
                is QrScanFlow.Signature -> getString(R.string.qr_scan_informative_text_signature_flow)
            }
        }
    }

    private fun navigateToPresentationRequest(scanResult: String) {
        setEffect {
            Effect.Navigation.SwitchScreen(
                route = PresentationRequestRoute(
                    config = RequestUriConfig(
                        PresentationMode.OpenId4Vp(
                            uri = scanResult,
                            initiatorRoute = DashboardRoute
                        )
                    )
                )
            )
        }
    }

    private fun navigateToDocumentOffer(scanResult: String, issuanceFlowType: IssuanceFlowType) {
        setEffect {
            Effect.Navigation.SwitchScreen(
                route = DocumentOfferRoute(
                    config = OfferUiConfig(
                        offerUri = scanResult,
                        onSuccessNavigation = calculateOnSuccessNavigation(
                            issuanceFlowType
                        ),
                        onCancelNavigation = calculateOnCancelNavigation(
                            issuanceFlowType
                        )
                    )
                )
            )
        }
    }

    private fun navigateToRqesSdk(context: Context, scanResult: String) {
        interactor.launchRqesSdk(
            context = context,
            uri = scanResult.toUriOrEmpty()
        )
        setEffect {
            Effect.Navigation.Pop
        }
    }

    private fun calculateOnSuccessNavigation(issuanceFlowType: IssuanceFlowType): ConfigNavigation {
        return when (issuanceFlowType) {
            is IssuanceFlowType.NoDocument -> {
                ConfigNavigation(
                    navigationType = NavigationType.PushRoute(
                        route = DashboardRoute,
                        popUpTo = AddDocumentRoute(
                            config = IssuanceUiConfig(flowType = issuanceFlowType)
                        )
                    )
                )
            }

            is IssuanceFlowType.ExtraDocument -> {
                ConfigNavigation(
                    navigationType = NavigationType.PopTo(
                        route = DashboardRoute
                    )
                )
            }
        }
    }

    private fun calculateOnCancelNavigation(issuanceFlowType: IssuanceFlowType): ConfigNavigation {
        return when (issuanceFlowType) {
            is IssuanceFlowType.NoDocument -> {
                ConfigNavigation(
                    navigationType = NavigationType.Pop
                )
            }

            is IssuanceFlowType.ExtraDocument -> {
                ConfigNavigation(
                    navigationType = NavigationType.Pop
                )
            }
        }
    }
}