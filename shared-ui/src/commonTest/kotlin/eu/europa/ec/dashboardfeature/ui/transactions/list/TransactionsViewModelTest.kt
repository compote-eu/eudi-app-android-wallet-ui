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

// TransactionsViewModel. Entirely in commonTest: nothing on this screen takes a platform handle, and
// the dates it works in are kotlinx-datetime now — which was the last piece of the deferred date
// migration and the reason this view-model could not move before.
//
// The date-range picker is the part worth the most attention. Its limits are derived from the loaded
// transactions, and the two ends constrain each other, so those bounds are what these tests pin.
package eu.europa.ec.dashboardfeature.ui.transactions.list

import eu.europa.ec.businesslogic.util.OPEN_END_DATE
import eu.europa.ec.businesslogic.util.OPEN_START_DATE
import eu.europa.ec.businesslogic.util.localDateToUtcMillis
import eu.europa.ec.businesslogic.validator.model.FilterableAttributes
import eu.europa.ec.businesslogic.validator.model.FilterableItem
import eu.europa.ec.businesslogic.validator.model.FilterableList
import eu.europa.ec.businesslogic.validator.model.Filters
import eu.europa.ec.businesslogic.validator.model.SortOrder
import eu.europa.ec.dashboardfeature.interactor.TransactionInteractorFilterPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionInteractorGetTransactionsPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionsInteractor
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.TransactionCategoryUi
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.TransactionUi
import eu.europa.ec.dashboardfeature.ui.transactions.model.TransactionStatusUi
import eu.europa.ec.shared.navigation.TransactionDetailsRoute
import eu.europa.ec.uilogic.component.DatePickerDialogType
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val EARLIEST = LocalDate(2026, 1, 10)
private val LATEST = LocalDate(2026, 8, 6)

internal fun transactionUi(id: String) = TransactionUi(
    uiData = ExpandableListItemUi.SingleListItem(
        header = ListItemDataUi(
            itemId = id,
            mainContentData = ListItemMainContentDataUi.Text("Transaction $id"),
        )
    ),
    uiStatus = TransactionStatusUi.Completed,
    transactionCategoryUi = TransactionCategoryUi.Today,
)

/**
 * `TransactionsFilterableAttributes` stays in :dashboard-feature — it still carries a java.time
 * `creationLocalDateTime`, read only by the interactor's own predicates — so these items carry the
 * bare [FilterableAttributes] contract instead. The view-model never looks at attributes.
 */
private object TestAttributes : FilterableAttributes {
    override val searchTags: List<String> = emptyList()
}

private fun filterableListOf(vararg ids: String) = FilterableList(
    items = ids.map { id ->
        FilterableItem(payload = transactionUi(id), attributes = TestAttributes)
    }
)

internal class FakeTransactionsInteractor(
    private val filterStates: Flow<TransactionInteractorFilterPartialState> = MutableSharedFlow(),
    private val transactionResults: List<TransactionInteractorGetTransactionsPartialState> = listOf(
        TransactionInteractorGetTransactionsPartialState.Success(
            allTransactions = filterableListOf("t-1"),
            availableDates = EARLIEST to LATEST,
        )
    ),
) : TransactionsInteractor {

    var getCalls: Int = 0
        private set
    var initializedWith: MutableList<FilterableList> = mutableListOf()
        private set
    var updatedWith: MutableList<FilterableList> = mutableListOf()
        private set
    var applyFilterCalls: Int = 0
        private set
    var resetFilterCalls: Int = 0
        private set
    var revertFilterCalls: Int = 0
        private set
    var lastSearchQuery: String? = null
        private set
    var updatedFilters: MutableList<Pair<String, String>> = mutableListOf()
        private set
    var dateFilterUpdates: MutableList<Pair<LocalDateTime, LocalDateTime>> = mutableListOf()
        private set

    override fun getTransactions(): Flow<TransactionInteractorGetTransactionsPartialState> = flow {
        val index = getCalls.coerceAtMost(transactionResults.lastIndex)
        getCalls++
        transactionResults.getOrNull(index)?.let { emit(it) }
    }

    override fun getTransactionCategory(dateTime: LocalDateTime): TransactionCategoryUi =
        TransactionCategoryUi.Today

    override fun initializeFilters(filterableList: FilterableList) {
        initializedWith.add(filterableList)
    }

    override fun applySearch(query: String) {
        lastSearchQuery = query
    }

    override fun applyFilters() {
        applyFilterCalls++
    }

    override fun updateFilter(filterGroupId: String, filterId: String) {
        updatedFilters.add(filterGroupId to filterId)
    }

    override fun updateDateFilterById(
        filterGroupId: String,
        filterId: String,
        lowerLimitDate: LocalDateTime,
        upperLimitDate: LocalDateTime,
    ) {
        dateFilterUpdates.add(lowerLimitDate to upperLimitDate)
    }

    override fun addDynamicFilters(transactions: FilterableList, filters: Filters): Filters = filters
    override fun getFilters(): Filters = Filters.emptyFilters()

    override fun resetFilters() {
        resetFilterCalls++
    }

    override fun onFilterStateChange(): Flow<TransactionInteractorFilterPartialState> = filterStates

    override fun updateSort(filterId: String) = Unit

    override fun revertFilters() {
        revertFilterCalls++
    }

    override fun updateLists(filterableList: FilterableList) {
        updatedWith.add(filterableList)
    }
}

