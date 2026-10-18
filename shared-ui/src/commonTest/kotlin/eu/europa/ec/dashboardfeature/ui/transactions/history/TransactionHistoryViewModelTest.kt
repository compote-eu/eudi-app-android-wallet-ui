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

// The history screen's view model: it reads the action from the route by name, follows the history
// interactor's states, and leaves for the dashboard when the presentation is gone.
package eu.europa.ec.dashboardfeature.ui.transactions.history

import eu.europa.ec.dashboardfeature.interactor.TransactionHistoryInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionHistoryInteractorPartialState
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDataProtectionAction
import eu.europa.ec.dashboardfeature.ui.transactions.history.model.TransactionHistoryUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionHistoryViewModelTest {

    private class FakeHistoryInteractor(
        var states: List<TransactionHistoryInteractorPartialState>,
    ) : TransactionHistoryInteractor {
        val observed = mutableListOf<Pair<String, TransactionDataProtectionAction>>()

        override fun observeHistory(
            presentationId: String,
            action: TransactionDataProtectionAction,
        ): Flow<TransactionHistoryInteractorPartialState> = flow {
            observed += presentationId to action
            states.forEach { emit(it) }
        }
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val history = TransactionHistoryUi(
        title = "Previous transaction reports for Acme",
        disclaimer = "Report disclaimer",
        authority = "Hellenic Data Protection Authority",
        introduction = "Report introduction",
        items = emptyList(),
    )

    @Test
    fun the_route_action_name_selects_the_history_to_observe() = runTest(mainDispatcher) {
        val fake = FakeHistoryInteractor(
            listOf(
                TransactionHistoryInteractorPartialState.Loading,
                TransactionHistoryInteractorPartialState.Success(history),
            )
        )
        val viewModel = TransactionHistoryViewModel(
            fake, "presentation", TransactionDataProtectionAction.ReportSuspiciousTransaction.name,
        )
        advanceUntilIdle()

        assertEquals(listOf("presentation" to TransactionDataProtectionAction.ReportSuspiciousTransaction), fake.observed)
        assertEquals(history, viewModel.viewState.value.history)
        assertEquals(false, viewModel.viewState.value.isLoading)
    }

    @Test
    fun an_unknown_action_leaves_without_observing() = runTest(mainDispatcher) {
        val fake = FakeHistoryInteractor(emptyList())
        val viewModel = TransactionHistoryViewModel(fake, "presentation", "NotAnAction")
        val effects = mutableListOf<Effect>()
        backgroundScope.launch { viewModel.effect.toList(effects) }
        advanceUntilIdle()

        assertTrue(fake.observed.isEmpty())
        assertEquals(listOf<Effect>(Effect.Pop), effects)
    }

    @Test
    fun a_presentation_that_is_gone_returns_to_the_dashboard() = runTest(mainDispatcher) {
        val fake = FakeHistoryInteractor(listOf(TransactionHistoryInteractorPartialState.ParentNotFound))
        val viewModel = TransactionHistoryViewModel(
            fake, "presentation", TransactionDataProtectionAction.RequestDataDeletion.name,
        )
        val effects = mutableListOf<Effect>()
        backgroundScope.launch { viewModel.effect.toList(effects) }
        advanceUntilIdle()

        assertEquals(listOf<Effect>(Effect.PopToDashboard), effects)
        assertNull(viewModel.viewState.value.history)
    }

    @Test
    fun a_failure_offers_a_retry_that_observes_again() = runTest(mainDispatcher) {
        val fake = FakeHistoryInteractor(listOf(TransactionHistoryInteractorPartialState.Failure("no history")))
        val viewModel = TransactionHistoryViewModel(
            fake, "presentation", TransactionDataProtectionAction.RequestDataDeletion.name,
        )
        advanceUntilIdle()
        val error = assertNotNull(viewModel.viewState.value.error)

        fake.states = listOf(TransactionHistoryInteractorPartialState.Success(history))
        assertNotNull(error.onRetry).invoke()
        advanceUntilIdle()

        assertEquals(2, fake.observed.size)
        assertEquals(history, viewModel.viewState.value.history)
        assertNull(viewModel.viewState.value.error)
    }
}
