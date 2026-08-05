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

// Phase 3b: the re-usable loading screen's view-model base. The only Android type it named was the
// `Context` it forwards to device authentication, now the opaque `PlatformContext` handle — on Android
// that is a typealias for Context, so `LoadingScreen` and the subclass overrides are unchanged. The
// presentation subclass stays in :presentation-feature for now: its interactor's partial states carry
// a `java.net.URI` redirect and an `android.content.Intent`. Package unchanged.
package eu.europa.ec.commonfeature.ui.loading

import androidx.lifecycle.viewModelScope
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

data class State(
    val error: ContentErrorConfig? = null,
    val headerConfig: ContentHeaderConfig,
    val isCancellable: Boolean,
    val notifyOnAuthenticationFailure: Boolean = false
) : ViewState

sealed class Event : ViewEvent {
    data class DoWork(val context: PlatformContext) : Event()
    data object Initialize : Event()
    data object GoBack : Event()
    data object DismissError : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data class SwitchScreen(val route: AppRoute) : Navigation()
        data class PopBackStackUpTo(
            val route: AppRoute,
            val inclusive: Boolean
        ) : Navigation()
    }
}

abstract class LoadingViewModel : MviViewModel<Event, State, Effect>() {

    /**
     * The [ContentHeaderConfig] of the re-usable [LoadingScreen] .
     */
    abstract fun getHeaderConfig(): ContentHeaderConfig

    /**
     * The [AppRoute] the user will be navigated to:
     * 1. If they press the "X" button--cancel the [LoadingScreen] .
     * 2. If they press the "X" button of the Error screen (should any Error happen).
     */
    abstract fun getPreviousRoute(): AppRoute

    /**
     * The [AppRoute] which opened the re-usable [LoadingScreen] .
     * It will be erased from the back-stack when user successfully moves to the next step route.
     */
    abstract fun getCallerRoute(): AppRoute

    /**
     * Used to perform any kind of work the calling viewModel needs to.
     * Gets called once upon initialization of the [LoadingScreen] +
     * each time the user presses "Try again" in its Error screen.
     */
    abstract fun doWork(context: PlatformContext)

    /**
     * Start the Screen without back navigation until timer is run out.
     * Based on the duration provided.
     */
    abstract fun getCancellableTimeout(): Duration

    override fun setInitialState(): State {
        return State(
            headerConfig = getHeaderConfig(),
            error = null,
            isCancellable = !getCancellableTimeout().isPositive()
        )
    }

    /**
     * Guards the one-shot initial work. Deliberately NOT saveable: it must survive configuration
     * change (where re-running would send the presentation request twice) but reset with the
     * process, so a restored screen re-runs against its brand-new ViewModel.
     *
     * This replaces `OneTimeLaunchedEffect`, whose flag was `rememberSaveable` and so came back
     * `true` after process death, suppressing both events and leaving the screen spinning forever.
     * The guard lives here rather than on [Event.DoWork] itself because subclasses re-send that
     * event as their error-retry path, which must stay ungated.
     */
    private var hasRunInitialWork = false

    /**
     * Called from a plain `LaunchedEffect(Unit)`, which re-runs whenever the composition is rebuilt;
     * this ViewModel-scoped guard is what makes the work once-per-instance.
     */
    fun startInitialWork(context: PlatformContext) {
        if (hasRunInitialWork) return
        hasRunInitialWork = true
        setEvent(Event.Initialize)
        setEvent(Event.DoWork(context))
    }

    override fun handleEvents(event: Event) {
        when (event) {

            is Event.Initialize -> initiateCancellableTimeoutIfAvailable()

            is Event.DoWork -> doWork(event.context)

            is Event.GoBack -> {
                setState {
                    copy(error = null)
                }
                doNavigation(NavigationType.Pop)
            }

            is Event.DismissError -> {
                setState {
                    copy(error = null)
                }
            }
        }
    }

    protected fun doNavigation(navigationType: NavigationType) {
        when (navigationType) {
            is NavigationType.Pop, NavigationType.Finish -> {
                setEffect {
                    Effect.Navigation.PopBackStackUpTo(
                        route = getPreviousRoute(),
                        inclusive = false
                    )
                }
            }

            is NavigationType.PopTo -> {
                setEffect {
                    Effect.Navigation.PopBackStackUpTo(
                        route = navigationType.route,
                        inclusive = false
                    )
                }
            }

            is NavigationType.Deeplink -> {}

            is NavigationType.PushRoute -> setEffect {
                Effect.Navigation.SwitchScreen(navigationType.route)
            }
        }
    }

    private fun initiateCancellableTimeoutIfAvailable() {
        with(getCancellableTimeout()) {
            if (isPositive()) {
                viewModelScope.launch {
                    delay(this@with.inWholeMilliseconds)
                    setState { copy(isCancellable = true) }
                }
            }
        }
    }
}