private fun filterGroup(id: String, isExpanded: Boolean = false) =
    ExpandableListItemUi.NestedListItem(
        isExpanded = isExpanded,
        header = ListItemDataUi(
            itemId = id,
            mainContentData = ListItemMainContentDataUi.Text(id),
            trailingContentData = ListItemTrailingContentDataUi.Icon(
                iconData = if (isExpanded) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown
            ),
        ),
        nestedItems = emptyList(),
    )

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun CoroutineScope.collectEffects(
        viewModel: TransactionsViewModel,
    ): Pair<List<Effect>, Job> {
        val effects = mutableListOf<Effect>()
        val job = launch { viewModel.effect.collect { effects.add(it) } }
        return effects to job
    }

    //region loading

    @Test
    fun the_filter_collector_runs_from_init_not_from_an_event() = runTest(mainDispatcher) {
        val filterStates = MutableSharedFlow<TransactionInteractorFilterPartialState>()
        val viewModel = TransactionsViewModel(FakeTransactionsInteractor(filterStates = filterStates))
        advanceUntilIdle()

        // Same fix as the documents list: an Event-driven start left the list permanently empty after
        // process death, because the "already ran" guard was restored as true.
        filterStates.emit(
            TransactionInteractorFilterPartialState.FilterApplyResult(
                transactions = listOf(TransactionCategoryUi.Today to listOf(transactionUi("t-1"))),
                filters = listOf(filterGroup("group-1")),
                sortOrder = SortOrder.Descending(isDefault = true),
                allDefaultFiltersAreSelected = true,
            )
        )
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertEquals(1, state.transactionsUi.size)
        assertFalse(state.showNoResultsFound)
        assertFalse(state.isFilteringActive)
    }

    @Test
    fun resuming_loads_the_transactions_and_records_the_available_date_range() =
        runTest(mainDispatcher) {
            val interactor = FakeTransactionsInteractor()
            val viewModel = TransactionsViewModel(interactor)

            viewModel.setEvent(Event.OnResume)
            advanceUntilIdle()

            // The picker's outer bounds are the oldest and newest transaction, so a user cannot pick a
            // range in which nothing could possibly exist.
            val state = viewModel.viewState.value
            assertEquals(EARLIEST, state.datePickerLimits.startDate)
            assertEquals(LATEST, state.datePickerLimits.endDate)
            assertFalse(state.isLoading)
            assertNull(state.error)
        }

    @Test
    fun every_load_rebuilds_the_filters_rather_than_only_swapping_the_list() =
        runTest(mainDispatcher) {
            val interactor = FakeTransactionsInteractor()
            val viewModel = TransactionsViewModel(interactor)

            viewModel.setEvent(Event.OnResume)
            advanceUntilIdle()
            viewModel.setEvent(Event.OnResume)
            advanceUntilIdle()

            // The relying-party filter group is derived from the transactions, so a reload that only
            // swapped the list would judge new transactions with a group built for the old one — and
            // a multiple-selection group hides everything it has no selected filter for. See
            // `FilterValidatorTest`, which pins that.
            assertEquals(2, interactor.initializedWith.size)
            assertEquals(0, interactor.updatedWith.size)
        }

    @Test
    fun a_transaction_that_appears_between_loads_reaches_the_filters() = runTest(mainDispatcher) {
        // The History bug, in the shape it was seen on a device: the tab is opened while the log is
        // still empty, a presentation happens without the app ever pausing (the QR scanner and the
        // deep-link handler both stay inside it), and the tab is looked at again. Before the fix the
        // second load never rebuilt the groups, so the entry stayed invisible until a restart, and
        // "Reset all" could not recover it either.
        val interactor = FakeTransactionsInteractor(
            transactionResults = listOf(
                TransactionInteractorGetTransactionsPartialState.Success(
                    allTransactions = filterableListOf(),
                    availableDates = null,
                ),
                TransactionInteractorGetTransactionsPartialState.Success(
                    allTransactions = filterableListOf("t-1"),
                    availableDates = EARLIEST to LATEST,
                ),
            )
        )
        val viewModel = TransactionsViewModel(interactor)

        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()
        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()

        assertEquals(2, interactor.initializedWith.size)
        assertEquals(0, interactor.initializedWith.first().items.size)
        assertEquals(1, interactor.initializedWith.last().items.size)
    }

    @Test
    fun pausing_does_not_change_how_the_next_load_behaves() = runTest(mainDispatcher) {
        val interactor = FakeTransactionsInteractor()
        val viewModel = TransactionsViewModel(interactor)

        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()
        viewModel.setEvent(Event.OnPause)
        advanceUntilIdle()
        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()

        // A pause used to be what made a load rebuild the filters. It no longer decides anything —
        // every load rebuilds — and this is here so that a reintroduced special case fails.
        assertEquals(2, interactor.initializedWith.size)
        assertEquals(0, interactor.updatedWith.size)
    }

    @Test
    fun a_load_failure_offers_a_retry() = runTest(mainDispatcher) {
        val interactor = FakeTransactionsInteractor(
            transactionResults = listOf(
                TransactionInteractorGetTransactionsPartialState.Failure(error = "boom"),
                TransactionInteractorGetTransactionsPartialState.Success(
                    allTransactions = filterableListOf("t-1"),
                    availableDates = EARLIEST to LATEST,
                ),
            )
        )
        val viewModel = TransactionsViewModel(interactor)

        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        assertNotNull(error.onRetry).invoke()
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.error)
        assertEquals(2, interactor.getCalls)
    }

    @Test
    fun transactions_with_no_dates_leave_the_picker_unbounded() = runTest(mainDispatcher) {
        // An empty history has no range to derive, so the picker must not be pinned to a single day.
        val interactor = FakeTransactionsInteractor(
            transactionResults = listOf(
                TransactionInteractorGetTransactionsPartialState.Success(
                    allTransactions = FilterableList(items = emptyList()),
                    availableDates = null,
                )
            )
        )
        val viewModel = TransactionsViewModel(interactor)

        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.datePickerLimits.startDate)
        assertNull(viewModel.viewState.value.datePickerLimits.endDate)
    }

    //endregion

    //region the date-range picker

    @Test
    fun the_start_picker_is_bounded_by_the_data_and_by_the_chosen_end() = runTest(mainDispatcher) {
        val viewModel = TransactionsViewModel(FakeTransactionsInteractor())
        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()

        // With no end chosen yet, the start may run to the newest transaction.
        viewModel.setEvent(Event.ShowDatePicker(DatePickerDialogType.SelectStartDate))
        advanceUntilIdle()
        var config = viewModel.viewState.value.datePickerDialogConfig
        assertEquals(DatePickerDialogType.SelectStartDate, config.type)
        assertEquals(EARLIEST, config.lowerLimit)
        assertEquals(LATEST, config.upperLimit)

        // Once an end is chosen, the start cannot go past it.
        val chosenEnd = LocalDate(2026, 3, 1)
        viewModel.setEvent(Event.OnEndDateSelected(localDateToUtcMillis(chosenEnd)))
        advanceUntilIdle()
        viewModel.setEvent(Event.ShowDatePicker(DatePickerDialogType.SelectStartDate))
        advanceUntilIdle()
        config = viewModel.viewState.value.datePickerDialogConfig
        assertEquals(EARLIEST, config.lowerLimit)
        assertEquals(chosenEnd, config.upperLimit)
    }

    @Test
    fun the_end_picker_cannot_go_before_the_chosen_start() = runTest(mainDispatcher) {
        val viewModel = TransactionsViewModel(FakeTransactionsInteractor())
        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()

        val chosenStart = LocalDate(2026, 5, 1)
        viewModel.setEvent(Event.OnStartDateSelected(localDateToUtcMillis(chosenStart)))
        advanceUntilIdle()
        viewModel.setEvent(Event.ShowDatePicker(DatePickerDialogType.SelectEndDate))
        advanceUntilIdle()

        val config = viewModel.viewState.value.datePickerDialogConfig
        assertEquals(DatePickerDialogType.SelectEndDate, config.type)
        // The lower limit is the later of the data's start and the chosen start — an inverted range
        // is unselectable rather than merely rejected afterwards.
        assertEquals(chosenStart, config.lowerLimit)
        assertEquals(LATEST, config.upperLimit)
    }

    @Test
    fun choosing_a_start_date_narrows_the_filter_with_an_open_end() = runTest(mainDispatcher) {
        val interactor = FakeTransactionsInteractor()
        val viewModel = TransactionsViewModel(interactor)
        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()

        val chosen = LocalDate(2026, 5, 1)
        viewModel.setEvent(Event.OnStartDateSelected(localDateToUtcMillis(chosen)))
        advanceUntilIdle()

        assertEquals(chosen, viewModel.viewState.value.snapshotFilterDateRangeSelectionUi.startDate)
        val (lower, upper) = interactor.dateFilterUpdates.single()
        assertEquals(chosen, lower.date)
        // No end chosen yet, so the range stays open at the top rather than collapsing to one day.
        assertEquals(OPEN_END_DATE, upper.date)
    }

    @Test
    fun choosing_both_ends_sends_the_full_range() = runTest(mainDispatcher) {
        val interactor = FakeTransactionsInteractor()
        val viewModel = TransactionsViewModel(interactor)
        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()

        val start = LocalDate(2026, 5, 1)
        val end = LocalDate(2026, 6, 1)
        viewModel.setEvent(Event.OnStartDateSelected(localDateToUtcMillis(start)))
        advanceUntilIdle()
        viewModel.setEvent(Event.OnEndDateSelected(localDateToUtcMillis(end)))
        advanceUntilIdle()

        val (lower, upper) = interactor.dateFilterUpdates.last()
        assertEquals(start, lower.date)
        assertEquals(end, upper.date)
    }

    @Test
    fun choosing_only_an_end_leaves_the_range_open_at_the_bottom() = runTest(mainDispatcher) {
        val interactor = FakeTransactionsInteractor()
        val viewModel = TransactionsViewModel(interactor)
        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()

        val end = LocalDate(2026, 6, 1)
        viewModel.setEvent(Event.OnEndDateSelected(localDateToUtcMillis(end)))
        advanceUntilIdle()

        val (lower, upper) = interactor.dateFilterUpdates.single()
        assertEquals(OPEN_START_DATE, lower.date)
        assertEquals(end, upper.date)
    }

    @Test
    fun showing_the_date_picker_emits_an_effect_and_the_flag_follows_the_host() =
        runTest(mainDispatcher) {
            val viewModel = TransactionsViewModel(FakeTransactionsInteractor())

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.ShowDatePicker(DatePickerDialogType.SelectStartDate))
            advanceUntilIdle()

            // `ShowDatePicker` only prepares the config and asks the host to open the dialog — it does
            // NOT set `isDatePickerDialogVisible` itself. The flag is host-owned, like the bottom
            // sheet's, and comes back through UpdateDialogState.
            assertIs<Effect.ShowDatePickerDialog>(effects.single())
            assertFalse(viewModel.viewState.value.isDatePickerDialogVisible)

            viewModel.setEvent(Event.DatePickerDialog.UpdateDialogState(isVisible = true))
            advanceUntilIdle()
            assertTrue(viewModel.viewState.value.isDatePickerDialogVisible)

            viewModel.setEvent(Event.DatePickerDialog.UpdateDialogState(isVisible = false))
            advanceUntilIdle()
            job.cancel()
            assertFalse(viewModel.viewState.value.isDatePickerDialogVisible)
        }

    //endregion

    //region filters, search and navigation

    @Test
    fun changing_a_filter_delegates_and_arms_the_revert() = runTest(mainDispatcher) {
        val interactor = FakeTransactionsInteractor()
        val viewModel = TransactionsViewModel(interactor)

        viewModel.setEvent(Event.OnFilterSelectionChanged(filterId = "f-1", groupId = "g-1"))
        advanceUntilIdle()

        assertEquals(listOf("g-1" to "f-1"), interactor.updatedFilters)
        assertTrue(viewModel.viewState.value.shouldRevertFilterChanges)
    }

    @Test
    fun applying_filters_persists_the_date_range_and_closes_the_sheet() = runTest(mainDispatcher) {
        val interactor = FakeTransactionsInteractor()
        val viewModel = TransactionsViewModel(interactor)
        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()

        val start = LocalDate(2026, 5, 1)
        viewModel.setEvent(Event.OnStartDateSelected(localDateToUtcMillis(start)))
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.OnFiltersApply)
        advanceUntilIdle()
        job.cancel()

        // Two calls, not one: loading the list applies the filters as well, so Apply is the second.
        assertEquals(2, interactor.applyFilterCalls)
        // The snapshot the sheet was editing becomes the persisted selection, which is what the
        // toolbar chip then reflects — and the snapshot is cleared so reopening starts fresh.
        assertEquals(start, viewModel.viewState.value.filterDateRangeSelectionUi.startDate)
        assertNull(viewModel.viewState.value.snapshotFilterDateRangeSelectionUi.startDate)
        assertFalse(viewModel.viewState.value.shouldRevertFilterChanges)
        assertTrue(effects.any { it is Effect.CloseBottomSheet })
    }

    @Test
    fun resetting_filters_clears_the_date_range_too() = runTest(mainDispatcher) {
        val interactor = FakeTransactionsInteractor()
        val viewModel = TransactionsViewModel(interactor)
        viewModel.setEvent(Event.OnResume)
        advanceUntilIdle()
        viewModel.setEvent(Event.OnStartDateSelected(localDateToUtcMillis(LocalDate(2026, 5, 1))))
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.OnFiltersReset)
        advanceUntilIdle()
        job.cancel()

        assertEquals(1, interactor.resetFilterCalls)
        // Reset means "show everything", and everything is expressed as the data's own bounds — not as
        // an empty selection. The editing snapshot, on the other hand, IS cleared.
        assertEquals(EARLIEST, viewModel.viewState.value.filterDateRangeSelectionUi.startDate)
        assertEquals(LATEST, viewModel.viewState.value.filterDateRangeSelectionUi.endDate)
        assertNull(viewModel.viewState.value.snapshotFilterDateRangeSelectionUi.startDate)
        assertTrue(effects.any { it is Effect.CloseBottomSheet })
    }

    @Test
    fun searching_delegates_the_query_and_records_it() = runTest(mainDispatcher) {
        val interactor = FakeTransactionsInteractor()
        val viewModel = TransactionsViewModel(interactor)

        viewModel.setEvent(Event.OnSearchQueryChanged(query = "acme"))
        advanceUntilIdle()

        assertEquals("acme", interactor.lastSearchQuery)
        assertEquals("acme", viewModel.viewState.value.searchText)
    }

    @Test
    fun opening_a_transaction_navigates_to_its_details() = runTest(mainDispatcher) {
        val viewModel = TransactionsViewModel(FakeTransactionsInteractor())

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.TransactionItemPressed(itemId = "t-42"))
        advanceUntilIdle()
        job.cancel()

        val switch = assertIs<Effect.Navigation.SwitchScreen>(effects.single())
        assertEquals(TransactionDetailsRoute(transactionId = "t-42"), switch.route)
    }

    @Test
    fun popping_navigates_back() = runTest(mainDispatcher) {
        val viewModel = TransactionsViewModel(FakeTransactionsInteractor())

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.Navigation.Pop>(effects.single())
    }

    //endregion
}
