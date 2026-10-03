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

package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.businesslogic.util.FULL_DATETIME_PATTERN
import eu.europa.ec.businesslogic.util.HOURS_MINUTES_DATETIME_PATTERN
import eu.europa.ec.businesslogic.util.formatLocalDateTime
import eu.europa.ec.businesslogic.util.isJustNow
import eu.europa.ec.businesslogic.util.isToday
import eu.europa.ec.businesslogic.util.isWithinLastHour
import eu.europa.ec.businesslogic.util.isWithinThisWeek
import eu.europa.ec.businesslogic.util.minutesToNow
import eu.europa.ec.businesslogic.util.safeLet
import eu.europa.ec.businesslogic.validator.FilterValidator
import eu.europa.ec.businesslogic.validator.FilterValidatorPartialState
import eu.europa.ec.businesslogic.validator.model.FilterAction
import eu.europa.ec.businesslogic.validator.model.FilterElement
import eu.europa.ec.businesslogic.validator.model.FilterElement.FilterItem
import eu.europa.ec.businesslogic.validator.model.FilterGroup
import eu.europa.ec.businesslogic.validator.model.FilterMultipleAction
import eu.europa.ec.businesslogic.validator.model.FilterSort
import eu.europa.ec.businesslogic.validator.model.FilterableItem
import eu.europa.ec.businesslogic.validator.model.FilterableList
import eu.europa.ec.businesslogic.validator.model.Filters
import eu.europa.ec.businesslogic.validator.model.SortOrder
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.TransactionCategoryUi
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.TransactionFilterIds
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.TransactionUi
import eu.europa.ec.dashboardfeature.ui.transactions.list.model.TransactionsFilterableAttributes
import eu.europa.ec.dashboardfeature.ui.transactions.model.TransactionStatusUi
import eu.europa.ec.dashboardfeature.ui.transactions.model.toUiText
import eu.europa.ec.dashboardfeature.ui.transactions.model.TransactionTypeUi
import eu.europa.ec.dashboardfeature.ui.transactions.model.isVisibleInTransactionList
import eu.europa.ec.dashboardfeature.ui.transactions.model.toTransactionPartyName
import eu.europa.ec.dashboardfeature.ui.transactions.model.toTransactionSearchTags
import eu.europa.ec.dashboardfeature.ui.transactions.model.toTransactionStatusUi
import eu.europa.ec.dashboardfeature.ui.transactions.model.toTransactionTitle
import eu.europa.ec.dashboardfeature.ui.transactions.model.toTransactionTypeUi
import eu.europa.ec.shared.resources.StringResolver
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemSupportingContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.CheckboxDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.component.wrap.RadioButtonDataUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.StringCatalog
import kotlinx.datetime.LocalDateTime
import eu.europa.ec.shared.resources.transactions_filter_item_no_relying_party_transactions
import eu.europa.ec.shared.resources.transactions_filter_item_status_completed
import eu.europa.ec.shared.resources.transactions_filter_item_status_not_completed
import eu.europa.ec.shared.resources.transactions_screen_0_minutes_ago_message
import eu.europa.ec.shared.resources.transactions_screen_filter_by_date_period
import eu.europa.ec.shared.resources.transactions_screen_filter_by_status
import eu.europa.ec.shared.resources.transactions_screen_filters_filter_by_relying_party
import eu.europa.ec.shared.resources.transactions_screen_filters_filter_by_transaction_type
import eu.europa.ec.shared.resources.transactions_screen_filters_filter_by_transaction_type_deletion
import eu.europa.ec.shared.resources.transactions_screen_filters_filter_by_transaction_type_issuance
import eu.europa.ec.shared.resources.transactions_screen_filters_filter_by_transaction_type_presentation
import eu.europa.ec.shared.resources.transactions_screen_filters_filter_by_transaction_type_reissuance
import eu.europa.ec.shared.resources.transactions_screen_filters_filter_by_transaction_type_signing
import eu.europa.ec.shared.resources.transactions_screen_filters_sort_by
import eu.europa.ec.shared.resources.transactions_screen_filters_sort_transaction_date
import eu.europa.ec.shared.resources.transactions_screen_some_minutes_ago_message


