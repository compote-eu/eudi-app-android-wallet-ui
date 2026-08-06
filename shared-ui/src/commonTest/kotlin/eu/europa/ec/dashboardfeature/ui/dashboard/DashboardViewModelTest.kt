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

// DashboardViewModel. All of it is common: `Event.Init` now takes a *pre-extracted* deep link and
// intent action rather than a raw `Intent`, so nothing here needs a platform handle. The intent-action
// branch does carry a `PlatformIntent` inside `IntentAction`, but only as an opaque token the
// view-model hands straight back — see DashboardViewModelAndroidTest for that one.
package eu.europa.ec.dashboardfeature.ui.dashboard

import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.model.PinFlow
import eu.europa.ec.corelogic.model.RevokedDocumentDataDomain
import eu.europa.ec.dashboardfeature.interactor.DashboardInteractor
import eu.europa.ec.dashboardfeature.ui.dashboard.model.SideMenuItemUi
import eu.europa.ec.dashboardfeature.ui.dashboard.model.SideMenuTypeUi
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentDetailsRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.QuickPinRoute
import eu.europa.ec.shared.navigation.SettingsRoute
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.navigation.helper.DeepLinkClassifier
import eu.europa.ec.uilogic.navigation.helper.DeepLinkKind
import eu.europa.ec.uilogic.navigation.helper.FakeDeepLinkClassifier
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

