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

// Phase 3b: the sixth feature view-model in commonMain, and the first to use the platform-handle
// layer. It was otherwise KMP-clean; the only Android type it named was `ComponentActivity`, which it
// never touched — `Event.NfcEngagement` carries it from the composition straight to
// `interactor.toggleNfcEngagement`. That parameter is now a `PlatformActivity`, an opaque expect class
// that is an `actual typealias` for ComponentActivity on Android, so the screen and the interactor
// implementation are untouched.
//
// Also lifted with it: the `ProximityQRInteractor` contract, the `ScopedPresentationInteractor` it
// extends, and `getOrNullKoinScope` (used by `cleanUp` to close the presentation scope). Package
// unchanged.
package eu.europa.ec.proximityfeature.ui.qr

import androidx.lifecycle.viewModelScope
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.corelogic.di.getOrNullKoinScope
import eu.europa.ec.proximityfeature.interactor.ProximityQRInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityQRPartialState
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.ProximityRequestRoute
import eu.europa.ec.shared.platform.PlatformActivity
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

data class State(
    val isLoading: Boolean = true,
    val error: ContentErrorConfig? = null,

    val qrCode: String = "",
    val presentationScopeId: String = "",

    /** Whether the platform offers NFC data retrieval at all — only iOS does, for now; see
     * [ProximityQRInteractor.isNfcDataRetrievalAvailable]. The screen offers the switch only when
     * this is `true`. */
    val nfcDataRetrievalAvailable: Boolean = false,
    /** The switch's own value. Starts `false`, matching `IosProximityPresenter`'s own default — see
     * `wiki/IOS_NFC_PLAN.md`'s phase 3 notes on why that default is deliberate. */
    val nfcDataRetrievalEnabled: Boolean = false,
) : ViewState

sealed class Event : ViewEvent {
    data object Init : Event()
    data object GoBack : Event()
    data class NfcEngagement(
        val componentActivity: PlatformActivity,
        val enable: Boolean
    ) : Event()

    /**
     * The user flipping the NFC data-retrieval switch — a different feature from [NfcEngagement]
     * above; see [ProximityQRInteractor.toggleNfcDataRetrieval].
     */
    data class NfcDataRetrievalToggled(val enabled: Boolean) : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data class SwitchScreen(
            val route: AppRoute
        ) : Navigation()

        data object Pop : Navigation()
    }

    /** See [ProximityQRPartialState.NfcNotice]: NFC specifically failed to start, BLE did not. */
    data class ShowSnackbar(val message: String) : Effect()
}

@KoinViewModel
class ProximityQRViewModel(
    private val interactor: ProximityQRInteractor,
    @InjectedParam private val requestUriConfig: RequestUriConfig
) : MviViewModel<Event, State, Effect>() {

    private var interactorJob: Job? = null

    init {
        // Tied to the ViewModel's lifetime, not the composition's — see the note on
        // `OneTimeLaunchedEffect`'s saveable guard in HomeViewModel. Without this no QR code was
        // generated after process death.
        initializeConfig()
        generateQrCode()
    }

    override fun setInitialState(): State = State()

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Init -> {
                initializeConfig()
                generateQrCode()
            }

            is Event.GoBack -> {
                cleanUp()
                setState { copy(error = null) }
                setEffect { Effect.Navigation.Pop }
            }

            is Event.NfcEngagement -> {
                interactor.toggleNfcEngagement(
                    event.componentActivity,
                    event.enable
                )
            }

            is Event.NfcDataRetrievalToggled -> {
                interactor.toggleNfcDataRetrieval(event.enabled)
                setState { copy(nfcDataRetrievalEnabled = event.enabled) }
                restartEngagementForNfcToggle()
            }
        }
    }

    private fun initializeConfig() {
        setState {
            copy(
                presentationScopeId = requestUriConfig.presentationScopeId,
                nfcDataRetrievalAvailable = interactor.isNfcDataRetrievalAvailable(),
                // Finding E of the current-state audit: read the actual current value back, rather
                // than leaving the field at its own default — the underlying flag lives on a
                // platform singleton (iOS's `IosProximityPresenter`) that outlives this view-model,
                // so a fresh screen visit must not silently show the switch off if it was left on.
                nfcDataRetrievalEnabled = interactor.isNfcDataRetrievalEnabled(),
            )
        }

        interactor.setConfig(requestUriConfig)
    }

    /**
     * Findings A/B/D/E of the current-state audit, part 4: without this, flipping the switch while
     * this screen's own engagement was already advertising silently did nothing until the *next*
     * time this screen opened — [ProximityQRInteractor.toggleNfcDataRetrieval] only takes effect on
     * the next [ProximityQRInteractor.startQrEngagement] call. Restarting here makes the change
     * visible immediately, on the same QR code the user is looking at (a new QR, since the
     * connection methods it encodes changed).
     *
     * [unsubscribe] first: [generateQrCode] does not cancel a still-running [interactorJob] itself,
     * and leaving the old one running would mean two collectors racing on the same underlying
     * platform state — the old one would read the restart's momentary disconnect as
     * [ProximityQRPartialState.Disconnected] and navigate back.
     */
    private fun restartEngagementForNfcToggle() {
        unsubscribe()
        generateQrCode()
    }

    private fun generateQrCode() {
        setState {
            copy(
                isLoading = true,
                error = null
            )
        }

        interactorJob = viewModelScope.launch {
            interactor.startQrEngagement().collect { response ->
                when (response) {
                    is ProximityQRPartialState.Error -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = ContentErrorConfig(
                                    onRetry = { setEvent(Event.Init) },
                                    errorSubTitle = response.error.asUiText(),
                                    onCancel = { setEvent(Event.GoBack) }
                                )
                            )
                        }
                    }

                    is ProximityQRPartialState.QrReady -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = null,
                                qrCode = response.qrCode
                            )
                        }
                    }

                    is ProximityQRPartialState.Connected -> {
                        unsubscribe()
                        setEffect {
                            Effect.Navigation.SwitchScreen(
                                route = ProximityRequestRoute(
                                    viewState.value.presentationScopeId
                                )
                            )
                        }
                    }

                    is ProximityQRPartialState.Disconnected -> {
                        unsubscribe()
                        setEvent(Event.GoBack)
                    }

                    is ProximityQRPartialState.NfcNotice -> {
                        setEffect { Effect.ShowSnackbar(message = response.message) }
                    }
                }
            }
        }
    }

    /**
     * Required in order to stop receiving emissions from interactor Flow
     * */
    private fun unsubscribe() {
        interactorJob?.cancel()
    }

    /**
     * Stop presentation and remove scope/listeners
     * */
    private fun cleanUp() {
        unsubscribe()
        interactor.cancelTransfer()
        getOrNullKoinScope(viewState.value.presentationScopeId)?.close()
    }
}