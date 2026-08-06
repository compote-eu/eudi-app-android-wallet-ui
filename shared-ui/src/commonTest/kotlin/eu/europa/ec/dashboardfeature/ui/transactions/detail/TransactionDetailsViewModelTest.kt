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
// runs on both targets.
//
// It loads from its `init` (not from an Init event), which is the process-death fix described in
// HomeViewModel — but the `Event.Init` branch stays reachable because the error card's `onRetry`
// re-sends it. Both paths are asserted, since it would be easy to "simplify" the branch away and
// silently break retry.
package eu.europa.ec.dashboardfeature.ui.transactions.detail

import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorReportSuspiciousTransactionPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorRequestDataDeletionPartialState
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsCardUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsDataSharedHolderUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsUi
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailsViewModelTest {

    private class FakeTransactionDetailsInteractor(
        private val states: List<TransactionDetailsInteractorPartialState>,
    ) : TransactionDetailsInteractor {
        var detailsCalls: Int = 0
            private set
        var deletionRequestedFor: String? = null
            private set
        var reportedFor: String? = null
            private set

        override fun getTransactionDetails(
            transactionId: String,
        ): Flow<TransactionDetailsInteractorPartialState> = flow {
            detailsCalls++
            states.forEach { emit(it) }
        }

        override fun requestDataDeletion(
            transactionId: String,
        ): Flow<TransactionDetailsInteractorRequestDataDeletionPartialState> = flow {
            deletionRequestedFor = transactionId
            emit(TransactionDetailsInteractorRequestDataDeletionPartialState.Success)
        }

        override fun reportSuspiciousTransaction(
            transactionId: String,
        ): Flow<TransactionDetailsInteractorReportSuspiciousTransactionPartialState> = flow {
            reportedFor = transactionId
            emit(TransactionDetailsInteractorReportSuspiciousTransactionPartialState.Success)
        }
    }

    private companion object {
        const val TX_ID = "tx-123"

        fun sharedItem(itemId: String) = ExpandableListItemUi.NestedListItem(
            header = ListItemDataUi(
                itemId = itemId,
                mainContentData = ListItemMainContentDataUi.Text("group-$itemId"),
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

        fun details(vararg sharedItems: ExpandableListItemUi.NestedListItem) = TransactionDetailsUi(
            transactionId = TX_ID,
            transactionDetailsCardUi = TransactionDetailsCardUi(
                transactionTypeLabel = "Presentation",
                transactionStatusLabel = "Completed",
                transactionIsCompleted = true,
                transactionDate = "06 Aug 2026",
                relyingPartyName = "Acme",
                relyingPartyIsVerified = true,
            ),
            transactionDetailsDataShared = TransactionDetailsDataSharedHolderUi(
                dataSharedItems = sharedItems.toList(),
            ),
            transactionDetailsDataSigned = null,
        )

        fun success(vararg sharedItems: ExpandableListItemUi.NestedListItem) =
            TransactionDetailsInteractorPartialState.Success(details(*sharedItems))
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

    @Test
    fun the_details_load_on_construction_not_on_an_event() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(sharedItem("g1")))
        advanceUntilIdle()

        // Loading from `init` is what survives process death; see HomeViewModel's note.
        assertEquals(1, fake.detailsCalls)
        val state = viewModel.viewState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(TX_ID, assertNotNull(state.transactionDetailsUi).transactionId)
    }

    @Test
    fun a_successful_load_exposes_the_card_and_shared_data() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(sharedItem("g1"), sharedItem("g2")))
        advanceUntilIdle()

        val details = assertNotNull(viewModel.viewState.value.transactionDetailsUi)
        assertEquals("Acme", details.transactionDetailsCardUi.relyingPartyName)
        assertTrue(details.transactionDetailsCardUi.transactionIsCompleted)
        assertEquals(2, details.transactionDetailsDataShared.dataSharedItems.size)
        assertNull(details.transactionDetailsDataSigned)
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
        val (fake, viewModel) = viewModel(
            TransactionDetailsInteractorPartialState.Failure("boom")
        )
        advanceUntilIdle()
        assertNotNull(viewModel.viewState.value.error)

        viewModel.setEvent(Event.DismissError)
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.error)
        assertEquals(1, fake.detailsCalls)
    }

    @Test
    fun popping_clears_the_error_and_navigates_back() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(
            TransactionDetailsInteractorPartialState.Failure("boom")
        )
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()

        assertIs<Effect.Navigation.Pop>(effect.await())
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun requesting_data_deletion_passes_this_transactions_id() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(sharedItem("g1")))
        advanceUntilIdle()

        viewModel.setEvent(Event.RequestDataDeletionPressed)
        advanceUntilIdle()

        assertEquals(TX_ID, fake.deletionRequestedFor)
    }

    @Test
    fun reporting_a_suspicious_transaction_passes_this_transactions_id() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(sharedItem("g1")))
        advanceUntilIdle()

        viewModel.setEvent(Event.ReportSuspiciousTransactionPressed)
        advanceUntilIdle()

        assertEquals(TX_ID, fake.reportedFor)
    }

    @Test
    fun expanding_a_shared_data_group_flips_its_chevron() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(sharedItem("g1")))
        advanceUntilIdle()

        viewModel.setEvent(Event.ExpandOrCollapseGroupItem(itemId = "g1"))
        advanceUntilIdle()
        assertTrue(
            viewModel.viewState.value.transactionDetailsUi!!
                .transactionDetailsDataShared.dataSharedItems.single().isExpanded
        )

        viewModel.setEvent(Event.ExpandOrCollapseGroupItem(itemId = "g1"))
        advanceUntilIdle()
        assertFalse(
            viewModel.viewState.value.transactionDetailsUi!!
                .transactionDetailsDataShared.dataSharedItems.single().isExpanded
        )
    }

    @Test
    fun expanding_before_the_details_arrive_is_a_safe_no_op() = runTest(mainDispatcher) {
        // Never emits, so `transactionDetailsUi` stays null while the user taps.
        val (_, viewModel) = viewModel()
        advanceUntilIdle()

        viewModel.setEvent(Event.ExpandOrCollapseGroupItem(itemId = "g1"))
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.transactionDetailsUi)
    }
}
