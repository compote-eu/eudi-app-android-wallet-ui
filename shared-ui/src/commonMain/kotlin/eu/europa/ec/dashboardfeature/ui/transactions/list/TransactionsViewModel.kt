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

package eu.europa.ec.dashboardfeature.ui.transactions.list

import androidx.lifecycle.viewModelScope
import eu.europa.ec.businesslogic.util.OPEN_END_DATE
import eu.europa.ec.businesslogic.util.OPEN_START_DATE
import eu.europa.ec.businesslogic.util.toLocalDateTime
import eu.europa.ec.businesslogic.util.utcMillisToLocalDate
import eu.europa.ec.dashboardfeature.interactor.TransactionInteractorFilterPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionInteractorGetTransactionsPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionsInteractor
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.FilterDateRangeSelectionUi
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.TransactionCategoryUi
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.TransactionFilterIds
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.TransactionUi
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.TransactionDetailsRoute
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.uilogic.component.DatePickerDialogConfig
import eu.europa.ec.uilogic.component.DatePickerDialogType
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.extension.collapsedExpansionState
import eu.europa.ec.uilogic.extension.toggleExpansionState
import eu.europa.ec.uilogic.extension.withExpansionStateFrom
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import kotlinx.datetime.LocalDate

data class State(
    val isLoading: Boolean,
    val error: ContentErrorConfig? = null,

    val searchText: String = "",
    val isFilteringActive: Boolean,
    val showNoResultsFound: Boolean = false,
    val isBottomSheetOpen: Boolean = false,
    val sheetContent: TransactionsBottomSheetContent = TransactionsBottomSheetContent.Filters(
        filters = emptyList()
    ),

    val transactionsUi: List<Pair<TransactionCategoryUi, List<TransactionUi>>> = emptyList(),
    val filtersUi: List<ExpandableListItemUi.NestedListItem> = emptyList(),
    val shouldRevertFilterChanges: Boolean = true,
    val hasDateFilterChanges: Boolean = false,
    val isDatePickerDialogVisible: Boolean = false,
    val datePickerDialogConfig: DatePickerDialogConfig = DatePickerDialogConfig(
        type = DatePickerDialogType.SelectStartDate
    ),
    // persisted selected date range applied for filtering
    val filterDateRangeSelectionUi: FilterDateRangeSelectionUi = FilterDateRangeSelectionUi(),
    // temporary date range data while filter bottom sheet is open to collect date picker dialog selections
    val snapshotFilterDateRangeSelectionUi: FilterDateRangeSelectionUi = FilterDateRangeSelectionUi(),
    // Bounds from the current transaction list; selected dates remain independently stored.
    val datePickerLimits: FilterDateRangeSelectionUi = FilterDateRangeSelectionUi(),
) : ViewState

sealed class Event : ViewEvent {
    data object OnResume : Event()
    data object OnPause : Event()
    data object Pop : Event()

    data class OnSearchQueryChanged(val query: String) : Event()
    data class OnFilterSelectionChanged(val filterId: String, val groupId: String) : Event()
    data class OnFilterGroupExpansionChanged(val groupId: String) : Event()
    data object OnFiltersReset : Event()
    data object OnFiltersApply : Event()
    data object FiltersPressed : Event()

    data class TransactionItemPressed(val itemId: String) : Event()

    sealed class BottomSheet : Event() {
        data class UpdateBottomSheetState(val isOpen: Boolean) : BottomSheet()
        data object Close : BottomSheet()
    }

    sealed class DatePickerDialog : Event() {
        data class UpdateDialogState(val isVisible: Boolean) : DatePickerDialog()
    }

    data class ShowDatePicker(val datePickerType: DatePickerDialogType) : Event()
    data class OnStartDateSelected(val selectedDateUtcMillis: Long) : Event()
    data class OnEndDateSelected(val selectedDateUtcMillis: Long) : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()
        data class SwitchScreen(
            val route: AppRoute,
            val popUpTo: AppRoute = DashboardRoute,
            val inclusive: Boolean = false,
        ) : Navigation()
    }

    data object ShowBottomSheet : Effect()
    data object CloseBottomSheet : Effect()
    data object ShowDatePickerDialog : Effect()
}

sealed class TransactionsBottomSheetContent {
    data class Filters(val filters: List<ExpandableListItemUi.SingleListItem>) :
        TransactionsBottomSheetContent()
}

