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

// Phase 3b: the QR scanner's view-model, moved to commonMain. Its only Android-touching parts were an
// `android.content.Context` threaded through for the RQES hand-off — now the opaque `PlatformContext` —
// and the `Form`/`Rule` construction that asked the validator whether a scan was a URL. The validator is
// 433 lines of `android.net.Uri` and libphonenumber, so what crosses the seam is its answer:
// `QrScanInteractor.isScannedQrValid`. Everything else here was already shared. Package unchanged.
package eu.europa.ec.commonfeature.ui.qr_scan

import androidx.lifecycle.viewModelScope
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.commonfeature.config.QrScanUiConfig
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.interactor.QrScanInteractor
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.issuance_qr_scan_subtitle
import eu.europa.ec.shared.resources.issuance_qr_scan_title
import eu.europa.ec.shared.resources.presentation_qr_scan_subtitle
import eu.europa.ec.shared.resources.presentation_qr_scan_title
import eu.europa.ec.shared.resources.qr_scan_informative_text_issuance_flow
import eu.europa.ec.shared.resources.qr_scan_informative_text_presentation_flow
import eu.europa.ec.shared.resources.qr_scan_informative_text_signature_flow
import eu.europa.ec.shared.resources.signature_qr_scan_subtitle
import eu.europa.ec.shared.resources.signature_qr_scan_title
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

    val title: UiText,
    val subtitle: UiText,
    val failedScanAttempts: Int = 0,
    val showInformativeText: Boolean = false,
    val informativeText: UiText,
) : ViewState

sealed class Event : ViewEvent {
    data object GoBack : Event()
    /**
     * @param context the host context, or null where there is none — iOS. Only the signature flow
     * reads it, so a scan is dispatched either way: presentation and issuance QR codes need no handle,
     * and dropping the event here made every scan on iOS do nothing at all.
     */
    data class OnQrScanned(val context: PlatformContext?, val resultQr: String) : Event()
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
    @InjectedParam private val qrScannedConfig: QrScanUiConfig,
) : MviViewModel<Event, State, Effect>() {

    override fun setInitialState(): State {
        return State(
            qrScannedConfig = qrScannedConfig,
            title = calculateTitle(qrScannedConfig.qrScanFlow),
            subtitle = calculateSubtitle(qrScannedConfig.qrScanFlow),
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

    private fun handleScannedQr(context: PlatformContext?, scannedQr: String) {
        viewModelScope.launch {
            val currentState = viewState.value

            // Validate the scanned QR code
            val urlIsValid = interactor.isScannedQrValid(scannedQr)

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

    private fun calculateNextStep(
        context: PlatformContext?,
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

    /**
     * The screen's copy follows from the flow alone, so it is derived here rather than passed in.
     * [QrScanUiConfig] used to carry `title`/`subTitle` as resolved strings, which made all three
     * callers resolve the very pair this `when` reproduces.
     */
    private fun calculateTitle(
        qrScanFlow: QrScanFlow,
    ): UiText {
        return UiText.Resource(
            when (qrScanFlow) {
                is QrScanFlow.Presentation -> Res.string.presentation_qr_scan_title
                is QrScanFlow.Issuance -> Res.string.issuance_qr_scan_title
                is QrScanFlow.Signature -> Res.string.signature_qr_scan_title
            }
        )
    }

    private fun calculateSubtitle(
        qrScanFlow: QrScanFlow,
    ): UiText {
        return UiText.Resource(
            when (qrScanFlow) {
                is QrScanFlow.Presentation -> Res.string.presentation_qr_scan_subtitle
                is QrScanFlow.Issuance -> Res.string.issuance_qr_scan_subtitle
                is QrScanFlow.Signature -> Res.string.signature_qr_scan_subtitle
            }
        )
    }

    private fun calculateInformativeText(
        qrScanFlow: QrScanFlow,
    ): UiText {
        return UiText.Resource(
            when (qrScanFlow) {
                is QrScanFlow.Presentation -> Res.string.qr_scan_informative_text_presentation_flow
                is QrScanFlow.Issuance -> Res.string.qr_scan_informative_text_issuance_flow
                is QrScanFlow.Signature -> Res.string.qr_scan_informative_text_signature_flow
            }
        )
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

    private fun navigateToRqesSdk(context: PlatformContext?, scanResult: String) {
        interactor.launchRqesSdk(context = context, uri = scanResult)
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