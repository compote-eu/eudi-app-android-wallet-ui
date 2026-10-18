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

// Phase 2: the *contract* is KMP once its dates are kotlinx-datetime — the last piece of the deferred
// date migration. `TransactionsInteractorImpl` stays in :dashboard-feature: it reads wallet-core
// transaction logs, whose `creationLocalDateTime` is still java.time, and converts at that boundary.
// Package unchanged.
package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.businesslogic.validator.model.FilterableList
import eu.europa.ec.businesslogic.validator.model.Filters
import eu.europa.ec.businesslogic.validator.model.SortOrder
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.TransactionCategoryUi
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.TransactionUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
sealed class TransactionInteractorFilterPartialState {
    data class FilterApplyResult(
        val transactions: List<Pair<TransactionCategoryUi, List<TransactionUi>>>,
        val filters: List<ExpandableListItemUi.NestedListItem>,
        val sortOrder: SortOrder,
        val allDefaultFiltersAreSelected: Boolean,
    ) : TransactionInteractorFilterPartialState()

    data class FilterUpdateResult(
        val filters: List<ExpandableListItemUi.NestedListItem>,
        val sortOrder: SortOrder,
    ) : TransactionInteractorFilterPartialState()
}

sealed class TransactionInteractorGetTransactionsPartialState {
    data class Success(
        val allTransactions: FilterableList,
        val availableDates: Pair<LocalDate, LocalDate>?
    ) : TransactionInteractorGetTransactionsPartialState()

    data class Failure(val error: String) : TransactionInteractorGetTransactionsPartialState()
}

sealed class TransactionInteractorDateTimeCategoryPartialState {
    data object JustNow : TransactionInteractorDateTimeCategoryPartialState()
    data class WithinLastHour(val minutes: Long) :
        TransactionInteractorDateTimeCategoryPartialState()

    data class Today(val time: String) : TransactionInteractorDateTimeCategoryPartialState()
    data class WithinMonth(val date: String) : TransactionInteractorDateTimeCategoryPartialState()
}

interface TransactionsInteractor {

    fun getTransactions(): Flow<TransactionInteractorGetTransactionsPartialState>

    fun getTransactionCategory(dateTime: LocalDateTime): TransactionCategoryUi

    fun initializeFilters(
        filterableList: FilterableList,
    )

    fun applySearch(query: String)
    fun applyFilters()
    fun updateFilter(filterGroupId: String, filterId: String)
    fun updateDateFilterById(
        filterGroupId: String,
        filterId: String,
        lowerLimitDate: LocalDateTime,
        upperLimitDate: LocalDateTime
    )

    fun addDynamicFilters(transactions: FilterableList, filters: Filters): Filters
    fun getFilters(): Filters
    fun resetFilters()
    fun onFilterStateChange(): Flow<TransactionInteractorFilterPartialState>
    fun updateSort(filterId: String)
    fun revertFilters()
    fun updateLists(filterableList: FilterableList)
}
