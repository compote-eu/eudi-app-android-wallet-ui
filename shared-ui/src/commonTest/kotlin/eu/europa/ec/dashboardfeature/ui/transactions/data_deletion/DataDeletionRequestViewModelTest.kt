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

// The data-deletion screen's view model: upstream (23b98be0) has no tests for it. What it must get right is
// the order — open the contact first, record the attempt only once it opened — and that a failed save is
// retried with the same attempt rather than by opening the contact again.
package eu.europa.ec.dashboardfeature.ui.transactions.data_deletion

import eu.europa.ec.corelogic.controller.RecordTransactionPartialState
import eu.europa.ec.corelogic.model.CommunicationMethodDomain
import eu.europa.ec.corelogic.model.InteractingPartyDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorDataDeletionPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorDataProtectionPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorDeleteTransactionPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorDpaReportPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorPartialState
import eu.europa.ec.dashboardfeature.ui.transactions.data_deletion.model.DataDeletionRequestUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.PendingTransactionActionUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.PresentationActionCountsUiState
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDataProtectionAction
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.transaction_details_action_open_failed
import eu.europa.ec.shared.resources.transaction_details_action_save_failed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DataDeletionRequestViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val request = DataDeletionRequestUi(
        description = "This will open your email application.",
        responsibility = "<b>The wallet doesn't send this email.</b>",
        retentionNotice = "Acme may keep some data.",
        buttonText = "Open email to Acme",
        contactUrl = "mailto:privacy@acme.example",
    )

    private fun loaded() = FakeActionInteractor(
        deletion = TransactionDetailsInteractorDataDeletionPartialState.Success(request),
    )

    @Test
    fun the_request_loads_on_construction() = runTest(mainDispatcher) {
        val fake = loaded()
        val viewModel = DataDeletionRequestViewModel(fake, "presentation")
        advanceUntilIdle()

        assertEquals(request, viewModel.viewState.value.request)
        assertFalse(viewModel.viewState.value.isLoading)
        assertEquals(1, fake.loads)
    }

    @Test
    fun continuing_asks_the_screen_to_open_the_contact_and_records_nothing_yet() = runTest(mainDispatcher) {
        val fake = loaded()
        val viewModel = DataDeletionRequestViewModel(fake, "presentation")
        advanceUntilIdle()
        val effects = mutableListOf<Effect>()
        backgroundScope.launch { viewModel.effect.toList(effects) }

        viewModel.setEvent(Event.Continue)
        advanceUntilIdle()

        assertEquals(listOf(TransactionDataProtectionAction.RequestDataDeletion to request.contactUrl), fake.prepared)
        assertEquals(listOf<Effect>(Effect.OpenChannel("attempt-1", "${request.contactUrl}?launch")), effects)
        assertTrue(fake.recorded.isEmpty())
        assertTrue(viewModel.viewState.value.isPerformingAction)
    }

    @Test
    fun an_opened_contact_is_recorded_with_when_it_opened_then_the_screen_closes() = runTest(mainDispatcher) {
        val fake = loaded()
        val viewModel = DataDeletionRequestViewModel(fake, "presentation")
        advanceUntilIdle()
        val effects = mutableListOf<Effect>()
        backgroundScope.launch { viewModel.effect.toList(effects) }
        viewModel.setEvent(Event.Continue)
        advanceUntilIdle()

        viewModel.setEvent(Event.ChannelOpened(actionId = "attempt-1", opened = true))
        advanceUntilIdle()

        val saved = fake.recorded.single()
        assertEquals("attempt-1", saved.id)
        assertNotNull(saved.launchedAt)
        assertEquals(Effect.Pop, effects.last())
    }

    @Test
    fun a_contact_that_did_not_open_records_nothing_and_retry_prepares_a_new_attempt() = runTest(mainDispatcher) {
        val fake = loaded()
        val viewModel = DataDeletionRequestViewModel(fake, "presentation")
        advanceUntilIdle()
        viewModel.setEvent(Event.Continue)
        advanceUntilIdle()

        viewModel.setEvent(Event.ChannelOpened(actionId = "attempt-1", opened = false))
        advanceUntilIdle()

        assertTrue(fake.recorded.isEmpty())
        val error = assertNotNull(viewModel.viewState.value.error)
        assertEquals(UiText.Resource(Res.string.transaction_details_action_open_failed), error.errorSubTitle)
        assertFalse(viewModel.viewState.value.isPerformingAction)

        assertNotNull(error.onRetry).invoke()
        advanceUntilIdle()

        assertEquals(2, fake.prepared.size)
        assertEquals("attempt-2", viewModel.viewState.value.pendingAction?.id)
    }

    @Test
    fun a_failed_save_is_retried_with_the_same_attempt_without_opening_the_contact_again() = runTest(mainDispatcher) {
        val fake = loaded().apply {
            saves = listOf(RecordTransactionPartialState.Failure("disk full"), RecordTransactionPartialState.Success)
        }
        val viewModel = DataDeletionRequestViewModel(fake, "presentation")
        advanceUntilIdle()
        val effects = mutableListOf<Effect>()
        backgroundScope.launch { viewModel.effect.toList(effects) }
        viewModel.setEvent(Event.Continue)
        advanceUntilIdle()
        viewModel.setEvent(Event.ChannelOpened(actionId = "attempt-1", opened = true))
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        assertEquals(UiText.Resource(Res.string.transaction_details_action_save_failed), error.errorSubTitle)

        assertNotNull(error.onRetry).invoke()
        advanceUntilIdle()

        assertEquals(2, fake.recorded.size)
        assertEquals(fake.recorded[0], fake.recorded[1])
        assertEquals(1, fake.prepared.size)
        assertEquals(1, effects.count { it is Effect.OpenChannel })
        assertEquals(Effect.Pop, effects.last())
    }

    @Test
    fun a_result_for_another_attempt_is_ignored() = runTest(mainDispatcher) {
        val fake = loaded()
        val viewModel = DataDeletionRequestViewModel(fake, "presentation")
        advanceUntilIdle()
        viewModel.setEvent(Event.Continue)
        advanceUntilIdle()

        viewModel.setEvent(Event.ChannelOpened(actionId = "attempt-0", opened = true))
        advanceUntilIdle()

        assertTrue(fake.recorded.isEmpty())
        assertTrue(viewModel.viewState.value.isPerformingAction)
    }

    @Test
    fun back_is_ignored_while_an_attempt_is_in_progress() = runTest(mainDispatcher) {
        val fake = loaded()
        val viewModel = DataDeletionRequestViewModel(fake, "presentation")
        advanceUntilIdle()
        val effects = mutableListOf<Effect>()
        backgroundScope.launch { viewModel.effect.toList(effects) }
        viewModel.setEvent(Event.Continue)
        advanceUntilIdle()

        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()

        assertTrue(effects.none { it == Effect.Pop })
    }

    @Test
    fun a_request_that_cannot_load_offers_a_retry_that_loads_again() = runTest(mainDispatcher) {
        val fake = FakeActionInteractor(
            deletion = TransactionDetailsInteractorDataDeletionPartialState.Failure("unavailable"),
        )
        val viewModel = DataDeletionRequestViewModel(fake, "presentation")
        advanceUntilIdle()
        val error = assertNotNull(viewModel.viewState.value.error)
        assertNull(viewModel.viewState.value.request)

        fake.deletion = TransactionDetailsInteractorDataDeletionPartialState.Success(request)
        assertNotNull(error.onRetry).invoke()
        advanceUntilIdle()

        assertEquals(2, fake.loads)
        assertEquals(request, viewModel.viewState.value.request)
    }
}

