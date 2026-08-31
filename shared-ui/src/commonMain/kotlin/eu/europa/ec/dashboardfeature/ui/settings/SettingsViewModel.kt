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

// Phase 3b: the last view-model freed by the platform-handle layer, and the only one that needed a real
// refactor rather than a retype. It used to BUILD the log-sharing intent itself, which shared code cannot
// do — a `PlatformIntent` is opaque — so that construction moved into `SettingsInteractorImpl`, which
// already owned the log files, behind `getLogFilePaths()` (originally `getLogShareIntent()`, which
// was still Android-shaped and left iOS unable to answer). The host `Context` became a
// `PlatformContext` and the changelog URL a `String`. Package unchanged, so `SettingsScreen` only had to
// parse that URL at the point of use.
package eu.europa.ec.dashboardfeature.ui.settings

import androidx.lifecycle.viewModelScope
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.dashboardfeature.interactor.SettingsInteractor
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsItemUi
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsMenuItemType
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.settings_screen_title
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

data class State(
    val isLoading: Boolean = false,
    val screenTitle: UiText = UiText.Resource(Res.string.settings_screen_title),

    val settingsItems: List<SettingsItemUi> = emptyList(),

    val appVersion: String = "",
    val changelogUrl: String?,

    /**
     * Only ever the "no biometrics enrolled" explainer, so there is no sheet-content type to choose
     * between. If a second sheet ever lands here, this becomes a sealed content class as on the home
     * screen.
     */
    val isBottomSheetOpen: Boolean = false,
) : ViewState

sealed class Event : ViewEvent {
    data object Init : Event()
    data object Pop : Event()
    data object LaunchBiometricSystemScreen : Event()
    /**
     * @param context the host context, or null where there is none — iOS, whose [PlatformContext] is
     * uninhabited. Only the biometrics row needs it, so the other rows stay clickable there.
     */
    data class ItemClicked(
        val itemType: SettingsMenuItemType,
        val context: PlatformContext?
    ) : Event()

    data class UpdateBottomSheetState(val isOpen: Boolean) : Event()

    /** The explainer's positive button: go where the platform can actually take us. */
    data object BiometricEnrolmentSettingsPressed : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()
        data object LaunchBiometricsSystemScreen : Navigation()

        /**
         * The URL stays a `String` rather than an `android.net.Uri`; the screen parses it at the
         * point of use, as with the deep-link effects elsewhere.
         */
        data class OpenUrlExternally(val url: String) : Navigation()
    }

    data class ShareLogFiles(val paths: List<String>) : Effect()
    data class ShowSnackbar(val message: String) : Effect()
}