internal class FakeDashboardInteractor(
    private val options: List<SideMenuItemUi> = SideMenuTypeUi.entries.map { type ->
        SideMenuItemUi(
            type = type,
            data = ListItemDataUi(
                itemId = type.itemId,
                mainContentData = ListItemMainContentDataUi.Text(type.name),
            ),
        )
    },
) : DashboardInteractor {
    var optionCalls: Int = 0
        private set

    override fun getSideMenuOptions(): List<SideMenuItemUi> {
        optionCalls++
        return options
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        interactor: DashboardInteractor = FakeDashboardInteractor(),
        classifier: DeepLinkClassifier = FakeDeepLinkClassifier(),
    ) = DashboardViewModel(interactor, classifier)

    private fun CoroutineScope.collectEffects(
        viewModel: DashboardViewModel,
    ): Pair<List<Effect>, Job> {
        val effects = mutableListOf<Effect>()
        val job = launch { viewModel.effect.collect { effects.add(it) } }
        return effects to job
    }

    //region side menu

    @Test
    fun the_side_menu_options_come_from_the_interactor() = runTest(mainDispatcher) {
        val interactor = FakeDashboardInteractor()
        val viewModel = viewModel(interactor)
        advanceUntilIdle()

        // Built in `setInitialState`, which `MviViewModel` holds `by lazy` — so the interactor is not
        // touched until the state is first read. Reading it here is what triggers that.
        val state = viewModel.viewState.value
        assertEquals(1, interactor.optionCalls)
        assertEquals(SideMenuTypeUi.entries.size, state.sideMenuOptions.size)
        assertFalse(state.isSideMenuVisible)
    }

    @Test
    fun opening_and_closing_the_side_menu_slides_it() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.setEvent(Event.SideMenu.Open)
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.isSideMenuVisible)
        assertEquals(SideMenuAnimation.SLIDE, viewModel.viewState.value.sideMenuAnimation)

        viewModel.setEvent(Event.SideMenu.Close)
        advanceUntilIdle()
        assertFalse(viewModel.viewState.value.isSideMenuVisible)
        assertEquals(SideMenuAnimation.SLIDE, viewModel.viewState.value.sideMenuAnimation)
    }

    @Test
    fun choosing_change_pin_fades_the_menu_away_and_navigates() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.setEvent(Event.SideMenu.Open)
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.SideMenu.ItemClicked(SideMenuTypeUi.CHANGE_PIN))
        advanceUntilIdle()
        job.cancel()

        // FADE, not SLIDE: the menu is leaving because the screen is changing under it, so sliding it
        // out would fight the screen transition.
        assertFalse(viewModel.viewState.value.isSideMenuVisible)
        assertEquals(SideMenuAnimation.FADE, viewModel.viewState.value.sideMenuAnimation)
        val switch = assertIs<Effect.Navigation.SwitchScreen>(effects.single())
        assertEquals(QuickPinRoute(pinFlow = PinFlow.UPDATE), switch.route)
    }

    @Test
    fun choosing_settings_fades_the_menu_away_and_navigates() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.SideMenu.ItemClicked(SideMenuTypeUi.SETTINGS))
        advanceUntilIdle()
        job.cancel()

        assertEquals(SideMenuAnimation.FADE, viewModel.viewState.value.sideMenuAnimation)
        val switch = assertIs<Effect.Navigation.SwitchScreen>(effects.single())
        assertEquals(SettingsRoute, switch.route)
    }

    //endregion

    //region revocation notifications

    @Test
    fun a_revocation_notification_offers_each_revoked_document() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(
            Event.DocumentRevocationNotificationReceived(
                payload = listOf(
                    RevokedDocumentDataDomain(name = "PID", id = "doc-1"),
                    RevokedDocumentDataDomain(name = "mDL", id = "doc-2"),
                )
            )
        )
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.ShowBottomSheet>(effects.single())
        val content = assertIs<DashboardBottomSheetContent.DocumentRevocation>(
            viewModel.viewState.value.sheetContent
        )
        assertEquals(listOf("PID", "mDL"), content.options.map { it.title })
        // Each option carries the id that opens that document, not its position.
        val second = assertIs<Event.BottomSheet.DocumentRevocation
        .OptionListItemForRevokedDocumentSelected>(content.options[1].event)
        assertEquals("doc-2", second.documentId)
    }

    @Test
    fun picking_a_revoked_document_closes_the_sheet_and_opens_its_details() =
        runTest(mainDispatcher) {
            val viewModel = viewModel()

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(
                Event.BottomSheet.DocumentRevocation
                    .OptionListItemForRevokedDocumentSelected(documentId = "doc-7")
            )
            advanceUntilIdle()
            job.cancel()

            assertTrue(effects.any { it is Effect.CloseBottomSheet })
            val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
            assertEquals(DocumentDetailsRoute(documentId = "doc-7"), switch.route)
        }

    @Test
    fun an_empty_revocation_notification_still_opens_an_empty_sheet() = runTest(mainDispatcher) {
        // Faithful to the existing behaviour: the sheet is opened by the notification arriving, not by
        // its contents. Worth pinning so a change here is deliberate.
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.DocumentRevocationNotificationReceived(payload = emptyList()))
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.ShowBottomSheet>(effects.single())
        val content = assertIs<DashboardBottomSheetContent.DocumentRevocation>(
            viewModel.viewState.value.sheetContent
        )
        assertTrue(content.options.isEmpty())
    }

    //endregion

    //region deep links

    @Test
    fun an_openid4vp_link_resolves_to_the_presentation_request_screen() = runTest(mainDispatcher) {
        val classifier = FakeDeepLinkClassifier(kind = DeepLinkKind.OPENID4VP)
        val viewModel = viewModel(classifier = classifier)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(
            Event.Init(deepLink = "eudi-openid4vp://request?x=1", intentAction = null)
        )
        advanceUntilIdle()
        job.cancel()

        val open = assertIs<Effect.Navigation.OpenDeepLinkAction>(effects.single())
        assertEquals("eudi-openid4vp://request?x=1", open.deepLinkUri)
        val route = assertIs<PresentationRequestRoute>(open.route)
        val mode = assertIs<PresentationMode.OpenId4Vp>(route.config.mode)
        // The whole link is the request uri, and the dashboard is where cancelling returns to.
        assertEquals("eudi-openid4vp://request?x=1", mode.uri)
        assertEquals(DashboardRoute, mode.initiatorRoute)
    }

    @Test
    fun a_credential_offer_link_resolves_to_the_offer_screen() = runTest(mainDispatcher) {
        val classifier = FakeDeepLinkClassifier(kind = DeepLinkKind.CREDENTIAL_OFFER)
        val viewModel = viewModel(classifier = classifier)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(
            Event.Init(deepLink = "openid-credential-offer://x", intentAction = null)
        )
        advanceUntilIdle()
        job.cancel()

        val open = assertIs<Effect.Navigation.OpenDeepLinkAction>(effects.single())
        val route = assertIs<DocumentOfferRoute>(open.route)
        assertEquals("openid-credential-offer://x", route.config.offerUri)
        // Success pops back to the dashboard rather than pushing it again.
        assertIs<NavigationType.PopTo>(route.config.onSuccessNavigation.navigationType)
        assertIs<NavigationType.Pop>(route.config.onCancelNavigation.navigationType)
    }

    @Test
    fun a_link_with_no_dashboard_destination_is_still_handed_to_the_host() =
        runTest(mainDispatcher) {
            // ISSUANCE and the rest resolve to no route here: the host still has to act on the link
            // (a broadcast, an external URL), so the effect is emitted with a null route rather than
            // being swallowed.
            val classifier = FakeDeepLinkClassifier(kind = DeepLinkKind.ISSUANCE)
            val viewModel = viewModel(classifier = classifier)

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(
                Event.Init(deepLink = "eudi-openid4ci://authorize", intentAction = null)
            )
            advanceUntilIdle()
            job.cancel()

            val open = assertIs<Effect.Navigation.OpenDeepLinkAction>(effects.single())
            assertEquals("eudi-openid4ci://authorize", open.deepLinkUri)
            assertNull(open.route)
        }

    @Test
    fun nothing_pending_means_nothing_happens() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Init(deepLink = null, intentAction = null))
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun a_link_that_is_not_a_deep_link_falls_through_to_the_intent_action() =
        runTest(mainDispatcher) {
            // The two readings of the pending intent are alternatives, and the deep link only wins if
            // it actually classifies — otherwise the intent action must still get its turn.
            val classifier = FakeDeepLinkClassifier(kind = null)
            val viewModel = viewModel(classifier = classifier)

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.Init(deepLink = "not-a-link", intentAction = null))
            advanceUntilIdle()
            job.cancel()

            // No intent action supplied here either, so nothing is emitted — but crucially no
            // OpenDeepLinkAction was emitted for an unclassifiable link.
            assertTrue(effects.isEmpty())
            assertEquals(listOf("not-a-link"), (classifier as FakeDeepLinkClassifier).classifiedLinks)
        }

    @Test
    fun popping_navigates_back() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.Navigation.Pop>(effects.single())
    }

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

    //endregion
}
