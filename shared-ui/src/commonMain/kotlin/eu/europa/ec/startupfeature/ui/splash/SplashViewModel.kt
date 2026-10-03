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

// Phase 3b: the first feature view-model to move to commonMain — it is shared verbatim by Android and
// iOS. Everything it touches is already KMP: the MVI base (:shared-logic), `AppRoute` (this module),
// androidx.lifecycle's KMP ViewModel, and coroutines. The package is unchanged, so `SplashScreen` and
// the module's `entryProvider` in :startup-feature are untouched.
//
// `@KoinViewModel` still works here: Koin 1.1.0's compiler plugin runs on every compilation (iOS
// included) and generates the binding against the KMP `koin-core-viewmodel` DSL. The definition is
// picked up by `SharedUiModule` (this module's `@ComponentScan`) instead of `FeatureStartupModule`,
// while the Android-only `SplashInteractor` implementation stays bound in :startup-feature.
package eu.europa.ec.startupfeature.ui.splash

import androidx.lifecycle.viewModelScope
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.startupfeature.interactor.SplashInteractor
import eu.europa.ec.startupfeature.interactor.SplashRoutePartialState
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

data class State(
    val logoAnimationDuration: Int = 1500,
    val isLoading: Boolean = false,
    val error: String? = null,
) : ViewState

/**
 * Only the error screen's two buttons (upstream bf514519). There is still no `Initialize`: entering the
 * application stays in `init` (see there), and an event that could start it a second time is exactly
 * what the old double-navigation bug was.
 */
sealed class Event : ViewEvent {
    data object Retry : Event()
    data object Cancel : Event()
}

sealed class Effect : ViewSideEffect {

    sealed class Navigation : Effect() {
        data class SwitchScreen(val route: AppRoute) : Navigation()
        data object Finish : Navigation()
    }
}

@KoinViewModel
class SplashViewModel(
    private val interactor: SplashInteractor,
) : MviViewModel<Event, State, Effect>() {

    init {
        // Must be tied to the ViewModel's lifetime, not the composition's. This is the only thing
        // that navigates off the splash, and it used to be triggered by an `Event.Initialize` from
        // `OneTimeLaunchedEffect`, whose "already ran" flag is `rememberSaveable` — so if the
        // process died while the splash was showing, the restored flag suppressed the event and the
        // app hung on the splash forever.
        enterApplication(animateLogo = true)
    }

    override fun setInitialState(): State = State()

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Retry -> enterApplication(animateLogo = false)
            is Event.Cancel -> setEffect { Effect.Navigation.Finish }
        }
    }

    private fun enterApplication(animateLogo: Boolean) {
        if (viewState.value.isLoading) return

        setState {
            copy(
                isLoading = true,
                error = null
            )
        }
        viewModelScope.launch {
            if (animateLogo) {
                delay((viewState.value.logoAnimationDuration + 500).toLong())
            }

            when (val result = interactor.getAfterSplashRoute()) {
                is SplashRoutePartialState.Success -> {
                    setEffect { Effect.Navigation.SwitchScreen(result.route) }
                }

                is SplashRoutePartialState.Failure -> {
                    setState {
                        copy(
                            isLoading = false,
                            error = result.error
                        )
                    }
                }
            }
        }
    }
}