@KoinViewModel
class SettingsViewModel(
    private val settingsInteractor: SettingsInteractor,
) : MviViewModel<Event, State, Effect>() {

    init {
        // Tied to the ViewModel's lifetime, not the composition's — see the note on
        // `OneTimeLaunchedEffect`'s saveable guard in HomeViewModel. Without this the settings list
        // came back empty after process death.
        createSettingsItemsUi(viewState.value.changelogUrl)
    }

    override fun setInitialState(): State {
        return State(
            appVersion = settingsInteractor.getAppVersion(),
            changelogUrl = settingsInteractor.getChangelogUrl(),
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Init -> {
                createSettingsItemsUi(viewState.value.changelogUrl)
            }

            is Event.Pop -> setEffect { Effect.Navigation.Pop }

            is Event.LaunchBiometricSystemScreen -> {
                settingsInteractor.launchBiometricSystemScreen()
            }

            is Event.UpdateBottomSheetState -> {
                setState { copy(isBottomSheetOpen = event.isOpen) }
            }

            is Event.BiometricEnrolmentSettingsPressed -> {
                setState { copy(isBottomSheetOpen = false) }
                setEffect { Effect.Navigation.LaunchBiometricsSystemScreen }
            }

            is Event.ItemClicked -> handleSettingsMenuItemClicked(
                itemType = event.itemType,
                context = event.context
            )
        }
    }

    private fun createSettingsItemsUi(changelogUrl: String?) {
        viewModelScope.launch {
            setState {
                copy(
                    isLoading = true,
                )
            }

            val settingsItems = settingsInteractor.getSettingsItemsUi(changelogUrl = changelogUrl)
            setState {
                copy(
                    isLoading = false,
                    settingsItems = settingsItems,
                )
            }
        }
    }

    private fun handleSettingsMenuItemClicked(
        itemType: SettingsMenuItemType,
        context: PlatformContext?,
    ) {
        when (itemType) {
            // The row appears wherever biometrics exist, iOS included: `deviceSupportsBiometrics`
            // asks the platform, and iOS answers `CanAuthenticate` once Face ID is enrolled. So the
            // handle is passed on exactly as it arrives rather than used as a gate — it is Android's
            // alone, and iOS raises the same prompt without one. Gating on it here is what used to
            // leave that visible switch dead on iOS.
            SettingsMenuItemType.BIOMETRICS_AUTHENTICATION -> {
                when (val availability = settingsInteractor.getBiometricsAvailability()) {
                    is BiometricsAvailability.CanAuthenticate -> viewModelScope.launch {
                        confirmBiometricsChange(context)
                    }

                    // Android can open the enrolment screen itself, and arriving there needs no
                    // explaining, so it goes straight. Where the platform cannot — iOS, which may not
                    // link past its own Settings pane — being thrown out of the app with no
                    // explanation is worse than being asked first, so the sheet says what is wrong and
                    // leaves the choice with the user.
                    is BiometricsAvailability.NonEnrolled -> {
                        if (settingsInteractor.canOpenBiometricEnrolment) {
                            setEffect {
                                Effect.Navigation.LaunchBiometricsSystemScreen
                            }
                        } else {
                            setState { copy(isBottomSheetOpen = true) }
                        }
                    }

                    is BiometricsAvailability.Failure -> {
                        setEffect {
                            Effect.ShowSnackbar(availability.errorMessage)
                        }
                    }
                }
            }

            SettingsMenuItemType.SHOW_BATCH_ISSUANCE_COUNTER -> {
                viewModelScope.launch {
                    settingsInteractor.toggleShowBatchIssuanceCounter()

                    val settingsItems = settingsInteractor.getSettingsItemsUi(
                        changelogUrl = viewState.value.changelogUrl
                    )

                    setState {
                        copy(
                            settingsItems = settingsItems,
                        )
                    }
                }
            }

            SettingsMenuItemType.REGISTRATION_CHECK -> {
                viewModelScope.launch {
                    settingsInteractor.toggleRegistrationCheck()

                    val settingsItems = settingsInteractor.getSettingsItemsUi(
                        changelogUrl = viewState.value.changelogUrl
                    )

                    setState {
                        copy(
                            settingsItems = settingsItems,
                        )
                    }

                    // Wallet Core reads both registration policies when it builds its managers, so
                    // the flip does not reach it until the next app start.
                    setEffect {
                        Effect.ShowSnackbar(
                            message = settingsInteractor.registrationCheckRestartMessage
                        )
                    }
                }
            }

            SettingsMenuItemType.RETRIEVE_LOGS -> {
                // Paths, not an intent: the platform turns them into its own share UI. Empty means
                // this platform collects no logs, which is how the row stays honest.
                val logPaths = settingsInteractor.getLogFilePaths()
                if (logPaths.isNotEmpty()) {
                    setEffect {
                        Effect.ShareLogFiles(paths = logPaths)
                    }
                }
            }

            SettingsMenuItemType.CHANGELOG -> {
                val changelogUrl = viewState.value.changelogUrl
                if (changelogUrl != null) {
                    setEffect {
                        Effect.Navigation.OpenUrlExternally(
                            url = changelogUrl
                        )
                    }
                }
            }
        }
    }

    /**
     * Flips biometric login, with the platform's prompt as the confirmation.
     *
     * **The order depends on the direction, and has to.** On iOS the biometry-gated Keychain item *is*
     * the setting, so a prompt is only possible while that item exists: switching on has to write
     * first and undo the write if the prompt goes unanswered, while switching off has to prompt before
     * deleting. Android is indifferent — its prompt is backed by a Keystore key that outlives the
     * preference — so one order serves both platforms and neither needs to know which it is.
     *
     * Prompting first in both directions is what used to be here, and it made switching on impossible
     * on iOS: the prompt read an item that only switching on would have created, got
     * `errSecItemNotFound`, and raised no prompt at all. The switch sat there and did nothing.
     *
     * Either direction only survives a prompt the user answered, which is the property worth keeping:
     * nobody turns the wallet's biometric login on or off without authenticating.
     */
    private suspend fun confirmBiometricsChange(context: PlatformContext?) {
        val turningOn = !settingsInteractor.isBiometricsEnabled()
        if (turningOn) {
            settingsInteractor.setBiometricsAuthentication(enabled = true)
        }
        settingsInteractor.authenticateWithBiometrics(
            context = context,
            notifyOnAuthenticationFailure = true,
        ) { result ->
            viewModelScope.launch {
                when (result) {
                    // Switching off is the half that still has work to do here; switching on already
                    // wrote, and the prompt it just passed is what makes that write final.
                    is BiometricsAuthenticate.Success ->
                        if (!turningOn) {
                            settingsInteractor.setBiometricsAuthentication(enabled = false)
                        }

                    // Cancelled or failed: leave the setting as the user found it. Only the "on"
                    // direction has anything to undo, because only it wrote before prompting.
                    else ->
                        if (turningOn) {
                            settingsInteractor.setBiometricsAuthentication(enabled = false)
                        }
                }
                refreshSettingsItems()
            }
        }
    }

    private suspend fun refreshSettingsItems() {
        val settingsItems = settingsInteractor.getSettingsItemsUi(
            changelogUrl = viewState.value.changelogUrl
        )
        setState {
            copy(
                settingsItems = settingsItems,
            )
        }
    }
}