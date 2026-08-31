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
                    is BiometricsAvailability.CanAuthenticate -> authenticate(context)

                    is BiometricsAvailability.NonEnrolled -> {
                        setEffect {
                            Effect.Navigation.LaunchBiometricsSystemScreen
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

    private fun authenticate(context: PlatformContext?) {
        settingsInteractor.authenticateWithBiometrics(
            context = context,
            notifyOnAuthenticationFailure = true,
        ) { result ->
            when (result) {
                is BiometricsAuthenticate.Success -> {
                    viewModelScope.launch {
                        settingsInteractor.toggleBiometricsAuthentication()
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

                else -> {}
            }
        }
    }
}