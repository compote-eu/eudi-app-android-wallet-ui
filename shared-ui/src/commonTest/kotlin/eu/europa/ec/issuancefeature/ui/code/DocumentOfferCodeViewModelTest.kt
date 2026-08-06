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

// DocumentOfferCodeViewModel. Every issuance outcome reaches this screen through
// `Event.OnPinEntered`, which carries a `PlatformContext` (entering the code can lead to a device-auth
// prompt) — so those branches live in DocumentOfferCodeViewModelAndroidTest and this file covers the
// screen's own state, navigation and sheet handling.
package eu.europa.ec.issuancefeature.ui.code

import eu.europa.ec.commonfeature.config.OfferCodeUiConfig
import eu.europa.ec.issuancefeature.interactor.DocumentOfferInteractor
import eu.europa.ec.issuancefeature.ui.offer.FakeDocumentOfferInteractor
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import kotlin.test.assertTrue

internal fun offerCodeConfig(
    txCodeLength: Int = 5,
    issuerName: String = "Test Issuer",
    onSuccess: NavigationType = NavigationType.PopTo(route = DashboardRoute),
) = OfferCodeUiConfig(
    offerUri = "openid-credential-offer://issuer?credential_offer=x",
    txCodeLength = txCodeLength,
    issuerName = issuerName,
    onSuccessNavigation = ConfigNavigation(navigationType = onSuccess),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentOfferCodeViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        interactor: DocumentOfferInteractor = FakeDocumentOfferInteractor(),
        config: OfferCodeUiConfig = offerCodeConfig(),
    ) = DocumentOfferCodeViewModel(config, interactor)

    private fun CoroutineScope.collectEffects(
        viewModel: DocumentOfferCodeViewModel,
    ): Pair<List<Effect>, Job> {
        val effects = mutableListOf<Effect>()
        val job = launch { viewModel.effect.collect { effects.add(it) } }
        return effects to job
    }

    //region initial state

    @Test
    fun the_title_and_caption_are_built_from_the_config() = runTest(mainDispatcher) {
        val viewModel = viewModel(config = offerCodeConfig(txCodeLength = 6, issuerName = "ACME"))

        val state = viewModel.viewState.value
        // Both are resource templates carrying their argument, so the strings are resolved by the host
        // in its own locale rather than being pre-formatted here.
        val title = assertIs<UiText.Resource>(state.screenTitle)
        assertEquals(listOf("ACME"), title.args)
        val caption = assertIs<UiText.Resource>(state.screenSubtitle)
        // `UiText.Resource.args` is a List<String>, so the code length arrives stringified.
        assertEquals(listOf("6"), caption.args)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun the_config_is_kept_in_state_for_the_issuance_call() = runTest(mainDispatcher) {
        val config = offerCodeConfig()
        val viewModel = viewModel(config = config)

        // The offer uri and issuer name are read back out of state when the code is submitted, so they
        // have to survive there rather than only in the constructor.
        assertEquals(config, viewModel.viewState.value.offerCodeUiConfig)
    }

    //endregion

    //region navigation and errors

    @Test
    fun popping_clears_any_error_first() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()
        job.cancel()

        assertNull(viewModel.viewState.value.error)
        assertIs<Effect.Navigation.Pop>(effects.single())
    }

    @Test
    fun dismissing_an_error_does_not_navigate() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.DismissError)
        advanceUntilIdle()
        job.cancel()

        // A wrong code should leave the user on the screen to try again, not throw them out of the flow.
        assertNull(viewModel.viewState.value.error)
        assertTrue(effects.isEmpty())
    }

    //endregion

    //region bottom sheet

    @Test
    fun the_bottom_sheet_open_flag_follows_the_host() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.isBottomSheetOpen)

        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
        advanceUntilIdle()
        assertFalse(viewModel.viewState.value.isBottomSheetOpen)
    }

    @Test
    fun closing_the_sheet_is_guarded_against_a_double_close() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BottomSheet.Close)
        advanceUntilIdle()
        viewModel.setEvent(Event.BottomSheet.Close)
        advanceUntilIdle()
        job.cancel()

        // The sheet's own dismissal drives FinishedClosing, which navigates — so a second press must be
        // swallowed rather than navigating twice.
        assertEquals(1, effects.count { it is Effect.CloseBottomSheet })
        assertTrue(viewModel.viewState.value.bottomSheetClosingInProgress)
    }

    @Test
    fun reopening_the_sheet_clears_the_closing_guard() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.setEvent(Event.BottomSheet.Close)
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.bottomSheetClosingInProgress)

        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
        advanceUntilIdle()

        assertFalse(viewModel.viewState.value.bottomSheetClosingInProgress)
    }

    @Test
    fun dismissing_the_untrusted_issuer_sheet_leaves_the_screen() = runTest(mainDispatcher) {
        // IssuerNotTrusted is also the initial sheet content, so this is reachable without submitting a
        // code. There is nothing to issue from an untrusted issuer, so the flow ends.
        val viewModel = viewModel()
        assertIs<DocumentOfferCodeBottomSheetContent.IssuerNotTrusted>(
            viewModel.viewState.value.sheetContent
        )

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BottomSheet.FinishedClosing)
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.Navigation.Pop>(effects.single())
    }

    //endregion
}
