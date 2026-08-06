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

// The third `DocumentSuccessViewModel` subclass — the one shown after issuance rather than after a
// presentation. Unlike the other two it has no presentation scope to tear down, and its next
// destination is supplied wholesale by the injected config rather than computed, so the assertions
// here are about *which document ids it asks for* and that the configured navigation is honoured
// verbatim.
package eu.europa.ec.issuancefeature.ui.success

import eu.europa.ec.commonfeature.config.IssuanceSuccessUiConfig
import eu.europa.ec.commonfeature.ui.document_success.Effect
import eu.europa.ec.commonfeature.ui.document_success.Event
import eu.europa.ec.issuancefeature.interactor.DocumentIssuanceSuccessInteractor
import eu.europa.ec.issuancefeature.interactor.DocumentIssuanceSuccessInteractorGetUiItemsPartialState
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentIssuanceSuccessViewModelTest {

    private class FakeDocumentIssuanceSuccessInteractor(
        private val states: List<DocumentIssuanceSuccessInteractorGetUiItemsPartialState>,
    ) : DocumentIssuanceSuccessInteractor {
        var requestedIds: List<String>? = null
            private set

        override fun getUiItems(
            documentIds: List<String>,
        ): Flow<DocumentIssuanceSuccessInteractorGetUiItemsPartialState> {
            requestedIds = documentIds
            return flow { states.forEach { emit(it) } }
        }
    }

    private companion object {
        val DOC_IDS = listOf("doc-1", "doc-2")

        fun config(navigationType: NavigationType = NavigationType.PopTo(DashboardRoute)) =
            IssuanceSuccessUiConfig(
                documentIds = DOC_IDS,
                onSuccessNavigation = ConfigNavigation(navigationType = navigationType),
            )

        fun row(itemId: String) = ExpandableListItemUi.NestedListItem(
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
            isExpanded = false,
        )

        fun success(vararg rows: ExpandableListItemUi.NestedListItem) =
            DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Success(
                documentsUi = rows.toList(),
                headerConfig = ContentHeaderConfig(description = UiText.Raw("added")),
            )
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun it_asks_the_interactor_for_exactly_the_configured_document_ids() = runTest(mainDispatcher) {
        val fake = FakeDocumentIssuanceSuccessInteractor(listOf(success(row("d1"), row("d2"))))
        DocumentIssuanceSuccessViewModel(fake, config())
        advanceUntilIdle()

        // The config is the only source of which documents were just issued.
        assertEquals(DOC_IDS, fake.requestedIds)
    }

    @Test
    fun a_successful_load_renders_the_documents_and_header() = runTest(mainDispatcher) {
        val fake = FakeDocumentIssuanceSuccessInteractor(listOf(success(row("d1"), row("d2"))))
        val viewModel = DocumentIssuanceSuccessViewModel(fake, config())
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.items.size)
        assertEquals(UiText.Raw("added"), state.headerConfig.description)
    }

    @Test
    fun a_failed_load_stops_the_spinner_and_shows_nothing() = runTest(mainDispatcher) {
        val fake = FakeDocumentIssuanceSuccessInteractor(
            listOf(DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Failed("boom"))
        )
        val viewModel = DocumentIssuanceSuccessViewModel(fake, config())
        advanceUntilIdle()

        // The documents were issued regardless, so a rendering failure must not trap the user.
        assertFalse(viewModel.viewState.value.isLoading)
        assertTrue(viewModel.viewState.value.items.isEmpty())
    }

    @Test
    fun the_sticky_button_follows_the_configured_navigation() = runTest(mainDispatcher) {
        val fake = FakeDocumentIssuanceSuccessInteractor(listOf(success(row("d1"))))
        val viewModel = DocumentIssuanceSuccessViewModel(fake, config())
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.StickyButtonPressed)
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
        assertEquals(DashboardRoute, navigation.route)
    }

    @Test
    fun a_configured_push_navigation_switches_screen_instead() = runTest(mainDispatcher) {
        val fake = FakeDocumentIssuanceSuccessInteractor(listOf(success(row("d1"))))
        val viewModel = DocumentIssuanceSuccessViewModel(
            fake,
            config(NavigationType.PushRoute(route = DashboardRoute)),
        )
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.StickyButtonPressed)
        advanceUntilIdle()

        // Issuance can be reached from several places, so the destination is never assumed.
        val navigation = assertIs<Effect.Navigation.SwitchScreen>(effect.await())
        assertEquals(DashboardRoute, navigation.route)
    }

    @Test
    fun expanding_a_document_flips_its_chevron() = runTest(mainDispatcher) {
        val fake = FakeDocumentIssuanceSuccessInteractor(listOf(success(row("d1"))))
        val viewModel = DocumentIssuanceSuccessViewModel(fake, config())
        advanceUntilIdle()

        viewModel.setEvent(Event.ExpandOrCollapseSuccessDocumentItem(itemId = "d1"))
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.items.single().isExpanded)

        viewModel.setEvent(Event.ExpandOrCollapseSuccessDocumentItem(itemId = "d1"))
        advanceUntilIdle()
        assertFalse(viewModel.viewState.value.items.single().isExpanded)
    }
}
