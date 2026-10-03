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

package eu.europa.ec.startupfeature.ui.splash

import eu.europa.ec.commonfeature.model.PinFlow
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.QuickPinRoute
import eu.europa.ec.startupfeature.interactor.SplashInteractor
import eu.europa.ec.startupfeature.interactor.SplashRoutePartialState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 3b: the first *view-model* test that runs on both platforms — the point of moving the VM to
 * commonMain. It covers the whole contract: the logo animation delay gates the decision, and whatever
 * route the interactor resolves (including a config-carrying one) is handed on verbatim as a
 * navigation effect.
 *
 * The trigger is **construction**, not an event: `enterApplication()` moved into the VM's `init` block
 * so that a process death on the splash screen cannot leave the app stranded there (the `Event.Init`
 * that used to drive it came from an `OneTimeLaunchedEffect` whose "already ran" flag is
 * `rememberSaveable`). An earlier version of these tests still sent the by-then-emitterless
 * `Event.Initialize`, which ran the flow a second time and made `invocations` come back as 2. There is
 * still no such event — the only ones are the error screen's Retry and Cancel (upstream bf514519), and a
 * Retry while the first resolution is under way is ignored — so `assertEquals(1, …)` below is a real
 * check that construction starts the flow exactly once.
 *
 * `Dispatchers.setMain` is required because `viewModelScope` dispatches on Main; the test dispatcher
 * is shared with `runTest` so the VM's `delay` runs on the same virtual clock (the same wiring the
 * `FlowExtensionsTest` fix needed).
 */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher, setMain, advanceTimeBy/UntilIdle
class SplashViewModelTest {

    /** Answers [results] in order, then keeps answering the last one. */
    private class FakeSplashInteractor(private vararg val results: SplashRoutePartialState) : SplashInteractor {
        constructor(route: AppRoute) : this(SplashRoutePartialState.Success(route))

        var invocations = 0
            private set

        override suspend fun getAfterSplashRoute(): SplashRoutePartialState {
            invocations++
            return results[minOf(invocations, results.size) - 1]
        }
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initial_state_carries_the_logo_animation_duration() {
        val viewModel = SplashViewModel(FakeSplashInteractor(DashboardRoute))
        // Loading already: construction is what starts the resolution.
        assertEquals(State(isLoading = true), viewModel.viewState.value)
    }

    @Test
    fun creating_the_view_model_switches_to_the_route_the_interactor_resolved() =
        runTest(mainDispatcher) {
            val interactor = FakeSplashInteractor(DashboardRoute)
            val viewModel = SplashViewModel(interactor)
            val effect = async { viewModel.effect.first() }

            advanceUntilIdle()

            assertEquals(Effect.Navigation.SwitchScreen(DashboardRoute), effect.await())
            assertEquals(1, interactor.invocations)
        }

    @Test
    fun the_route_is_not_resolved_until_the_logo_animation_has_played_out() =
        runTest(mainDispatcher) {
            val interactor = FakeSplashInteractor(DashboardRoute)
            val viewModel = SplashViewModel(interactor)
            val animationDuration = viewModel.viewState.value.logoAnimationDuration.toLong()

            advanceTimeBy(animationDuration)

            assertEquals(0, interactor.invocations)
        }

    @Test
    fun a_config_carrying_route_is_handed_on_verbatim() =
        runTest(mainDispatcher) {
            val route = QuickPinRoute(PinFlow.CREATE_WITH_ACTIVATION)
            val viewModel = SplashViewModel(FakeSplashInteractor(route))
            val effect = async { viewModel.effect.first() }

            advanceUntilIdle()

            assertEquals(Effect.Navigation.SwitchScreen(route), effect.await())
        }

    @Test
    fun a_failure_shows_its_error_and_retry_resolves_again_without_the_logo_delay() =
        runTest(mainDispatcher) {
            val interactor = FakeSplashInteractor(
                SplashRoutePartialState.Failure(error = "Something went wrong"),
                SplashRoutePartialState.Success(DashboardRoute),
            )
            val viewModel = SplashViewModel(interactor)
            advanceUntilIdle()

            assertEquals("Something went wrong", viewModel.viewState.value.error)
            assertEquals(false, viewModel.viewState.value.isLoading)

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.Retry)
            runCurrent()

            assertEquals(2, interactor.invocations)
            assertEquals(Effect.Navigation.SwitchScreen(DashboardRoute), effect.await())
            assertNull(viewModel.viewState.value.error)
        }

    @Test
    fun a_retry_while_the_route_is_still_resolving_is_ignored() =
        runTest(mainDispatcher) {
            val interactor = FakeSplashInteractor(DashboardRoute)
            val viewModel = SplashViewModel(interactor)

            viewModel.setEvent(Event.Retry)
            advanceUntilIdle()

            assertEquals(1, interactor.invocations)
        }

    @Test
    fun cancel_asks_to_leave_the_app() =
        runTest(mainDispatcher) {
            val viewModel = SplashViewModel(
                FakeSplashInteractor(SplashRoutePartialState.Failure(error = "Something went wrong"))
            )
            advanceUntilIdle()
            val effect = async { viewModel.effect.first() }

            viewModel.setEvent(Event.Cancel)

            assertEquals(Effect.Navigation.Finish, effect.await())
        }
}
