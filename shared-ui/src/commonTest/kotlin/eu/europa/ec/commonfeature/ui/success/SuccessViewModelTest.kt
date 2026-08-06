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

// SuccessViewModel is a pure `ConfigNavigation` -> `Effect.Navigation` mapper with no interactor, so
// it is entirely testable in common code — every branch, on both platforms.
//
// Two mappings are worth pinning rather than assuming:
//   * `Pop` and `Finish` collapse onto the same `Effect.Navigation.Pop`;
//   * `Deeplink.routeToPop` arrives as an AppRouteCodec-encoded String and must come back out as a
//     real AppRoute — it is a String only because :core-logic cannot depend on :shared-ui.
package eu.europa.ec.commonfeature.ui.success

import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.shared.navigation.AppRouteCodec
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.SuccessRoute
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SuccessViewModelTest {

    private companion object {
        fun button(navigationType: NavigationType) = SuccessUIConfig.ButtonConfig(
            text = UiText.Raw("ok"),
            style = SuccessUIConfig.ButtonConfig.Style.PRIMARY,
            navigation = ConfigNavigation(navigationType = navigationType),
        )

        fun config(onBack: NavigationType = NavigationType.Pop) = SuccessUIConfig(
            textElementsConfig = SuccessUIConfig.TextElementsConfig(
                text = UiText.Raw("done"),
                description = UiText.Raw("all good"),
            ),
            imageConfig = SuccessUIConfig.ImageConfig(),
            buttonConfig = emptyList(),
            onBackScreenToNavigate = ConfigNavigation(navigationType = onBack),
        )
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_injected_config_becomes_the_initial_state() = runTest(mainDispatcher) {
        val config = config()
        val viewModel = SuccessViewModel(config)

        assertEquals(config, viewModel.viewState.value.successConfig)
    }

    @Test
    fun a_button_that_pushes_a_route_switches_screen_and_carries_pop_up_to() =
        runTest(mainDispatcher) {
            val viewModel = SuccessViewModel(config())

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(
                Event.ButtonClicked(
                    button(NavigationType.PushRoute(route = DashboardRoute, popUpTo = SuccessRoute(config())))
                )
            )
            advanceUntilIdle()

            val navigation = assertIs<Effect.Navigation.SwitchScreen>(effect.await())
            assertEquals(DashboardRoute, navigation.route)
            assertEquals(SuccessRoute(config()), navigation.popUpTo)
        }

    @Test
    fun a_button_that_pops_to_a_route_pops_exclusively() = runTest(mainDispatcher) {
        val viewModel = SuccessViewModel(config())

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.ButtonClicked(button(NavigationType.PopTo(DashboardRoute))))
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
        assertEquals(DashboardRoute, navigation.route)
        assertFalse(navigation.inclusive)
    }

    @Test
    fun a_button_that_pops_maps_to_pop() = runTest(mainDispatcher) {
        val viewModel = SuccessViewModel(config())

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.ButtonClicked(button(NavigationType.Pop)))
        advanceUntilIdle()

        assertIs<Effect.Navigation.Pop>(effect.await())
    }

    @Test
    fun finish_maps_to_pop_as_well() = runTest(mainDispatcher) {
        val viewModel = SuccessViewModel(config())

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.ButtonClicked(button(NavigationType.Finish)))
        advanceUntilIdle()

        // Deliberately the same effect as Pop — the success screen has nothing to finish.
        assertIs<Effect.Navigation.Pop>(effect.await())
    }

    @Test
    fun a_deeplink_button_keeps_the_link_as_a_string_and_decodes_the_route_to_pop() =
        runTest(mainDispatcher) {
            val viewModel = SuccessViewModel(config())

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(
                Event.ButtonClicked(
                    button(
                        NavigationType.Deeplink(
                            link = "eudi-wallet://issue",
                            routeToPop = AppRouteCodec.encode(DashboardRoute),
                        )
                    )
                )
            )
            advanceUntilIdle()

            val navigation = assertIs<Effect.Navigation.DeepLink>(effect.await())
            // Still a String: parsing to a platform URI is the consuming screen's job.
            assertEquals("eudi-wallet://issue", navigation.link)
            // ...but the encoded route must come back as a real destination.
            assertEquals(DashboardRoute, navigation.routeToPop)
        }

    @Test
    fun a_deeplink_without_a_route_to_pop_decodes_to_null() = runTest(mainDispatcher) {
        val viewModel = SuccessViewModel(config())

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(
            Event.ButtonClicked(
                button(NavigationType.Deeplink(link = "eudi-wallet://issue", routeToPop = null))
            )
        )
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.DeepLink>(effect.await())
        assertNull(navigation.routeToPop)
    }

    @Test
    fun back_follows_the_config_s_own_back_navigation() = runTest(mainDispatcher) {
        val viewModel = SuccessViewModel(config(onBack = NavigationType.PopTo(DashboardRoute)))

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.BackPressed)
        advanceUntilIdle()

        // Back is configurable, not hardcoded: the same screen is reused by several flows.
        val navigation = assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
        assertEquals(DashboardRoute, navigation.route)
    }
}
