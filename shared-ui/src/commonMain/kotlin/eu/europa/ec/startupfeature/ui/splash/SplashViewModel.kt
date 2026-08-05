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
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

data class State(
    val logoAnimationDuration: Int = 1500
) : ViewState

sealed class Effect : ViewSideEffect {

    sealed class Navigation : Effect() {
        data class SwitchScreen(val route: AppRoute) : Navigation()
    }
}

@KoinViewModel
class SplashViewModel(
    private val interactor: SplashInteractor,
) : MviViewModel<Nothing, State, Effect>() {

    init {
        // Must be tied to the ViewModel's lifetime, not the composition's. This is the only thing
        // that navigates off the splash, and it used to be triggered by an `Event.Initialize` from
        // `OneTimeLaunchedEffect`, whose "already ran" flag is `rememberSaveable` — so if the
        // process died while the splash was showing, the restored flag suppressed the event and the
        // app hung on the splash forever.
        enterApplication()
    }

    override fun setInitialState(): State = State()

    /**
     * The splash screen has no interactions — it shows a logo and leaves. Since the move to `init`
     * above, an event could only re-run [enterApplication] and navigate twice, which is exactly the
     * bug the stale test caught, so the event type is [Nothing]: `setEvent` now takes an argument no
     * caller can construct, making "there are no events" a compile-time guarantee instead of a
     * convention. If the splash ever does need one (skipping the animation, say), declare a
     * `sealed class Event : ViewEvent` and widen this type argument back.
     */
    override fun handleEvents(event: Nothing) = Unit

    private fun enterApplication() {
        viewModelScope.launch {
            delay((viewState.value.logoAnimationDuration + 500).toLong())
            val route = interactor.getAfterSplashRoute()
            setEffect {
                Effect.Navigation.SwitchScreen(route)
            }
        }
    }
}
