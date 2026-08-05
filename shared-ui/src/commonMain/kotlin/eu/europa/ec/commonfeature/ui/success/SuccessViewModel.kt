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

// Phase 3b: moved to commonMain — shared verbatim by Android and iOS. Everything it touches was
// already KMP (SuccessUIConfig, ConfigNavigation/NavigationType, AppRoute/AppRouteCodec, the MVI
// base); the single Android leaf was `android.net.Uri` in the deep-link effect, now a `String` that
// the consuming screen parses. The package is unchanged, so `SuccessScreen` and :common-feature's
// `entryProvider` are untouched, and `@KoinViewModel` is picked up by `SharedUiModule`'s scan
// instead of `FeatureCommonModule`'s.
package eu.europa.ec.commonfeature.ui.success

import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.AppRouteCodec
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

data class State(
    val successConfig: SuccessUIConfig
) : ViewState

sealed class Event : ViewEvent {
    data class ButtonClicked(val config: SuccessUIConfig.ButtonConfig) : Event()
    data object BackPressed : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data class SwitchScreen(
            val route: AppRoute,
            val popUpTo: AppRoute?
        ) : Navigation()

        data class PopBackStackUpTo(
            val route: AppRoute,
            val inclusive: Boolean
        ) : Navigation()

        data object Pop : Navigation()

        /**
         * The link stays a `String` rather than an `android.net.Uri`: this view-model lives in
         * commonMain, and parsing to a platform URI is the consuming screen's job.
         */
        data class DeepLink(
            val link: String,
            val routeToPop: AppRoute?
        ) : Navigation()
    }
}

@KoinViewModel
class SuccessViewModel(
    @InjectedParam private val successConfig: SuccessUIConfig
) : MviViewModel<Event, State, Effect>() {
    override fun setInitialState(): State = State(successConfig = successConfig)

    override fun handleEvents(event: Event) {
        when (event) {

            is Event.ButtonClicked -> {
                doNavigation(event.config.navigation)
            }

            is Event.BackPressed -> {
                doNavigation(
                    viewState.value.successConfig.onBackScreenToNavigate
                )
            }
        }
    }

    private fun doNavigation(navigation: ConfigNavigation) {

        val navigationEffect: Effect.Navigation = when (val nav = navigation.navigationType) {
            is NavigationType.PopTo -> {
                Effect.Navigation.PopBackStackUpTo(
                    route = nav.route,
                    inclusive = false
                )
            }

            is NavigationType.Deeplink -> Effect.Navigation.DeepLink(
                nav.link,
                AppRouteCodec.decode(nav.routeToPop)
            )

            is NavigationType.Pop, NavigationType.Finish -> Effect.Navigation.Pop

            is NavigationType.PushRoute -> Effect.Navigation.SwitchScreen(
                route = nav.route,
                popUpTo = nav.popUpTo
            )
        }

        setEffect {
            navigationEffect
        }
    }
}