// Phase 2: the Android implementation of the (now KMP) `TransactionsInteractor` contract, which
// moved to :shared-ui/commonMain with `TransactionsViewModel`.
class TransactionsInteractorImpl(
    private val strings: StringCatalog,
    private val stringResolver: StringResolver,
    private val filterValidator: FilterValidator,
    private val platform: TransactionsPlatformBridge,
) : TransactionsInteractor {

    private val genericErrorMsg
        get() = strings[Res.string.generic_error_message]

    override fun revertFilters() = filterValidator.revertFilters()
    override fun updateLists(filterableList: FilterableList) =
        filterValidator.updateLists(filterableList)

    override fun initializeFilters(
        filterableList: FilterableList,
    ) = filterValidator.initializeValidator(
        addDynamicFilters(filterableList, getFilters()),
        filterableList
    )

    override fun onFilterStateChange(): Flow<TransactionInteractorFilterPartialState> =
        filterValidator.onFilterStateChange().map { result ->
            val transactionsUi = when (result) {
                is FilterValidatorPartialState.FilterListResult.FilterApplyResult -> {
                    result.filteredList.items.mapNotNull { filterableItem ->
                        filterableItem.payload as? TransactionUi
                    }
                }

                is FilterValidatorPartialState.FilterListResult.FilterListEmptyResult -> {
                    emptyList()
                }

                else -> {
                    emptyList()
                }
            }.groupBy {
                it.transactionCategoryUi
            }.toList()

            val filtersUi = result.updatedFilters.filterGroups.map { filterGroup ->
                ExpandableListItemUi.NestedListItem(
                    isExpanded = false,
                    header = ListItemDataUi(
                        itemId = filterGroup.id,
                        mainContentData = ListItemMainContentDataUi.Text(filterGroup.name),
                        trailingContentData = ListItemTrailingContentDataUi.Icon(
                            iconData = AppIcons.KeyboardArrowDown
                        )
                    ),
                    nestedItems = filterGroup.filters.map { filterItem ->
                        ExpandableListItemUi.SingleListItem(
                            header = ListItemDataUi(
                                itemId = filterItem.id,
                                mainContentData = ListItemMainContentDataUi.Text(filterItem.name),
                                trailingContentData = when (filterGroup) {
                                    is FilterGroup.MultipleSelectionFilterGroup<*>,
                                    is FilterGroup.ReversibleMultipleSelectionFilterGroup<*> -> {
                                        ListItemTrailingContentDataUi.Checkbox(
                                            checkboxData = CheckboxDataUi(
                                                isChecked = filterItem.selected,
                                                enabled = true
                                            )
                                        )
                                    }

                                    is FilterGroup.SingleSelectionFilterGroup,
                                    is FilterGroup.ReversibleSingleSelectionFilterGroup -> {
                                        ListItemTrailingContentDataUi.RadioButton(
                                            radioButtonData = RadioButtonDataUi(
                                                isSelected = filterItem.selected,
                                                enabled = true
                                            )
                                        )
                                    }
                                },
                            )
                        )
                    }
                )
            }

            when (result) {
                is FilterValidatorPartialState.FilterListResult -> {
                    TransactionInteractorFilterPartialState.FilterApplyResult(
                        transactions = transactionsUi,
                        filters = filtersUi,
                        sortOrder = result.updatedFilters.sortOrder,
                        allDefaultFiltersAreSelected = result.allDefaultFiltersAreSelected
                    )
                }

                is FilterValidatorPartialState.FilterUpdateResult -> {
                    TransactionInteractorFilterPartialState.FilterUpdateResult(
                        filters = filtersUi,
                        sortOrder = result.updatedFilters.sortOrder
                    )
                }
            }
        }

    override fun getTransactions(): Flow<TransactionInteractorGetTransactionsPartialState> =
        flow {
            val transactions = platform.getTransactionLogs()
                .filter { transaction -> transaction.isVisibleInTransactionList() }
            val filterableItems = transactions.map { transaction ->

                val trailingContentData = ListItemTrailingContentDataUi.TextWithIcon(
                    text = transaction.toTransactionTypeUi().toUiText(strings),
                    iconData = AppIcons.KeyboardArrowRight
                )

                val transactionName = transaction.toTransactionTitle(strings)
                val transactionStatus = transaction.result.toTransactionStatusUi()

                FilterableItem(
                    payload = TransactionUi(
                        uiData = ExpandableListItemUi.SingleListItem(
                            header = ListItemDataUi(
                                itemId = transaction.id,
                                mainContentData = ListItemMainContentDataUi.Text(text = transactionName),
                                overlineText = transactionStatus.toUiText(strings),
                                supportingContentData = ListItemSupportingContentDataUi.Text(
                                    text = transaction.time.toFormattedDisplayableDate(),
                                ),
                                trailingContentData = trailingContentData
                            )
                        ),
                        uiStatus = transactionStatus,
                        transactionCategoryUi = getTransactionCategory(dateTime = transaction.time),
                    ),
                    attributes = TransactionsFilterableAttributes(
                        searchTags = transaction.toTransactionSearchTags(),
                        transactionStatus = transactionStatus,
                        transactionType = transaction.toTransactionTypeUi(),
                        creationLocalDateTime = transaction.time,
                        partyName = transaction.toTransactionPartyName()
                    )
                )
            }

            // Already kotlinx: the shared domain records kotlinx times, so nothing to translate.
            val creationDates = filterableItems
                .mapNotNull {
                    (it.attributes as? TransactionsFilterableAttributes)
                        ?.creationLocalDateTime
                        ?.date
                }

            emit(
                TransactionInteractorGetTransactionsPartialState.Success(
                    allTransactions = FilterableList(items = filterableItems),
                    availableDates = safeLet(
                        creationDates.minOrNull(),
                        creationDates.maxOrNull()
                    ) { minDate, maxDate ->
                        minDate to maxDate
                    }
                )
            )
        }.safeAsync {
            TransactionInteractorGetTransactionsPartialState.Failure(
                error = it.message ?: genericErrorMsg
            )
        }

    override fun getTransactionCategory(dateTime: LocalDateTime): TransactionCategoryUi {
        val javaDateTime = dateTime
        val transactionCategoryUi = when {
            javaDateTime.isToday() -> TransactionCategoryUi.Today
            javaDateTime.isWithinThisWeek() -> TransactionCategoryUi.ThisWeek
            else -> TransactionCategoryUi.Month(dateTime = dateTime)
        }
        return transactionCategoryUi
    }

    override fun applySearch(query: String) = filterValidator.applySearch(query)

    override fun applyFilters() = filterValidator.applyFilters()

    override fun addDynamicFilters(transactions: FilterableList, filters: Filters): Filters {
        return filters.copy(
            filterGroups = filters.filterGroups.map { filterGroup ->
                when (filterGroup.id) {
                    TransactionFilterIds.FILTER_BY_PARTY_GROUP_ID -> {
                        if (filterGroup !is FilterGroup.MultipleSelectionFilterGroup<*>)
                            return@map filterGroup
                        filterGroup.copy(
                            filters = addPartyFilter(transactions)
                        )
                    }

                    else -> {
                        filterGroup
                    }
                }
            },
            sortOrder = filters.sortOrder
        )
    }

    override fun getFilters(): Filters = Filters(
        sortOrder = SortOrder.Descending(isDefault = true),
        filterGroups = listOf(
            // Filter by Transaction date
            FilterGroup.SingleSelectionFilterGroup(
                id = TransactionFilterIds.FILTER_BY_TRANSACTION_DATE_GROUP_ID,
                name = strings.get(Res.string.transactions_screen_filter_by_date_period),
                filters = listOf(
                    FilterElement.DateTimeRangeFilterItem(
                        id = TransactionFilterIds.FILTER_BY_TRANSACTION_DATE_RANGE,
                        name = strings.get(Res.string.transactions_screen_filter_by_date_period),
                        selected = true,
                        isDefault = true,
                        startDateTime = FilterElement.DateTimeRangeFilterItem.OPEN_START,
                        endDateTime = FilterElement.DateTimeRangeFilterItem.OPEN_END,
                        filterableAction = FilterAction.Filter<TransactionsFilterableAttributes> { attributes, filter ->
                            return@Filter isDateAttributeWithinFilterRange(
                                filter = filter,
                                attributes = attributes
                            )
                        }
                    ),
                ),
            ),

            // Filter by Status
            FilterGroup.MultipleSelectionFilterGroup(
                id = TransactionFilterIds.FILTER_BY_STATUS_GROUP_ID,
                name = strings.get(Res.string.transactions_screen_filter_by_status),
                filters = listOf(
                    FilterItem(
                        id = TransactionFilterIds.FILTER_BY_STATUS_COMPLETE,
                        name = strings.get(Res.string.transactions_filter_item_status_completed),
                        selected = true,
                        isDefault = true,
                    ),
                    FilterItem(
                        id = TransactionFilterIds.FILTER_BY_STATUS_NOT_COMPLETED,
                        name = strings.get(Res.string.transactions_filter_item_status_not_completed),
                        selected = true,
                        isDefault = true,
                    )
                ),
                filterableAction = FilterMultipleAction<TransactionsFilterableAttributes> { attributes, filter ->
                    when (filter.id) {
                        TransactionFilterIds.FILTER_BY_STATUS_COMPLETE -> {
                            attributes.transactionStatus == TransactionStatusUi.Completed
                        }

                        TransactionFilterIds.FILTER_BY_STATUS_NOT_COMPLETED -> attributes.transactionStatus == TransactionStatusUi.NotCompleted

                        else -> true
                    }
                }
            ),

            // Filter by Party
            FilterGroup.MultipleSelectionFilterGroup(
                id = TransactionFilterIds.FILTER_BY_PARTY_GROUP_ID,
                name = strings.get(Res.string.transactions_screen_filters_filter_by_relying_party),
                filters = emptyList(),
                filterableAction = FilterMultipleAction<TransactionsFilterableAttributes> { attributes, filter ->
                    if (filter.id == TransactionFilterIds.FILTER_BY_PARTY_WITHOUT_NAME) {
                        attributes.partyName == null
                    } else {
                        attributes.partyName != null && attributes.partyName == filter.name
                    }
                }
            ),

            // Filter by Transaction Type
            FilterGroup.MultipleSelectionFilterGroup(
                id = TransactionFilterIds.FILTER_BY_TRANSACTION_TYPE_GROUP_ID,
                name = strings.get(Res.string.transactions_screen_filters_filter_by_transaction_type),
                filters = listOf(
                    FilterItem(
                        id = TransactionFilterIds.FILTER_BY_TRANSACTION_TYPE_PRESENTATION,
                        name = strings.get(Res.string.transactions_screen_filters_filter_by_transaction_type_presentation),
                        selected = true,
                        isDefault = true,
                    ),
                    FilterItem(
                        id = TransactionFilterIds.FILTER_BY_TRANSACTION_TYPE_ISSUANCE,
                        name = strings.get(Res.string.transactions_screen_filters_filter_by_transaction_type_issuance),
                        selected = true,
                        isDefault = true,
                    ),
                    FilterItem(
                        id = TransactionFilterIds.FILTER_BY_TRANSACTION_TYPE_REISSUANCE,
                        name = strings.get(Res.string.transactions_screen_filters_filter_by_transaction_type_reissuance),
                        selected = true,
                        isDefault = true,
                    ),
                    FilterItem(
                        id = TransactionFilterIds.FILTER_BY_TRANSACTION_TYPE_DELETION,
                        name = strings.get(Res.string.transactions_screen_filters_filter_by_transaction_type_deletion),
                        selected = true,
                        isDefault = true,
                    ),
                    FilterItem(
                        id = TransactionFilterIds.FILTER_BY_TRANSACTION_TYPE_SIGNING,
                        name = strings.get(Res.string.transactions_screen_filters_filter_by_transaction_type_signing),
                        selected = true,
                        isDefault = true,
                    ),
                ),
                filterableAction = FilterMultipleAction<TransactionsFilterableAttributes> { attributes, filter ->
                    when (filter.id) {
                        TransactionFilterIds.FILTER_BY_TRANSACTION_TYPE_PRESENTATION -> {
                            attributes.transactionType == TransactionTypeUi.PRESENTATION
                        }

                        TransactionFilterIds.FILTER_BY_TRANSACTION_TYPE_ISSUANCE -> {
                            attributes.transactionType == TransactionTypeUi.ISSUANCE
                        }

                        TransactionFilterIds.FILTER_BY_TRANSACTION_TYPE_REISSUANCE -> {
                            attributes.transactionType == TransactionTypeUi.REISSUANCE
                        }

                        TransactionFilterIds.FILTER_BY_TRANSACTION_TYPE_DELETION -> {
                            attributes.transactionType == TransactionTypeUi.DELETION
                        }

                        TransactionFilterIds.FILTER_BY_TRANSACTION_TYPE_SIGNING -> {
                            attributes.transactionType == TransactionTypeUi.SIGNING
                        }

                        else -> false
                    }
                }
            )
        ),
        sort = FilterSort(
            id = TransactionFilterIds.FILTER_SORT_GROUP_ID,
            name = strings.get(Res.string.transactions_screen_filters_sort_by),
            filters = listOf(
                FilterItem(
                    id = TransactionFilterIds.FILTER_SORT_TRANSACTION_DATE,
                    name = strings.get(Res.string.transactions_screen_filters_sort_transaction_date),
                    selected = true,
                    isDefault = true,
                    filterableAction = FilterAction.Sort<TransactionsFilterableAttributes, LocalDateTime> { attributes ->
                        attributes.creationLocalDateTime
                    }
                ),
            )
        )
    )

    override fun resetFilters() = filterValidator.resetFilters()

    override fun updateFilter(filterGroupId: String, filterId: String) =
        filterValidator.updateFilter(filterGroupId, filterId)

    override fun updateDateFilterById(
        filterGroupId: String,
        filterId: String,
        lowerLimitDate: LocalDateTime,
        upperLimitDate: LocalDateTime
    ) {
        // Both the contract and the filter framework are on kotlinx-datetime now, so this is a
        // straight pass-through — the conversion that used to sit here is gone.
        filterValidator.updateDateFilter(
            filterGroupId,
            filterId,
            lowerLimitDate,
            upperLimitDate
        )
    }

    override fun updateSort(filterId: String) =
        filterValidator.updateSort(filterId)

    private fun LocalDateTime.toDateTimeState(): TransactionInteractorDateTimeCategoryPartialState {
        return when {
            isJustNow() -> TransactionInteractorDateTimeCategoryPartialState.JustNow

            isWithinLastHour() -> TransactionInteractorDateTimeCategoryPartialState.WithinLastHour(
                minutes = minutesToNow()
            )

            isToday() -> TransactionInteractorDateTimeCategoryPartialState.Today(
                time = formatLocalDateTime(HOURS_MINUTES_DATETIME_PATTERN)
            )

            else -> TransactionInteractorDateTimeCategoryPartialState.WithinMonth(
                date = formatLocalDateTime(FULL_DATETIME_PATTERN)
            )
        }
    }

    /**
     * `suspend` for the plural branch only: choosing a plural form needs the locale's CLDR
     * categories, which compose-resources applies during a suspend read — so plurals resolve
     * through [StringResolver] rather than the synchronous [eu.europa.ec.shared.resources.StringCatalog].
     * The sole caller already runs inside `flow { }`, so this costs nothing.
     */
    private suspend fun LocalDateTime.toFormattedDisplayableDate(): String {
        return runCatching {
            when (val dateTimeState = this.toDateTimeState()) {
                is TransactionInteractorDateTimeCategoryPartialState.JustNow -> strings.get(
                    Res.string.transactions_screen_0_minutes_ago_message
                )

                is TransactionInteractorDateTimeCategoryPartialState.WithinLastHour -> stringResolver.resolvePlural(
                    Res.plurals.transactions_screen_some_minutes_ago_message,
                    dateTimeState.minutes.toInt(),
                    dateTimeState.minutes
                )

                is TransactionInteractorDateTimeCategoryPartialState.Today -> dateTimeState.time
                is TransactionInteractorDateTimeCategoryPartialState.WithinMonth -> dateTimeState.date
            }
        }.getOrDefault(this.toString())
    }

    private fun addPartyFilter(transactions: FilterableList): List<FilterItem> {
        val partyFilters = transactions.items
            .mapNotNull { item -> (item.attributes as? TransactionsFilterableAttributes)?.partyName }
            .distinct()
            .sortedBy { partyName -> partyName.lowercase() }
            .map { partyName ->
                FilterItem(
                    // A party's name must not collide with the reserved no-party filter id.
                    id = "party:$partyName",
                    name = partyName,
                    selected = true,
                    isDefault = true,
                )
            }

        return listOf(
            FilterItem(
                id = TransactionFilterIds.FILTER_BY_PARTY_WITHOUT_NAME,
                name = strings.get(Res.string.transactions_filter_item_no_relying_party_transactions),
                selected = true,
                isDefault = true,
            )
        ) + partyFilters
    }

    private fun isDateAttributeWithinFilterRange(
        filter: FilterElement,
        attributes: TransactionsFilterableAttributes,
    ): Boolean {
        // All kotlinx now: the filter's bounds and the attribute agree on the type, so the comparison
        // no longer needs a conversion on either side.
        val creationDate = attributes.creationLocalDateTime?.date
        return if (filter is FilterElement.DateTimeRangeFilterItem && creationDate != null) {
            creationDate in filter.startDateTime.date..filter.endDateTime.date
        } else {
            true
        }
    }
}