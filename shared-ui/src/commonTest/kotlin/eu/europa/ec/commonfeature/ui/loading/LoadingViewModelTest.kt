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

// The abstract `LoadingViewModel` base, driven through a minimal test subclass rather than through
// either real one. The two real subclasses differ only in what `doWork` does, and `doWork` takes a
// `PlatformContext` — an `expect class` with no common constructor, and deliberately uninhabited on
// iOS — so their work paths can only be exercised from the Android host tests (see
// ProximityLoadingViewModelAndroidTest). Everything the base decides on its own is common, and lives
// here so it runs on both targets:
//
//   * the cancellable timeout, which is what stops a user cancelling a presentation mid-flight;
//   * the once-per-instance guard on `startInitialWork`;
//   * the navigation mapping, which collapses Pop/Finish onto the *previous* route rather than an
//     ordinary back step.
package eu.europa.ec.commonfeature.ui.loading

import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.ProximityRequestRoute
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.config.NavigationType
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingViewModelTest {

    /**
     * Stands in for ProximityLoading/PresentationLoading, recording every [doWork] call and the handle
     * it arrived with.
     *
     * It used to say `doWork` "cannot be invoked from a test, since that needs a `PlatformContext`".
     * That was the shape of the bug: the handle is null on iOS, and requiring one meant the work never
     * started there at all. It is nullable now, so a test can drive exactly what iOS does.
     */
    private class TestLoadingViewModel(
        private val timeout: Duration,
        private val previousRoute: AppRoute = ProximityRequestRoute("scope"),
    ) : LoadingViewModel() {
        var doWorkCalls: Int = 0
            private set

        /** The handle the last [doWork] arrived with — null is what iOS passes. */
        var lastContext: PlatformContext? = null
            private set

        override fun getHeaderConfig(): ContentHeaderConfig =
            ContentHeaderConfig(description = UiText.Raw("loading"))

        override fun getPreviousRoute(): AppRoute = previousRoute
        override fun getCallerRoute(): AppRoute = DashboardRoute
        override fun getCancellableTimeout(): Duration = timeout

        override fun doWork(context: PlatformContext?) {
            doWorkCalls++
            lastContext = context
        }

        /** Exposes the protected mapping so each `NavigationType` branch can be asserted directly. */
        fun navigate(navigationType: NavigationType) = doNavigation(navigationType)

        fun setError(config: ContentErrorConfig) = setState { copy(error = config) }
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_work_starts_even_without_a_platform_handle() = runTest(mainDispatcher) {
        // iOS has no `PlatformContext`, and the screen used to guard the start on having one, so the
        // presentation was never sent and the screen span forever. The handle is an argument, never a
        // precondition.
        val viewModel = TestLoadingViewModel(timeout = Duration.ZERO)

        viewModel.startInitialWork(context = null)

        assertEquals(1, viewModel.doWorkCalls)
        assertNull(viewModel.lastContext)
    }

    @Test
    fun the_work_runs_once_per_instance_however_often_the_screen_recomposes() = runTest(mainDispatcher) {
        val viewModel = TestLoadingViewModel(timeout = Duration.ZERO)

        repeat(3) { viewModel.startInitialWork(context = null) }

        assertEquals(1, viewModel.doWorkCalls)
    }

    @Test
    fun a_positive_timeout_starts_the_screen_uncancellable() = runTest(mainDispatcher) {
        val viewModel = TestLoadingViewModel(timeout = 5.seconds)

        // The whole point: while the wallet is mid-exchange the user must not be able to cancel.
        assertFalse(viewModel.viewState.value.isCancellable)
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun a_zero_timeout_starts_the_screen_cancellable() = runTest(mainDispatcher) {
        val viewModel = TestLoadingViewModel(timeout = Duration.ZERO)

        assertTrue(viewModel.viewState.value.isCancellable)
    }

    @Test
    fun the_screen_becomes_cancellable_only_after_the_timeout_elapses() = runTest(mainDispatcher) {
        val viewModel = TestLoadingViewModel(timeout = 5.seconds)

        viewModel.setEvent(Event.Initialize)
        advanceTimeBy(4_999)
        assertFalse(viewModel.viewState.value.isCancellable)

        advanceTimeBy(2)
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.isCancellable)
    }

    @Test
    fun initialize_without_a_timeout_leaves_it_cancellable_and_schedules_nothing() =
        runTest(mainDispatcher) {
            val viewModel = TestLoadingViewModel(timeout = Duration.ZERO)

            viewModel.setEvent(Event.Initialize)
            advanceUntilIdle()

            assertTrue(viewModel.viewState.value.isCancellable)
        }

    @Test
    fun going_back_clears_the_error_and_pops_to_the_previous_route() = runTest(mainDispatcher) {
        val previous = ProximityRequestRoute("scope-42")
        val viewModel = TestLoadingViewModel(timeout = 5.seconds, previousRoute = previous)
        viewModel.setError(ContentErrorConfig(errorSubTitle = UiText.Raw("boom"), onCancel = {}))

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.GoBack)
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
        // Pop maps to the PREVIOUS route, not a plain back step: the loading screen sits on top of
        // the request screen and must not leave the user on a dead spinner.
        assertEquals(previous, navigation.route)
        assertFalse(navigation.inclusive)
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun dismissing_an_error_clears_it_without_navigating() = runTest(mainDispatcher) {
        val viewModel = TestLoadingViewModel(timeout = 5.seconds)
        viewModel.setError(ContentErrorConfig(errorSubTitle = UiText.Raw("boom"), onCancel = {}))

        viewModel.setEvent(Event.DismissError)
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun finish_pops_to_the_previous_route_just_like_pop() = runTest(mainDispatcher) {
        val previous = ProximityRequestRoute("scope-7")
        val viewModel = TestLoadingViewModel(timeout = Duration.ZERO, previousRoute = previous)

        val effect = async { viewModel.effect.first() }
        viewModel.navigate(NavigationType.Finish)
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
        assertEquals(previous, navigation.route)
    }

    @Test
    fun pop_to_an_explicit_route_uses_that_route() = runTest(mainDispatcher) {
        val viewModel = TestLoadingViewModel(timeout = Duration.ZERO)

        val effect = async { viewModel.effect.first() }
        viewModel.navigate(NavigationType.PopTo(DashboardRoute))
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
        assertEquals(DashboardRoute, navigation.route)
        assertFalse(navigation.inclusive)
    }

    @Test
    fun pushing_a_route_switches_screen() = runTest(mainDispatcher) {
        val viewModel = TestLoadingViewModel(timeout = Duration.ZERO)

        val effect = async { viewModel.effect.first() }
        viewModel.navigate(NavigationType.PushRoute(DashboardRoute))
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.SwitchScreen>(effect.await())
        assertEquals(DashboardRoute, navigation.route)
    }

    @Test
    fun a_deeplink_navigation_is_inert_on_the_loading_screen() = runTest(mainDispatcher) {
        val viewModel = TestLoadingViewModel(timeout = Duration.ZERO)

        // Deliberately unhandled in this base; asserted so the empty branch is a decision, not a gap.
        viewModel.navigate(NavigationType.Deeplink(link = "https://example.test"))
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun the_header_and_previous_route_come_from_the_subclass() = runTest(mainDispatcher) {
        val previous = ProximityRequestRoute("scope-9")
        val viewModel = TestLoadingViewModel(timeout = Duration.ZERO, previousRoute = previous)

        assertEquals(previous, viewModel.getPreviousRoute())
        assertEquals(DashboardRoute, viewModel.getCallerRoute())
        assertEquals(
            UiText.Raw("loading"),
            viewModel.viewState.value.headerConfig.description,
        )
    }
}
