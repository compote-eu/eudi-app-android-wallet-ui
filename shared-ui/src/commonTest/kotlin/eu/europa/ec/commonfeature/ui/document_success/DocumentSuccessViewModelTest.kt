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

// The abstract `DocumentSuccessViewModel` base, exercised through its two presentation subclasses
// rather than a stand-in, because the interesting decision is a *collaboration*: the base's sticky
// button asks `getPendingIntent()` first and only navigates when there is none. Proximity always
// returns null; presentation may not — so testing both real subclasses covers both sides of that
// branch, and the two also differ in how they compute the next destination.
//
// One case necessarily sits elsewhere: the pending-intent path emits `FinishWithResult(PlatformIntent)`
// and so needs a real Intent. See PresentationSuccessViewModelAndroidTest.
//
// Note both subclasses call `doWork()` from their own `init` (the base cannot, since `doWork` is
// abstract), so the load happens on construction — no Init event to send.
package eu.europa.ec.commonfeature.ui.document_success

import eu.europa.ec.presentationfeature.interactor.PresentationSuccessInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationSuccessInteractorGetUiItemsPartialState
import eu.europa.ec.presentationfeature.ui.success.PresentationSuccessViewModel
import eu.europa.ec.proximityfeature.interactor.ProximitySuccessInteractor
import eu.europa.ec.proximityfeature.interactor.ProximitySuccessInteractorGetUiItemsPartialState
import eu.europa.ec.proximityfeature.ui.success.ProximitySuccessViewModel
import eu.europa.ec.shared.navigation.AppRouteCodec
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSuccessViewModelTest {

    private class FakeProximitySuccessInteractor(
        private val states: List<ProximitySuccessInteractorGetUiItemsPartialState>,
    ) : ProximitySuccessInteractor {
        override var presentationScopeId: String = ""
            private set
        var stopCount: Int = 0
            private set

        override fun setScopeId(scopeId: String) {
            presentationScopeId = scopeId
        }

        override fun getUiItems(): Flow<ProximitySuccessInteractorGetUiItemsPartialState> =
            flow { states.forEach { emit(it) } }

        override fun stopPresentation() {
            stopCount++
        }
    }

    internal class FakePresentationSuccessInteractor(
        private val states: List<PresentationSuccessInteractorGetUiItemsPartialState>,
        override val redirectUri: String? = null,
        override val initiatorRoute: String = AppRouteCodec.encode(DashboardRoute),
        private val pendingIntent: PlatformIntent? = null,
    ) : PresentationSuccessInteractor {
        override var presentationScopeId: String = ""
            private set
        var stopCount: Int = 0
            private set

        override fun setScopeId(scopeId: String) {
            presentationScopeId = scopeId
        }

        override fun getPendingIntent(): PlatformIntent? = pendingIntent

        override fun getUiItems(): Flow<PresentationSuccessInteractorGetUiItemsPartialState> =
            flow { states.forEach { emit(it) } }

        override fun stopPresentation() {
            stopCount++
        }
    }

    internal companion object {
        const val SCOPE = "scope-success"

        /** A document row with one nested claim, so expand/collapse has something to toggle. */
        fun documentRow(itemId: String, expanded: Boolean = false) =
            ExpandableListItemUi.NestedListItem(
                header = ListItemDataUi(
                    itemId = itemId,
                    mainContentData = ListItemMainContentDataUi.Text("doc-$itemId"),
                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                        iconData = AppIcons.KeyboardArrowDown,
                    ),
                ),
                nestedItems = listOf(
                    ExpandableListItemUi.SingleListItem(
                        header = ListItemDataUi(
                            itemId = "$itemId-claim",
                            mainContentData = ListItemMainContentDataUi.Text("claim"),
                        ),
                    ),
                ),
                isExpanded = expanded,
            )

        fun header(text: String) =
            ContentHeaderConfig(description = UiText.Raw(text))

        fun presentationSuccess(vararg rows: ExpandableListItemUi.NestedListItem) =
            PresentationSuccessInteractorGetUiItemsPartialState.Success(
                documentsUi = rows.toList(),
                headerConfig = header("shared with Acme"),
                bannerText = UiText.Raw("done"),
            )
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // ---- proximity subclass ------------------------------------------------------------------

    @Test
    fun proximity_loads_its_documents_on_construction() = runTest(mainDispatcher) {
        val fake = FakeProximitySuccessInteractor(
            listOf(
                ProximitySuccessInteractorGetUiItemsPartialState.Success(
                    documentsUi = listOf(documentRow("d1")),
                    headerConfig = header("shared in person"),
                    bannerText = UiText.Raw("done"),
                )
            )
        )
        val viewModel = ProximitySuccessViewModel(fake, SCOPE)
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.items.size)
        assertEquals(SCOPE, fake.presentationScopeId)
        assertEquals(UiText.Raw("shared in person"), state.headerConfig.description)
    }

    @Test
    fun proximity_stops_loading_even_when_the_items_fail() = runTest(mainDispatcher) {
        val fake = FakeProximitySuccessInteractor(
            listOf(ProximitySuccessInteractorGetUiItemsPartialState.Failed("no items"))
        )
        val viewModel = ProximitySuccessViewModel(fake, SCOPE)
        advanceUntilIdle()

        // The exchange already succeeded, so a rendering failure must not leave a spinner up.
        assertFalse(viewModel.viewState.value.isLoading)
        assertTrue(viewModel.viewState.value.items.isEmpty())
    }

    @Test
    fun proximity_has_no_pending_intent_so_its_button_pops_to_the_dashboard() =
        runTest(mainDispatcher) {
            val fake = FakeProximitySuccessInteractor(
                listOf(
                    ProximitySuccessInteractorGetUiItemsPartialState.Success(
                        documentsUi = listOf(documentRow("d1")),
                        headerConfig = header("h"),
                        bannerText = UiText.Raw("done"),
                    )
                )
            )
            val viewModel = ProximitySuccessViewModel(fake, SCOPE)
            advanceUntilIdle()

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.StickyButtonPressed)
            advanceUntilIdle()

            val navigation = assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
            assertEquals(DashboardRoute, navigation.route)
            assertFalse(navigation.inclusive)
        }

    @Test
    fun expanding_a_document_flips_its_chevron() = runTest(mainDispatcher) {
        val fake = FakeProximitySuccessInteractor(
            listOf(
                ProximitySuccessInteractorGetUiItemsPartialState.Success(
                    documentsUi = listOf(documentRow("d1")),
                    headerConfig = header("h"),
                    bannerText = UiText.Raw("done"),
                )
            )
        )
        val viewModel = ProximitySuccessViewModel(fake, SCOPE)
        advanceUntilIdle()

        viewModel.setEvent(Event.ExpandOrCollapseSuccessDocumentItem(itemId = "d1"))
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.items.single().isExpanded)

        viewModel.setEvent(Event.ExpandOrCollapseSuccessDocumentItem(itemId = "d1"))
        advanceUntilIdle()
        assertFalse(viewModel.viewState.value.items.single().isExpanded)
    }

    @Test
    fun expanding_an_unknown_item_leaves_every_row_alone() = runTest(mainDispatcher) {
        val fake = FakeProximitySuccessInteractor(
            listOf(
                ProximitySuccessInteractorGetUiItemsPartialState.Success(
                    documentsUi = listOf(documentRow("d1"), documentRow("d2")),
                    headerConfig = header("h"),
                    bannerText = UiText.Raw("done"),
                )
            )
        )
        val viewModel = ProximitySuccessViewModel(fake, SCOPE)
        advanceUntilIdle()

        viewModel.setEvent(Event.ExpandOrCollapseSuccessDocumentItem(itemId = "nope"))
        advanceUntilIdle()

        assertTrue(viewModel.viewState.value.items.none { it.isExpanded })
    }

    // ---- presentation subclass ---------------------------------------------------------------

    @Test
    fun presentation_loads_its_documents_on_construction() = runTest(mainDispatcher) {
        val fake = FakePresentationSuccessInteractor(
            listOf(presentationSuccess(documentRow("d1")))
        )
        val viewModel = PresentationSuccessViewModel(fake, SCOPE)
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.items.size)
        assertEquals(SCOPE, fake.presentationScopeId)
    }

    @Test
    fun presentation_without_a_redirect_pops_to_the_dashboard() = runTest(mainDispatcher) {
        val fake = FakePresentationSuccessInteractor(
            listOf(presentationSuccess(documentRow("d1"))),
            redirectUri = null,
        )
        val viewModel = PresentationSuccessViewModel(fake, SCOPE)
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.StickyButtonPressed)
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
        assertEquals(DashboardRoute, navigation.route)
    }

    @Test
    fun presentation_with_a_redirect_deep_links_back_to_the_relying_party() =
        runTest(mainDispatcher) {
            val fake = FakePresentationSuccessInteractor(
                listOf(presentationSuccess(documentRow("d1"))),
                redirectUri = "https://rp.test/callback?code=1",
                initiatorRoute = AppRouteCodec.encode(DashboardRoute),
            )
            val viewModel = PresentationSuccessViewModel(fake, SCOPE)
            advanceUntilIdle()

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.StickyButtonPressed)
            advanceUntilIdle()

            val navigation = assertIs<Effect.Navigation.DeepLink>(effect.await())
            assertEquals("https://rp.test/callback?code=1", navigation.link)
            // The initiator travelled through :core-logic as an encoded String and must decode back.
            assertEquals(DashboardRoute, navigation.routeToPop)
        }

    @Test
    fun presentation_stops_loading_even_when_the_items_fail() = runTest(mainDispatcher) {
        val fake = FakePresentationSuccessInteractor(
            listOf(PresentationSuccessInteractorGetUiItemsPartialState.Failed("no items"))
        )
        val viewModel = PresentationSuccessViewModel(fake, SCOPE)
        advanceUntilIdle()

        assertFalse(viewModel.viewState.value.isLoading)
        assertTrue(viewModel.viewState.value.items.isEmpty())
    }

    @Test
    fun the_initial_header_has_no_description_until_the_interactor_supplies_one() =
        runTest(mainDispatcher) {
            // Deliberately never emits, so the pre-load state is observable.
            val fake = FakePresentationSuccessInteractor(emptyList())
            val viewModel = PresentationSuccessViewModel(fake, SCOPE)
            advanceUntilIdle()

            assertNull(viewModel.viewState.value.headerConfig.description)
            assertTrue(viewModel.viewState.value.items.isEmpty())
        }
}
