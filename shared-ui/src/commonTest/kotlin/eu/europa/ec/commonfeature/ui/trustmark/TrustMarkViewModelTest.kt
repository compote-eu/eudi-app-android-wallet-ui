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

// The Trust Mark screen's view model: upstream (bf514519) has no tests for it. Its job is the guards — the
// welcome completes only once, About never completes, only the two loaded links open — plus one rule of this
// tree: Back on the welcome asks to leave the app WITHOUT locking the screen, because where leaving does
// nothing (iOS) a locked welcome would have no working Continue.
package eu.europa.ec.commonfeature.ui.trustmark

import eu.europa.ec.commonfeature.config.TrustMarkMode
import eu.europa.ec.commonfeature.config.TrustMarkUiConfig
import eu.europa.ec.commonfeature.interactor.CompleteTrustMarkIntroductionPartialState
import eu.europa.ec.commonfeature.interactor.LoadTrustMarkPartialState
import eu.europa.ec.commonfeature.interactor.TrustMarkInteractor
import eu.europa.ec.commonfeature.model.PinFlow
import eu.europa.ec.commonfeature.ui.trustmark.model.TrustMarkParagraphUi
import eu.europa.ec.commonfeature.ui.trustmark.model.TrustMarkUi
import eu.europa.ec.shared.navigation.QuickPinRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TrustMarkViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val continuation = QuickPinRoute(PinFlow.CREATE_WITHOUT_ACTIVATION)
    private val welcome = TrustMarkUiConfig(mode = TrustMarkMode.Welcome(continuationRoute = continuation))
    private val about = TrustMarkUiConfig(mode = TrustMarkMode.About)

    private val trustMark = TrustMarkUi(
        imageUrl = "https://example.com/assets/logo.svg",
        text = "Certified.",
        certifiedWalletsUrl = "https://example.com/wallets",
        walletSolutionUrl = "https://example.com/wallets?id=1",
        certificationDescription = TrustMarkParagraphUi(text = "Listed.", linkRange = null),
        certificationInformationDescription = TrustMarkParagraphUi(text = "Details.", linkRange = null),
    )

    private class FakeTrustMarkInteractor(
        vararg loads: LoadTrustMarkPartialState,
        var completion: CompleteTrustMarkIntroductionPartialState = CompleteTrustMarkIntroductionPartialState.Success,
    ) : TrustMarkInteractor {
        private val loads = loads.toList()
        var loadCount = 0
            private set
        var completionCount = 0
            private set

        override suspend fun getTrustMark(): LoadTrustMarkPartialState {
            loadCount++
            return loads[minOf(loadCount, loads.size) - 1]
        }

        override suspend fun completeIntroduction(): CompleteTrustMarkIntroductionPartialState {
            completionCount++
            return completion
        }
    }

    private fun loaded() = FakeTrustMarkInteractor(LoadTrustMarkPartialState.Success(trustMark))

    private fun TestScope.effectsOf(viewModel: TrustMarkViewModel): List<Effect> {
        val effects = mutableListOf<Effect>()
        backgroundScope.launch { viewModel.effect.toList(effects) }
        return effects
    }

    @Test
    fun the_trust_mark_loads_on_construction() = runTest(mainDispatcher) {
        val fake = loaded()
        val viewModel = TrustMarkViewModel(fake, about)
        advanceUntilIdle()

        assertEquals(trustMark, viewModel.viewState.value.trustMark)
        assertFalse(viewModel.viewState.value.isLoading)
        assertEquals(1, fake.loadCount)
    }

    @Test
    fun a_load_failure_is_shown_and_retry_loads_again() = runTest(mainDispatcher) {
        val fake = FakeTrustMarkInteractor(
            LoadTrustMarkPartialState.Failure(error = "Unable to load"),
            LoadTrustMarkPartialState.Success(trustMark),
        )
        val viewModel = TrustMarkViewModel(fake, welcome)
        advanceUntilIdle()
        assertEquals("Unable to load", viewModel.viewState.value.loadError)

        viewModel.setEvent(Event.Retry)
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.loadError)
        assertEquals(trustMark, viewModel.viewState.value.trustMark)
        assertEquals(2, fake.loadCount)
    }

    @Test
    fun continue_on_the_welcome_completes_the_introduction_once_and_goes_on() = runTest(mainDispatcher) {
        val fake = loaded()
        val viewModel = TrustMarkViewModel(fake, welcome)
        advanceUntilIdle()
        val effects = effectsOf(viewModel)

        viewModel.setEvent(Event.Continue)
        viewModel.setEvent(Event.Continue)
        advanceUntilIdle()

        assertEquals(1, fake.completionCount)
        assertEquals(listOf<Effect>(Effect.Navigation.Continue(route = continuation)), effects)
        assertTrue(viewModel.viewState.value.isNavigating)
    }

    @Test
    fun continue_is_ignored_on_about() = runTest(mainDispatcher) {
        val fake = loaded()
        val viewModel = TrustMarkViewModel(fake, about)
        advanceUntilIdle()
        val effects = effectsOf(viewModel)

        viewModel.setEvent(Event.Continue)
        advanceUntilIdle()

        assertEquals(0, fake.completionCount)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun a_completion_failure_is_shown_and_continue_stays_available() = runTest(mainDispatcher) {
        val fake = loaded().apply {
            completion = CompleteTrustMarkIntroductionPartialState.Failure(error = "Could not save")
        }
        val viewModel = TrustMarkViewModel(fake, welcome)
        advanceUntilIdle()

        viewModel.setEvent(Event.Continue)
        advanceUntilIdle()

        assertEquals("Could not save", viewModel.viewState.value.completionError)
        assertFalse(viewModel.viewState.value.isCompleting)
        assertFalse(viewModel.viewState.value.isNavigating)
    }

    @Test
    fun back_on_about_pops_once() = runTest(mainDispatcher) {
        val viewModel = TrustMarkViewModel(loaded(), about)
        advanceUntilIdle()
        val effects = effectsOf(viewModel)

        viewModel.setEvent(Event.Back)
        viewModel.setEvent(Event.Back)
        advanceUntilIdle()

        assertEquals(listOf<Effect>(Effect.Navigation.Pop), effects)
    }

    @Test
    fun back_on_the_welcome_asks_to_leave_without_locking_continue() = runTest(mainDispatcher) {
        val fake = loaded()
        val viewModel = TrustMarkViewModel(fake, welcome)
        advanceUntilIdle()
        val effects = effectsOf(viewModel)

        viewModel.setEvent(Event.Back)
        advanceUntilIdle()
        // Still here, as on iOS where leaving does nothing: Continue must still work.
        assertFalse(viewModel.viewState.value.isNavigating)
        viewModel.setEvent(Event.Continue)
        advanceUntilIdle()

        assertEquals(
            listOf(Effect.Navigation.Finish, Effect.Navigation.Continue(route = continuation)),
            effects,
        )
        assertEquals(1, fake.completionCount)
    }

    @Test
    fun only_the_two_loaded_links_open() = runTest(mainDispatcher) {
        val viewModel = TrustMarkViewModel(loaded(), about)
        advanceUntilIdle()
        val effects = effectsOf(viewModel)

        viewModel.setEvent(Event.OpenLink(url = "https://evil.example/"))
        viewModel.setEvent(Event.OpenLink(url = trustMark.certifiedWalletsUrl!!))
        viewModel.setEvent(Event.OpenLink(url = trustMark.walletSolutionUrl!!))
        advanceUntilIdle()

        assertEquals(
            listOf<Effect>(
                Effect.OpenUrl(url = trustMark.certifiedWalletsUrl!!),
                Effect.OpenUrl(url = trustMark.walletSolutionUrl!!),
            ),
            effects,
        )
    }
}
