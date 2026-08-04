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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 3b: the first *view-model* test that runs on both platforms — the point of moving the VM to
 * commonMain. It covers the whole contract: the logo animation delay gates the decision, and whatever
 * route the interactor resolves (including a config-carrying one) is handed on verbatim as a
 * navigation effect.
 *
 * `Dispatchers.setMain` is required because `viewModelScope` dispatches on Main; the test dispatcher
 * is shared with `runTest` so the VM's `delay` runs on the same virtual clock (the same wiring the
 * `FlowExtensionsTest` fix needed).
 */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher, setMain, advanceTimeBy/UntilIdle
class SplashViewModelTest {

    private class FakeSplashInteractor(private val route: AppRoute) : SplashInteractor {
        var invocations = 0
            private set

        override suspend fun getAfterSplashRoute(): AppRoute {
            invocations++
            return route
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
        assertEquals(State(), viewModel.viewState.value)
    }

    @Test
    fun initialize_switches_to_the_route_the_interactor_resolved() =
        runTest(mainDispatcher) {
            val interactor = FakeSplashInteractor(DashboardRoute)
            val viewModel = SplashViewModel(interactor)
            val effect = async { viewModel.effect.first() }

            viewModel.setEvent(Event.Initialize)
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

            viewModel.setEvent(Event.Initialize)
            advanceTimeBy(animationDuration)

            assertEquals(0, interactor.invocations)
        }

    @Test
    fun a_config_carrying_route_is_handed_on_verbatim() =
        runTest(mainDispatcher) {
            val route = QuickPinRoute(PinFlow.CREATE_WITH_ACTIVATION)
            val viewModel = SplashViewModel(FakeSplashInteractor(route))
            val effect = async { viewModel.effect.first() }

            viewModel.setEvent(Event.Initialize)
            advanceUntilIdle()

            assertEquals(Effect.Navigation.SwitchScreen(route), effect.await())
        }
}