/**
 * The action screens' side of the details interactor. Each prepared attempt gets a fresh id, as
 * upstream's `UuidProvider` gives it; every call is recorded so a test can see what was asked.
 */
internal class FakeActionInteractor(
    var deletion: TransactionDetailsInteractorDataDeletionPartialState =
        TransactionDetailsInteractorDataDeletionPartialState.Failure("unset"),
    var report: TransactionDetailsInteractorDpaReportPartialState =
        TransactionDetailsInteractorDpaReportPartialState.Failure("unset"),
    /** Answers to successive saves; the last one repeats. */
    var saves: List<RecordTransactionPartialState> = listOf(RecordTransactionPartialState.Success),
    var prepareFailure: String? = null,
) : TransactionDetailsInteractor {
    val prepared = mutableListOf<Pair<TransactionDataProtectionAction, String>>()
    val recorded = mutableListOf<PendingTransactionActionUi>()
    var loads: Int = 0
        private set

    override fun getTransactionDetails(transactionId: String): Flow<TransactionDetailsInteractorPartialState> =
        flowOf(TransactionDetailsInteractorPartialState.Failure("not used here"))

    override fun getDataDeletionRequest(transactionId: String) = flow { loads++; emit(deletion) }

    override fun getDpaReport(transactionId: String) = flow { loads++; emit(report) }

    override fun observePresentationActionCounts(presentationId: String): Flow<PresentationActionCountsUiState> =
        flowOf(PresentationActionCountsUiState.Loading)

    override fun deleteTransaction(transactionId: String): Flow<TransactionDetailsInteractorDeleteTransactionPartialState> =
        flowOf(TransactionDetailsInteractorDeleteTransactionPartialState.Success)

    override fun prepareDataProtectionAction(
        transactionId: String,
        action: TransactionDataProtectionAction,
        contactUrl: String,
    ): Flow<TransactionDetailsInteractorDataProtectionPartialState> = flow {
        prepared += action to contactUrl
        val failure = prepareFailure
        if (failure != null) {
            emit(TransactionDetailsInteractorDataProtectionPartialState.Failure(failure))
        } else {
            emit(
                TransactionDetailsInteractorDataProtectionPartialState.Success(
                    PendingTransactionActionUi(
                        id = "attempt-${prepared.size}",
                        presentation = PRESENTATION,
                        action = action,
                        contactUrl = contactUrl,
                        launchUrl = "$contactUrl?launch",
                        communicationMethod = CommunicationMethodDomain.Email,
                        authority = null,
                        launchedAt = null,
                    )
                )
            )
        }
    }

    override fun recordDataProtectionAction(
        pendingAction: PendingTransactionActionUi,
    ): Flow<RecordTransactionPartialState> = flow {
        recorded += pendingAction
        emit(saves[minOf(recorded.size, saves.size) - 1])
    }

    companion object {
        val PRESENTATION = TransactionLogDomain.Presentation(
            id = "presentation",
            time = LocalDateTime(2026, 9, 17, 10, 30),
            result = TransactionResultDomain.Completed,
            party = InteractingPartyDomain(name = null, identifier = null, contacts = emptyList()),
            partyType = null,
            intermediary = null,
            registration = null,
            claimsRequested = emptyList(),
            claimsPresented = emptyList(),
            transactionData = emptyList(),
        )
    }
}
