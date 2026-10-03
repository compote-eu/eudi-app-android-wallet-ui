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

// TransactionDetailsViewModel touches no platform handle at all, so every branch is covered here and
// runs on both targets. Upstream (7a47e46a, 23b98be0) has no view-model tests for it.
//
// It loads from its `init` (not from an Init event), which is the process-death fix described in
// HomeViewModel — but the `Event.Init` branch stays reachable because the error card's `onRetry`
// re-sends it. Both paths are asserted, since it would be easy to "simplify" the branch away and
// silently break retry.
package eu.europa.ec.dashboardfeature.ui.transactions.detail

import eu.europa.ec.corelogic.controller.RecordTransactionPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorDataDeletionPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorDataProtectionPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorDeleteTransactionPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorDpaReportPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorPartialState
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.PendingTransactionActionUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.PresentationActionCountsUiState
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionContactUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDataProtectionAction
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsBodyUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsCardUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsFieldUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsGroupUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsMetadataUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsSectionUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsUi
import eu.europa.ec.shared.navigation.DataDeletionRequestRoute
import eu.europa.ec.shared.navigation.DpaReportRoute
import eu.europa.ec.shared.navigation.TransactionHistoryRoute
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailsViewModelTest {

    private class FakeTransactionDetailsInteractor(
        private val states: List<TransactionDetailsInteractorPartialState>,
        private val deleteOutcome: TransactionDetailsInteractorDeleteTransactionPartialState =
            TransactionDetailsInteractorDeleteTransactionPartialState.Success,
    ) : TransactionDetailsInteractor {
        var detailsCalls: Int = 0
            private set
        val deletedIds = mutableListOf<String>()

        override fun getTransactionDetails(
            transactionId: String,
        ): Flow<TransactionDetailsInteractorPartialState> = flow {
            detailsCalls++
            states.forEach { emit(it) }
        }

        override fun deleteTransaction(
            transactionId: String,
        ): Flow<TransactionDetailsInteractorDeleteTransactionPartialState> = flow {
            deletedIds += transactionId
            emit(deleteOutcome)
        }

        /** What the action-count stream emits; each subscription is counted. */
        var counts: Flow<PresentationActionCountsUiState> =
            flowOf(PresentationActionCountsUiState.Content(dataDeletionRequests = 0, dpaReports = 0))
        var countSubscriptions: Int = 0
            private set

        override fun observePresentationActionCounts(
            presentationId: String,
        ): Flow<PresentationActionCountsUiState> = flow {
            countSubscriptions++
            emitAll(counts)
        }

        // The action screens' own calls; this view model never makes them.
        override fun getDataDeletionRequest(transactionId: String) =
            flowOf(TransactionDetailsInteractorDataDeletionPartialState.Failure("not used here"))

        override fun getDpaReport(transactionId: String) =
            flowOf(TransactionDetailsInteractorDpaReportPartialState.Failure("not used here"))

        override fun prepareDataProtectionAction(
            transactionId: String,
            action: TransactionDataProtectionAction,
            contactUrl: String,
        ) = flowOf(TransactionDetailsInteractorDataProtectionPartialState.Failure("not used here"))

        override fun recordDataProtectionAction(pendingAction: PendingTransactionActionUi) =
            flowOf(RecordTransactionPartialState.Failure("not used here"))
    }

    private companion object {
        const val TX_ID = "tx-123"
        const val LINK = "https://example.com/privacy"

        fun group(itemId: String) = TransactionDetailsGroupUi(
            header = ListItemDataUi(
                itemId = itemId,
                mainContentData = ListItemMainContentDataUi.Text("credential-$itemId"),
                trailingContentData = ListItemTrailingContentDataUi.Icon(iconData = AppIcons.KeyboardArrowDown),
            ),
            items = listOf(
                ExpandableListItemUi.SingleListItem(
                    header = ListItemDataUi(
                        itemId = "$itemId:0",
                        mainContentData = ListItemMainContentDataUi.Text("[\"ns\"][\"claim\"]"),
                    ),
                ),
            ),
        )

        fun section(title: String, vararg groups: TransactionDetailsGroupUi) = TransactionDetailsSectionUi(
            title = title,
            items = emptyList(),
            groups = groups.toList(),
            emptyItem = null,
        )

        val contact = TransactionContactUi(label = LINK, url = LINK)

        fun details(
            vararg sharedGroups: TransactionDetailsGroupUi,
            metadata: List<TransactionDetailsMetadataUi> = emptyList(),
            deletionContacts: List<TransactionContactUi> = listOf(contact),
            reportContacts: List<TransactionContactUi> = listOf(contact),
        ) = TransactionDetailsUi(
            transactionId = TX_ID,
            transactionDetailsCardUi = TransactionDetailsCardUi(
                transactionTypeLabel = "Presentation",
                transactionStatusLabel = "Completed",
                transactionIsCompleted = true,
                transactionDate = "06 Aug 2026",
                partyName = "Acme",
                providerType = null,
                nonCompletionReason = null,
                metadata = metadata,
            ),
            body = TransactionDetailsBodyUi.Presentation(
                requested = section("DATA REQUESTED", *sharedGroups),
                shared = section("DATA SHARED", *sharedGroups),
                deletionContacts = deletionContacts,
                reportContacts = reportContacts,
                actionCounts = PresentationActionCountsUiState.Loading,
                transactionData = null,
            ),
        )

        fun success(
            vararg sharedGroups: TransactionDetailsGroupUi,
            metadata: List<TransactionDetailsMetadataUi> = emptyList(),
        ) = TransactionDetailsInteractorPartialState.Success(details(*sharedGroups, metadata = metadata))

        val policyMetadata = listOf(
            TransactionDetailsMetadataUi(
                fields = listOf(TransactionDetailsFieldUi("privacy:0", "Privacy policy", LINK, LINK)),
            )
        )
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(vararg states: TransactionDetailsInteractorPartialState) =
        FakeTransactionDetailsInteractor(states.toList()).let { fake ->
            fake to TransactionDetailsViewModel(fake, TX_ID)
        }

    private fun CoroutineScope.collectEffects(viewModel: TransactionDetailsViewModel): MutableList<Effect> {
        val effects = mutableListOf<Effect>()
        launch { viewModel.effect.collect { effects += it } }
        return effects
    }

    private fun TransactionDetailsViewModel.sharedGroup(): TransactionDetailsGroupUi =
        (viewState.value.transactionDetailsUi!!.body as TransactionDetailsBodyUi.Presentation).shared.groups.single()

    @Test
    fun the_details_load_on_construction_not_on_an_event() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(group("g1")))
        advanceUntilIdle()

        // Loading from `init` is what survives process death; see HomeViewModel's note.
        assertEquals(1, fake.detailsCalls)
        val state = viewModel.viewState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(TX_ID, assertNotNull(state.transactionDetailsUi).transactionId)
    }

    @Test
    fun a_successful_load_exposes_the_card_and_the_sections() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(group("g1"), group("g2")))
        advanceUntilIdle()

        val details = assertNotNull(viewModel.viewState.value.transactionDetailsUi)
        assertEquals("Acme", details.transactionDetailsCardUi.partyName)
        assertTrue(details.transactionDetailsCardUi.transactionIsCompleted)
        assertEquals(listOf("DATA REQUESTED", "DATA SHARED"), details.body.sections.map { it.title })
    }

    @Test
    fun a_failure_becomes_a_retryable_error_that_reloads() = runTest(mainDispatcher) {
        val fake = FakeTransactionDetailsInteractor(
            listOf(TransactionDetailsInteractorPartialState.Failure("boom"))
        )
        val viewModel = TransactionDetailsViewModel(fake, TX_ID)
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        assertEquals(1, fake.detailsCalls)

        // The retry callback re-sends Event.Init, which is why that branch must stay.
        assertNotNull(error.onRetry).invoke()
        advanceUntilIdle()
        assertEquals(2, fake.detailsCalls)
    }

    @Test
    fun dismissing_an_error_clears_it_without_reloading() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(TransactionDetailsInteractorPartialState.Failure("boom"))
        advanceUntilIdle()
        assertNotNull(viewModel.viewState.value.error)

        viewModel.setEvent(Event.DismissError)
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.error)
        assertEquals(1, fake.detailsCalls)
    }

    @Test
    fun popping_clears_the_error_and_navigates_back() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(TransactionDetailsInteractorPartialState.Failure("boom"))
        advanceUntilIdle()
        val effects = backgroundScope.collectEffects(viewModel)

        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()

        assertIs<Effect.Navigation.Pop>(effects.single())
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun expanding_a_credential_group_flips_its_chevron_and_back() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(group("g1")))
        advanceUntilIdle()

        viewModel.setEvent(Event.ExpandOrCollapseGroupItem(itemId = "g1"))
        advanceUntilIdle()
        assertEquals(setOf("g1"), viewModel.viewState.value.expandedGroupIds)
        assertEquals(
            AppIcons.KeyboardArrowUp,
            (viewModel.sharedGroup().header.trailingContentData as ListItemTrailingContentDataUi.Icon).iconData,
        )

        viewModel.setEvent(Event.ExpandOrCollapseGroupItem(itemId = "g1"))
        advanceUntilIdle()
        assertEquals(emptySet(), viewModel.viewState.value.expandedGroupIds)
        assertEquals(
            AppIcons.KeyboardArrowDown,
            (viewModel.sharedGroup().header.trailingContentData as ListItemTrailingContentDataUi.Icon).iconData,
        )
    }

    @Test
    fun a_reload_of_the_same_transaction_keeps_what_was_expanded() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(group("g1"), metadata = policyMetadata))
        advanceUntilIdle()
        viewModel.setEvent(Event.ExpandOrCollapseGroupItem(itemId = "g1"))
        viewModel.setEvent(Event.ExpandOrCollapseCard)
        advanceUntilIdle()

        viewModel.setEvent(Event.Init)
        advanceUntilIdle()

        assertEquals(setOf("g1"), viewModel.viewState.value.expandedGroupIds)
        assertTrue(viewModel.viewState.value.isCardExpanded)
    }

    @Test
    fun expanding_before_the_details_arrive_is_a_safe_no_op() = runTest(mainDispatcher) {
        // Never emits, so `transactionDetailsUi` stays null while the user taps.
        val (_, viewModel) = viewModel()
        advanceUntilIdle()

        viewModel.setEvent(Event.ExpandOrCollapseGroupItem(itemId = "g1"))
        viewModel.setEvent(Event.ExpandOrCollapseCard)
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.transactionDetailsUi)
        assertFalse(viewModel.viewState.value.isCardExpanded)
    }

    @Test
    fun the_card_expands_only_when_it_has_metadata() = runTest(mainDispatcher) {
        val (_, bare) = viewModel(success(group("g1")))
        advanceUntilIdle()
        bare.setEvent(Event.ExpandOrCollapseCard)
        assertFalse(bare.viewState.value.isCardExpanded)

        val (_, withPolicy) = viewModel(success(group("g1"), metadata = policyMetadata))
        advanceUntilIdle()
        withPolicy.setEvent(Event.ExpandOrCollapseCard)
        assertTrue(withPolicy.viewState.value.isCardExpanded)
        withPolicy.setEvent(Event.ExpandOrCollapseCard)
        assertFalse(withPolicy.viewState.value.isCardExpanded)
    }

    @Test
    fun a_link_opens_externally_once_the_details_are_loaded() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(group("g1"), metadata = policyMetadata))
        advanceUntilIdle()
        val effects = backgroundScope.collectEffects(viewModel)

        viewModel.setEvent(Event.OpenLink(LINK))
        advanceUntilIdle()

        assertEquals(LINK, assertIs<Effect.Navigation.OpenUrlExternally>(effects.single()).url)
    }

    @Test
    fun delete_asks_for_confirmation_before_anything_is_deleted() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(group("g1")))
        advanceUntilIdle()
        val effects = backgroundScope.collectEffects(viewModel)

        viewModel.setEvent(Event.DeletePressed)
        advanceUntilIdle()

        assertIs<Effect.ShowBottomSheet>(effects.single())
        assertTrue(fake.deletedIds.isEmpty())
    }

    @Test
    fun delete_is_not_offered_while_the_details_are_missing() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(TransactionDetailsInteractorPartialState.Failure("boom"))
        advanceUntilIdle()
        val effects = backgroundScope.collectEffects(viewModel)

        viewModel.setEvent(Event.DeletePressed)
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun confirming_deletes_this_transaction_and_leaves_the_screen() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(group("g1")))
        advanceUntilIdle()
        val effects = backgroundScope.collectEffects(viewModel)
        viewModel.setEvent(Event.DeletePressed)
        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
        advanceUntilIdle()

        viewModel.setEvent(Event.BottomSheet.Delete.PrimaryButtonPressed)
        advanceUntilIdle()

        assertEquals(listOf(TX_ID), fake.deletedIds)
        assertIs<Effect.Navigation.Pop>(effects.last())
    }

    @Test
    fun a_failed_deletion_shows_an_error_whose_retry_asks_again() = runTest(mainDispatcher) {
        val fake = FakeTransactionDetailsInteractor(
            states = listOf(success(group("g1"))),
            deleteOutcome = TransactionDetailsInteractorDeleteTransactionPartialState.Failure("locked"),
        )
        val viewModel = TransactionDetailsViewModel(fake, TX_ID)
        advanceUntilIdle()
        val effects = backgroundScope.collectEffects(viewModel)
        viewModel.setEvent(Event.DeletePressed)
        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
        viewModel.setEvent(Event.BottomSheet.Delete.PrimaryButtonPressed)
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertFalse(state.isDeleting)
        val error = assertNotNull(state.error)
        assertNotNull(error.onRetry).invoke()
        advanceUntilIdle()

        // The sheet closed for the attempt; retry re-asks rather than deleting outright.
        assertEquals(listOf(TX_ID), fake.deletedIds)
        assertEquals(
            listOf(Effect.ShowBottomSheet, Effect.CloseBottomSheet, Effect.ShowBottomSheet),
            effects,
        )
    }

    private fun TransactionDetailsViewModel.actionCounts(): PresentationActionCountsUiState =
        (viewState.value.transactionDetailsUi!!.body as TransactionDetailsBodyUi.Presentation).actionCounts

    @Test
    fun the_action_counts_arrive_once_the_details_have_loaded() = runTest(mainDispatcher) {
        val fake = FakeTransactionDetailsInteractor(listOf(success(group("g1"))))
        fake.counts = flowOf(PresentationActionCountsUiState.Content(dataDeletionRequests = 2, dpaReports = 1))
        val viewModel = TransactionDetailsViewModel(fake, TX_ID)
        advanceUntilIdle()

        assertEquals(1, fake.countSubscriptions)
        assertEquals(PresentationActionCountsUiState.Content(2, 1), viewModel.actionCounts())
    }

    @Test
    fun retrying_the_counts_subscribes_again() = runTest(mainDispatcher) {
        val fake = FakeTransactionDetailsInteractor(listOf(success(group("g1"))))
        fake.counts = flowOf(PresentationActionCountsUiState.Failure("could not load"))
        val viewModel = TransactionDetailsViewModel(fake, TX_ID)
        advanceUntilIdle()
        assertIs<PresentationActionCountsUiState.Failure>(viewModel.actionCounts())

        fake.counts = flowOf(PresentationActionCountsUiState.Content(0, 3))
        viewModel.setEvent(Event.RetryPresentationActionCounts)
        advanceUntilIdle()

        assertEquals(2, fake.countSubscriptions)
        assertEquals(PresentationActionCountsUiState.Content(0, 3), viewModel.actionCounts())
    }

    @Test
    fun each_action_opens_its_own_screen_for_this_transaction() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(group("g1")))
        advanceUntilIdle()
        val effects = backgroundScope.collectEffects(viewModel)

        viewModel.setEvent(Event.RequestDataDeletionPressed)
        viewModel.setEvent(Event.ReportSuspiciousTransactionPressed)
        advanceUntilIdle()

        assertEquals(
            listOf<Effect>(
                Effect.Navigation.SwitchScreen(DataDeletionRequestRoute(transactionId = TX_ID)),
                Effect.Navigation.SwitchScreen(DpaReportRoute(transactionId = TX_ID)),
            ),
            effects,
        )
    }

    @Test
    fun an_action_without_a_contact_goes_nowhere() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(
            TransactionDetailsInteractorPartialState.Success(
                details(group("g1"), deletionContacts = emptyList(), reportContacts = emptyList())
            )
        )
        advanceUntilIdle()
        val effects = backgroundScope.collectEffects(viewModel)

        viewModel.setEvent(Event.RequestDataDeletionPressed)
        viewModel.setEvent(Event.ReportSuspiciousTransactionPressed)
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun a_history_link_opens_only_when_there_is_history_for_that_action() = runTest(mainDispatcher) {
        val fake = FakeTransactionDetailsInteractor(listOf(success(group("g1"))))
        fake.counts = flowOf(PresentationActionCountsUiState.Content(dataDeletionRequests = 1, dpaReports = 0))
        val viewModel = TransactionDetailsViewModel(fake, TX_ID)
        advanceUntilIdle()
        val effects = backgroundScope.collectEffects(viewModel)

        viewModel.setEvent(Event.HistoryPressed(TransactionDataProtectionAction.ReportSuspiciousTransaction))
        viewModel.setEvent(Event.HistoryPressed(TransactionDataProtectionAction.RequestDataDeletion))
        advanceUntilIdle()

        assertEquals(
            listOf<Effect>(
                Effect.Navigation.SwitchScreen(
                    TransactionHistoryRoute(TX_ID, TransactionDataProtectionAction.RequestDataDeletion)
                ),
            ),
            effects,
        )
    }

    @Test
    fun a_history_link_goes_nowhere_while_the_counts_are_loading() = runTest(mainDispatcher) {
        val fake = FakeTransactionDetailsInteractor(listOf(success(group("g1"))))
        fake.counts = flowOf(PresentationActionCountsUiState.Loading)
        val viewModel = TransactionDetailsViewModel(fake, TX_ID)
        advanceUntilIdle()
        val effects = backgroundScope.collectEffects(viewModel)

        viewModel.setEvent(Event.HistoryPressed(TransactionDataProtectionAction.RequestDataDeletion))
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
    }
}