@KoinViewModel
class TransactionsViewModel(
    private val interactor: TransactionsInteractor,
) : MviViewModel<Event, State, Effect>() {

    init {
        // Belongs to the ViewModel's lifetime, not the composition's: this collector is the only
        // assigner of `transactionsUi`, so nothing renders until it is running. It used to be
        // started by an `Event.Init` sent from `OneTimeLaunchedEffect`, whose "already ran" flag is
        // `rememberSaveable` — so after process death the flag was restored as `true` and the event
        // was never re-sent to this brand-new ViewModel, leaving the list permanently empty.
        // `init` is the correct scope: it runs exactly once per instance, surviving configuration
        // change (where re-running would double-collect) and re-running after process death.
        collectSearchAndFilterStateChanges()
    }

    override fun setInitialState(): State {
        return State(
            isLoading = true,
            isFilteringActive = false,
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.OnResume -> {
                getTransactions(event)
            }

            is Event.OnPause -> {
                // Nothing to do. It used to flag the next load as a "first" load, which is what
                // decided whether the derived filter groups were rebuilt; they are now rebuilt on
                // every load, because a pause is not the only way the list can change.
            }

            is Event.FiltersPressed -> {
                collapseFilterGroups()
                showBottomSheet(
                    sheetContent = TransactionsBottomSheetContent.Filters(
                        filters = emptyList()
                    )
                )
            }

            is Event.OnFilterSelectionChanged -> {
                updateFilter(event.filterId, event.groupId)
            }

            is Event.OnFilterGroupExpansionChanged -> {
                updateFilterGroupExpansion(event.groupId)
            }

            is Event.OnFiltersApply -> {
                applySelectedFilters()
            }

            is Event.OnFiltersReset -> {
                resetFilters()
            }

            is Event.OnSearchQueryChanged -> {
                applySearch(event.query)
            }

            is Event.TransactionItemPressed -> {
                onTransactionItemClicked(itemId = event.itemId)
            }

            is Event.BottomSheet.UpdateBottomSheetState -> {
                revertFilters(event.isOpen)
            }

            is Event.Pop -> setEffect { Effect.Navigation.Pop }
            is Event.BottomSheet.Close -> {
                hideBottomSheet()
            }

            is Event.ShowDatePicker -> {
                showDatePickerDialog(
                    datePickerDialogConfig = viewState.value.snapshotFilterDateRangeSelectionUi
                        .toDatePickerConfig(
                            type = event.datePickerType,
                            availableDates = viewState.value.datePickerLimits,
                        )
                )
            }

            is Event.OnStartDateSelected -> {
                val datePickerSelectionData =
                    viewState.value.snapshotFilterDateRangeSelectionUi.copy(
                        startDate = utcMillisToLocalDate(
                            utcMillis = event.selectedDateUtcMillis
                        )
                    )

                setState {
                    copy(
                        snapshotFilterDateRangeSelectionUi = datePickerSelectionData
                    )
                }

                updateDateRangeFilter(
                    lowerLimit = datePickerSelectionData.startDate ?: OPEN_START_DATE,
                    upperLimit = datePickerSelectionData.endDate ?: OPEN_END_DATE,
                )
            }

            is Event.OnEndDateSelected -> {
                val datePickerSelectionData =
                    viewState.value.snapshotFilterDateRangeSelectionUi.copy(
                        endDate = utcMillisToLocalDate(
                            utcMillis = event.selectedDateUtcMillis
                        )
                    )

                setState {
                    copy(
                        snapshotFilterDateRangeSelectionUi = datePickerSelectionData
                    )
                }

                updateDateRangeFilter(
                    lowerLimit = datePickerSelectionData.startDate ?: OPEN_START_DATE,
                    upperLimit = datePickerSelectionData.endDate ?: OPEN_END_DATE,
                )
            }

            is Event.DatePickerDialog.UpdateDialogState -> {
                setState {
                    copy(isDatePickerDialogVisible = event.isVisible)
                }
            }
        }
    }

    private fun applySelectedFilters() {
        interactor.applyFilters()
        setState {
            copy(
                shouldRevertFilterChanges = false,
                filterDateRangeSelectionUi = if (hasDateFilterChanges) {
                    snapshotFilterDateRangeSelectionUi
                } else {
                    filterDateRangeSelectionUi
                },
                hasDateFilterChanges = false,
                snapshotFilterDateRangeSelectionUi = FilterDateRangeSelectionUi()
                // reset snapshot to default date range (with null long values)
            )
        }
        hideBottomSheet()
    }

    private fun updateFilter(filterId: String, groupId: String) {
        setState { copy(shouldRevertFilterChanges = true) }
        interactor.updateFilter(filterGroupId = groupId, filterId = filterId)
    }

    private fun updateFilterGroupExpansion(groupId: String) {
        setState {
            copy(
                filtersUi = filtersUi.toggleExpansionState(groupId)
                    .filterIsInstance<ExpandableListItemUi.NestedListItem>()
            )
        }
    }

    private fun collapseFilterGroups() {
        setState {
            copy(filtersUi = filtersUi.collapsedExpansionState())
        }
    }

    private fun updateDateRangeFilter(
        groupId: String = TransactionFilterIds.FILTER_BY_TRANSACTION_DATE_GROUP_ID,
        filterId: String = TransactionFilterIds.FILTER_BY_TRANSACTION_DATE_RANGE,
        lowerLimit: LocalDate,
        upperLimit: LocalDate
    ) {
        setState {
            copy(
                shouldRevertFilterChanges = true,
                hasDateFilterChanges = true
            )
        }

        interactor.updateDateFilterById(
            filterGroupId = groupId,
            filterId = filterId,
            lowerLimitDate = lowerLimit.toLocalDateTime(),
            upperLimitDate = upperLimit.toLocalDateTime(),
        )
    }

    private fun revertFilters(isOpening: Boolean) {
        if (viewState.value.sheetContent is TransactionsBottomSheetContent.Filters
            && !isOpening
            && viewState.value.shouldRevertFilterChanges
        ) {
            interactor.revertFilters()
            setState {
                copy(
                    shouldRevertFilterChanges = true,
                    hasDateFilterChanges = false,
                    snapshotFilterDateRangeSelectionUi = FilterDateRangeSelectionUi()
                )
            }
        }

        setState {
            copy(isBottomSheetOpen = isOpening)
        }
    }

    private fun resetFilters() {
        setState {
            copy(
                filterDateRangeSelectionUi = FilterDateRangeSelectionUi(),
                hasDateFilterChanges = false,
                snapshotFilterDateRangeSelectionUi = FilterDateRangeSelectionUi()
            )
        }
        interactor.resetFilters()
        hideBottomSheet()
    }

    private fun showDatePickerDialog(
        datePickerDialogConfig: DatePickerDialogConfig
    ) {
        setState {
            copy(datePickerDialogConfig = datePickerDialogConfig)
        }
        setEffect {
            Effect.ShowDatePickerDialog
        }
    }

    private fun showBottomSheet(sheetContent: TransactionsBottomSheetContent) {
        setState {
            copy(
                sheetContent = sheetContent,
                hasDateFilterChanges = false,
                snapshotFilterDateRangeSelectionUi = if (filterDateRangeSelectionUi.isEmpty) {
                    datePickerLimits
                } else {
                    filterDateRangeSelectionUi
                }
            )
        }
        setEffect {
            Effect.ShowBottomSheet
        }
    }

    private fun hideBottomSheet() {
        setEffect {
            Effect.CloseBottomSheet
        }
    }

    private fun onTransactionItemClicked(itemId: String) {
        goToTransactionDetails(transactionId = itemId)
    }

    private fun goToTransactionDetails(transactionId: String) {
        setEffect {
            Effect.Navigation.SwitchScreen(
                route = TransactionDetailsRoute(transactionId = transactionId)
            )
        }
    }

    private fun applySearch(queryText: String) {
        interactor.applySearch(queryText)
        setState {
            copy(searchText = queryText)
        }
    }

    private fun getTransactions(event: Event) {
        setState {
            copy(
                isLoading = transactionsUi.isEmpty(),
                error = null
            )
        }

        viewModelScope.launch {
            interactor.getTransactions().collect { response ->
                when (response) {
                    is TransactionInteractorGetTransactionsPartialState.Success -> {

                        // Rebuilt on EVERY load, not only on the first one after a pause.
                        //
                        // The relying-party group is *derived from the transactions themselves*, so a
                        // group built against an older list carries no item for a relying party seen
                        // since — and a multiple-selection group answers "no" for every item it has no
                        // selected filter for. The transaction is then hidden completely, "Reset all"
                        // cannot recover it (it restores the group as it was built) and only a restart
                        // brings it back. Presentations reach History without a pause, since the QR
                        // scanner and the deep-link handler both stay inside the app, so the cheap
                        // `updateLists` path was the normal one.
                        //
                        // `initializeFilters` merges: the user's own selections survive the rebuild.
                        // `FilterValidatorTest` pins both halves of that.
                        interactor.initializeFilters(filterableList = response.allTransactions)

                        interactor.applyFilters()

                        setState {
                            copy(
                                isLoading = false,
                                error = null,
                                datePickerLimits = FilterDateRangeSelectionUi(
                                    startDate = response.availableDates?.first,
                                    endDate = response.availableDates?.second,
                                ),
                            )
                        }
                    }

                    is TransactionInteractorGetTransactionsPartialState.Failure -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = ContentErrorConfig(
                                    onRetry = { setEvent(event) },
                                    errorSubTitle = response.error.asUiText(),
                                    onCancel = {
                                        setState { copy(error = null) }
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun collectSearchAndFilterStateChanges() {
        viewModelScope.launch {
            interactor.onFilterStateChange().collect { result ->
                when (result) {
                    is TransactionInteractorFilterPartialState.FilterApplyResult -> {
                        val isDefaultFilterDateRangeSelected = with(viewState.value) {
                            filterDateRangeSelectionUi.isEmpty ||
                                    filterDateRangeSelectionUi == datePickerLimits
                        }
                        setState {
                            copy(
                                isFilteringActive = !result.allDefaultFiltersAreSelected || !isDefaultFilterDateRangeSelected,
                                transactionsUi = result.transactions,
                                showNoResultsFound = result.transactions.isEmpty(),
                                filtersUi = result.filters.withExpansionStateFrom(
                                    currentItems = filtersUi
                                )
                            )
                        }
                    }

                    is TransactionInteractorFilterPartialState.FilterUpdateResult -> {
                        setState {
                            copy(
                                filtersUi = result.filters.withExpansionStateFrom(
                                    currentItems = filtersUi
                                )
                            )
                        }
                    }
                }
            }
        }
    }

}