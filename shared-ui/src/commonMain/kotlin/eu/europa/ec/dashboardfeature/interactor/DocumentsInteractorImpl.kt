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

import eu.europa.ec.businesslogic.extension.isBeyondNextDays
import eu.europa.ec.businesslogic.extension.isExpired
import eu.europa.ec.businesslogic.extension.isValid
import eu.europa.ec.businesslogic.extension.isWithinNextDays
import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.businesslogic.util.formatInstant
import eu.europa.ec.businesslogic.validator.FilterValidator
import eu.europa.ec.businesslogic.validator.FilterValidatorPartialState
import eu.europa.ec.businesslogic.validator.model.FilterAction
import eu.europa.ec.businesslogic.validator.model.FilterElement.FilterItem
import eu.europa.ec.businesslogic.validator.model.FilterGroup
import eu.europa.ec.businesslogic.validator.model.FilterMultipleAction
import eu.europa.ec.businesslogic.validator.model.FilterSort
import eu.europa.ec.businesslogic.validator.model.FilterableItem
import eu.europa.ec.businesslogic.validator.model.FilterableList
import eu.europa.ec.businesslogic.validator.model.Filters
import eu.europa.ec.businesslogic.validator.model.SortOrder
import eu.europa.ec.corelogic.model.DeferredDocumentDataDomain
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.corelogic.model.toDocumentCategory
import eu.europa.ec.corelogic.model.toDocumentIdentifier
import eu.europa.ec.dashboardfeature.ui.documents.detail.model.DocumentIssuanceStateUi
import eu.europa.ec.dashboardfeature.ui.documents.list.model.DocumentFilterIds
import eu.europa.ec.dashboardfeature.ui.documents.list.model.DocumentUi
import eu.europa.ec.dashboardfeature.ui.documents.list.model.DocumentsFilterableAttributes
import eu.europa.ec.dashboardfeature.ui.documents.model.DocumentCredentialsInfoUi
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.content_description_issuer_logo_icon
import eu.europa.ec.shared.resources.dashboard_document_credentials_info_text
import eu.europa.ec.shared.resources.dashboard_document_deferred_failed
import eu.europa.ec.shared.resources.dashboard_document_deferred_pending
import eu.europa.ec.shared.resources.dashboard_document_has_expired
import eu.europa.ec.shared.resources.dashboard_document_has_not_expired
import eu.europa.ec.shared.resources.dashboard_document_revoked
import eu.europa.ec.shared.resources.documents_screen_filters_filter_by_category
import eu.europa.ec.shared.resources.documents_screen_filters_filter_by_expiry_period
import eu.europa.ec.shared.resources.documents_screen_filters_filter_by_expiry_period_1
import eu.europa.ec.shared.resources.documents_screen_filters_filter_by_expiry_period_2
import eu.europa.ec.shared.resources.documents_screen_filters_filter_by_expiry_period_3
import eu.europa.ec.shared.resources.documents_screen_filters_filter_by_expiry_period_4
import eu.europa.ec.shared.resources.documents_screen_filters_filter_by_issuer
import eu.europa.ec.shared.resources.documents_screen_filters_filter_by_state
import eu.europa.ec.shared.resources.documents_screen_filters_filter_by_state_expired
import eu.europa.ec.shared.resources.documents_screen_filters_filter_by_state_revoked
import eu.europa.ec.shared.resources.documents_screen_filters_filter_by_state_valid
import eu.europa.ec.shared.resources.documents_screen_filters_sort_by
import eu.europa.ec.shared.resources.documents_screen_filters_sort_default
import eu.europa.ec.shared.resources.documents_screen_filters_unknown_issuer
import eu.europa.ec.shared.wallet.WalletDocumentIssuanceState
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemLeadingContentDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.CheckboxDataUi
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.component.wrap.RadioButtonDataUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// Phase 2: the Android implementation of the (now KMP) `DocumentsInteractor` contract, which moved
// to :shared-ui/commonMain with `DocumentsViewModel`. This side stays Android-only: it resolves
// strings through ResourceProvider and drives wallet-core's issuance/deletion controller. The
// document *list* it builds now reads the WalletEngine seam, so it holds no wallet-core document
// types at all.
class DocumentsInteractorImpl(
    private val strings: StringCatalog,
    private val walletEngine: WalletEngine,
    private val filterValidator: FilterValidator,
    private val platform: DocumentsPlatformBridge,
) : DocumentsInteractor {

    private val genericErrorMsg
        get() = strings[Res.string.generic_error_message]

    override val deferredFailedSupportingText: String
        get() = strings.get(Res.string.dashboard_document_deferred_failed)

    override fun onFilterStateChange(): Flow<DocumentInteractorFilterPartialState> =
        filterValidator.onFilterStateChange().map { result ->
            val documentsUi = when (result) {
                is FilterValidatorPartialState.FilterListResult.FilterApplyResult -> {
                    result.filteredList.items.mapNotNull { filterableItem ->
                        filterableItem.payload as? DocumentUi
                    }
                }

                is FilterValidatorPartialState.FilterListResult.FilterListEmptyResult -> {
                    emptyList()
                }

                else -> {
                    emptyList()
                }
            }.groupBy {
                it.documentCategory
            }.toList().sortedBy { it.first.order }

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
                    DocumentInteractorFilterPartialState.FilterApplyResult(
                        documents = documentsUi,
                        filters = filtersUi,
                        allDefaultFiltersAreSelected = result.allDefaultFiltersAreSelected
                    )
                }

                is FilterValidatorPartialState.FilterUpdateResult -> {
                    DocumentInteractorFilterPartialState.FilterUpdateResult(
                        filters = filtersUi,
                    )
                }
            }
        }

    override fun initializeFilters(
        filterableList: FilterableList,
    ) = filterValidator.initializeValidator(
        addDynamicFilters(filterableList, getFilters()),
        filterableList
    )

    override fun updateLists(filterableList: FilterableList) =
        filterValidator.updateLists(filterableList)

    override fun applySearch(query: String) = filterValidator.applySearch(query)

    override fun revertFilters() = filterValidator.revertFilters()

    override fun updateFilter(filterGroupId: String, filterId: String) =
        filterValidator.updateFilter(filterGroupId, filterId)

    override fun updateSort(filterId: String) =
        filterValidator.updateSort(filterId)

    override fun applyFilters() = filterValidator.applyFilters()

    override fun resetFilters() = filterValidator.resetFilters()

    override fun getDocuments(): Flow<DocumentInteractorGetDocumentsPartialState> =
        flow<DocumentInteractorGetDocumentsPartialState> {
            val shouldAllowUserInteraction =
                walletEngine.getMainPidDocument() != null

            val documentCategories = platform.documentCategories

            val userLocaleTag = platform.localeTag()

            val showBatchIssuanceCounter = platform.showBatchIssuanceCounter()

            val allDocuments = FilterableList(
                items = walletEngine
                    .getAllDocumentsWithDetails(locale = userLocaleTag)
                    .map { document ->
                        val issuerName = document.issuerName
                            ?: strings.get(Res.string.documents_screen_filters_unknown_issuer)

                        val documentIdentifier = document.formatType.toDocumentIdentifier()

                        val documentCategory = documentIdentifier.toDocumentCategory(
                            allCategories = documentCategories
                        )

                        val documentSearchTags = buildList {
                            add(document.name)
                            if (issuerName.isNotBlank()) {
                                add(issuerName)
                            }
                        }

                        val isPending =
                            document.issuanceState == WalletDocumentIssuanceState.Pending

                        val documentIssuanceState = when {
                            isPending -> DocumentIssuanceStateUi.Pending
                            document.isRevoked -> DocumentIssuanceStateUi.Revoked
                            document.isExpired -> DocumentIssuanceStateUi.Expired
                            else -> DocumentIssuanceStateUi.Issued
                        }

                        // Bound to a local because WalletDocument lives in another module, where a
                        // null-check on its `val` does not smart-cast.
                        val expiresAt = document.expiresAt
                        val supportingText = when {
                            isPending -> strings.get(Res.string.dashboard_document_deferred_pending)
                            document.isRevoked -> strings.get(Res.string.dashboard_document_revoked)
                            document.isExpired -> strings.get(Res.string.dashboard_document_has_expired)
                            expiresAt == null -> null
                            else -> strings.get(
                                Res.string.dashboard_document_has_not_expired,
                                expiresAt.formatInstant()
                            )
                        }

                        val trailingContentData = when {
                            isPending -> ListItemTrailingContentDataUi.Icon(
                                iconData = AppIcons.ClockTimer,
                                tint = ColorKey.Warning,
                            )

                            document.isRevoked -> ListItemTrailingContentDataUi.Icon(
                                iconData = AppIcons.ErrorFilled,
                                tint = ColorKey.Error
                            )

                            else -> {
                                val documentCredentialsInfoUi = DocumentCredentialsInfoUi(
                                    availableCredentials = document.credentialsCount,
                                    totalCredentials = document.initialCredentialsCount,
                                    title = strings.get(
                                        Res.string.dashboard_document_credentials_info_text,
                                        document.credentialsCount,
                                        document.initialCredentialsCount
                                    )
                                )

                                createDocumentTrailingContentData(
                                    documentCredentialsInfoUi = documentCredentialsInfoUi,
                                    documentLowOnCredentials = document.isLowOnCredentials,
                                    showBatchIssuanceCounter = showBatchIssuanceCounter
                                )
                            }
                        }

                        FilterableItem(
                            payload = DocumentUi(
                                documentIssuanceState = documentIssuanceState,
                                uiData = ListItemDataUi(
                                    itemId = document.id,
                                    mainContentData = ListItemMainContentDataUi.Text(text = document.name),
                                    overlineText = issuerName,
                                    supportingText = supportingText,
                                    leadingContentData = ListItemLeadingContentDataUi.AsyncImage(
                                        imageUrl = document.issuerLogoUri.orEmpty(),
                                        contentDescription = strings.get(Res.string.content_description_issuer_logo_icon),
                                        errorImage = AppIcons.Id,
                                    ),
                                    trailingContentData = trailingContentData
                                ),
                                documentIdentifier = documentIdentifier,
                                documentCategory = documentCategory,
                            ),
                            attributes = DocumentsFilterableAttributes(
                                searchTags = documentSearchTags,
                                issuedDate = document.issuedAt,
                                expiryDate = document.expiresAt,
                                issuer = issuerName,
                                name = document.name,
                                category = documentCategory,
                                isRevoked = document.isRevoked
                            )
                        )
                    }
            )

            emit(
                DocumentInteractorGetDocumentsPartialState.Success(
                    allDocuments = allDocuments,
                    shouldAllowUserInteraction = shouldAllowUserInteraction,
                )
            )
        }.safeAsync {
            DocumentInteractorGetDocumentsPartialState.Failure(
                error = it.message ?: genericErrorMsg
            )
        }

    private fun createDocumentTrailingContentData(
        documentCredentialsInfoUi: DocumentCredentialsInfoUi,
        documentLowOnCredentials: Boolean,
        showBatchIssuanceCounter: Boolean,
    ): ListItemTrailingContentDataUi {
        val lowOnCredentialsIcon = AppIcons.ErrorFilled
        val lowOnCredentialsIconTint = ColorKey.Warning

        return when {
            showBatchIssuanceCounter && documentLowOnCredentials -> {
                ListItemTrailingContentDataUi.TextWithIcon(
                    text = documentCredentialsInfoUi.title,
                    iconData = lowOnCredentialsIcon,
                    tint = lowOnCredentialsIconTint
                )
            }

            showBatchIssuanceCounter -> {
                ListItemTrailingContentDataUi.TextWithIcon(
                    text = documentCredentialsInfoUi.title,
                    iconData = AppIcons.KeyboardArrowRight
                )
            }

            documentLowOnCredentials -> {
                ListItemTrailingContentDataUi.Icon(
                    iconData = lowOnCredentialsIcon,
                    tint = lowOnCredentialsIconTint
                )
            }

            else -> {
                ListItemTrailingContentDataUi.Icon(
                    iconData = AppIcons.KeyboardArrowRight
                )
            }
        }
    }

    override fun tryIssuingDeferredDocumentsFlow(
        deferredDocuments: Map<String, FormatType>,
        dispatcher: CoroutineDispatcher,
    ): Flow<DocumentInteractorRetryIssuingDeferredDocumentsPartialState> =
        platform.tryIssuingDeferredDocuments(deferredDocuments, dispatcher)

    override fun deleteDocument(
        documentId: String,
    ): Flow<DocumentInteractorDeleteDocumentPartialState> = platform.deleteDocument(documentId)

    override fun addDynamicFilters(documents: FilterableList, filters: Filters): Filters {
        return filters.copy(
            filterGroups = filters.filterGroups.map { filterGroup ->
                when (filterGroup.id) {
                    DocumentFilterIds.FILTER_BY_ISSUER_GROUP_ID -> {
                        filterGroup as FilterGroup.MultipleSelectionFilterGroup<*>
                        filterGroup.copy(
                            filters = addIssuerFilter(documents)
                        )
                    }

                    DocumentFilterIds.FILTER_BY_DOCUMENT_CATEGORY_GROUP_ID -> {
                        filterGroup as FilterGroup.MultipleSelectionFilterGroup<*>
                        filterGroup.copy(
                            filters = addDocumentCategoryFilter(documents)
                        )
                    }

                    DocumentFilterIds.FILTER_BY_STATE_GROUP_ID -> {
                        filterGroup as FilterGroup.MultipleSelectionFilterGroup<*>
                        filterGroup.copy(
                            filters = buildList {
                                addAll(filterGroup.filters)
                            }
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
        filterGroups = listOf(
            // Filter by expiry period
            FilterGroup.SingleSelectionFilterGroup(
                id = DocumentFilterIds.FILTER_BY_PERIOD_GROUP_ID,
                name = strings.get(Res.string.documents_screen_filters_filter_by_expiry_period),
                filters = listOf(
                    FilterItem(
                        id = DocumentFilterIds.FILTER_BY_PERIOD_DEFAULT,
                        name = strings.get(Res.string.documents_screen_filters_sort_default),
                        selected = true,
                        isDefault = true,
                        filterableAction = FilterAction.Filter<DocumentsFilterableAttributes> { _, _ ->
                            true // Get everything
                        }
                    ),
                    FilterItem(
                        id = DocumentFilterIds.FILTER_BY_PERIOD_NEXT_7,
                        name = strings.get(Res.string.documents_screen_filters_filter_by_expiry_period_1),
                        selected = false,
                        filterableAction = FilterAction.Filter<DocumentsFilterableAttributes> { attributes, _ ->
                            attributes.expiryDate?.isWithinNextDays(7) == true
                        }
                    ),
                    FilterItem(
                        id = DocumentFilterIds.FILTER_BY_PERIOD_NEXT_30,
                        name = strings.get(Res.string.documents_screen_filters_filter_by_expiry_period_2),
                        selected = false,
                        filterableAction = FilterAction.Filter<DocumentsFilterableAttributes> { attributes, _ ->
                            attributes.expiryDate?.isWithinNextDays(30) == true
                        }
                    ),
                    FilterItem(
                        id = DocumentFilterIds.FILTER_BY_PERIOD_BEYOND_30,
                        name = strings.get(Res.string.documents_screen_filters_filter_by_expiry_period_3),
                        selected = false,
                        filterableAction = FilterAction.Filter<DocumentsFilterableAttributes> { attributes, _ ->
                            attributes.expiryDate?.isBeyondNextDays(30) == true
                        }
                    ),
                    FilterItem(
                        id = DocumentFilterIds.FILTER_BY_PERIOD_EXPIRED,
                        name = strings.get(Res.string.documents_screen_filters_filter_by_expiry_period_4),
                        selected = false,
                        filterableAction = FilterAction.Filter<DocumentsFilterableAttributes> { attributes, _ ->
                            attributes.expiryDate?.isExpired() == true
                        }
                    )
                )
            ),
            // Filter by Issuer
            FilterGroup.MultipleSelectionFilterGroup(
                id = DocumentFilterIds.FILTER_BY_ISSUER_GROUP_ID,
                name = strings.get(Res.string.documents_screen_filters_filter_by_issuer),
                filters = emptyList(),
                filterableAction = FilterMultipleAction<DocumentsFilterableAttributes> { attributes, filter ->
                    attributes.issuer == filter.name
                }
            ),
            // Filter by category
            FilterGroup.MultipleSelectionFilterGroup(
                id = DocumentFilterIds.FILTER_BY_DOCUMENT_CATEGORY_GROUP_ID,
                name = strings.get(Res.string.documents_screen_filters_filter_by_category),
                filters = emptyList(),
                filterableAction = FilterMultipleAction<DocumentsFilterableAttributes> { attributes, filter ->
                    attributes.category.id.toString() == filter.id
                }
            ),
            // Filter by State
            FilterGroup.MultipleSelectionFilterGroup(
                id = DocumentFilterIds.FILTER_BY_STATE_GROUP_ID,
                name = strings.get(Res.string.documents_screen_filters_filter_by_state),
                filters = listOf(
                    FilterItem(
                        id = DocumentFilterIds.FILTER_BY_STATE_VALID,
                        name = strings.get(Res.string.documents_screen_filters_filter_by_state_valid),
                        selected = true,
                        isDefault = true,
                    ),
                    FilterItem(
                        id = DocumentFilterIds.FILTER_BY_STATE_EXPIRED,
                        name = strings.get(Res.string.documents_screen_filters_filter_by_state_expired),
                        selected = false,
                        isDefault = false,
                    ),
                    FilterItem(
                        id = DocumentFilterIds.FILTER_BY_STATE_REVOKED,
                        name = strings.get(Res.string.documents_screen_filters_filter_by_state_revoked),
                        selected = false,
                        isDefault = false,
                    ),
                ),
                filterableAction = FilterMultipleAction<DocumentsFilterableAttributes> { attributes, filter ->
                    when (filter.id) {
                        DocumentFilterIds.FILTER_BY_STATE_VALID -> {
                            (attributes.expiryDate?.isValid() == true || attributes.expiryDate == null)
                                    && attributes.isRevoked == false
                        }

                        DocumentFilterIds.FILTER_BY_STATE_EXPIRED -> attributes.expiryDate?.isExpired() == true && attributes.isRevoked == false
                        DocumentFilterIds.FILTER_BY_STATE_REVOKED -> attributes.isRevoked
                        else -> true
                    }
                }
            )
        ),
        sortOrder = SortOrder.Ascending(isDefault = true),
        sort = FilterSort(
            id = DocumentFilterIds.FILTER_SORT_GROUP_ID,
            name = strings.get(Res.string.documents_screen_filters_sort_by),
            filters = listOf(
                FilterItem(
                    id = DocumentFilterIds.FILTER_SORT_DEFAULT,
                    name = strings.get(Res.string.documents_screen_filters_sort_default),
                    selected = true,
                    isDefault = true,
                    filterableAction = FilterAction.Sort<DocumentsFilterableAttributes, String> { attributes ->
                        attributes.name.lowercase()
                    }
                ),
            )
        )
    )

    private fun addDocumentCategoryFilter(documents: FilterableList): List<FilterItem> {
        return documents.items
            .distinctBy { (it.attributes as DocumentsFilterableAttributes).category }
            .map { filterableItem ->
                with(filterableItem.attributes as DocumentsFilterableAttributes) {
                    FilterItem(
                        id = category.id.toString(),
                        name = strings.get(category.nameRes),
                        selected = true,
                        isDefault = true
                    )
                }
            }
    }

    private fun addIssuerFilter(documents: FilterableList): List<FilterItem> {
        return documents.items
            .distinctBy { (it.attributes as DocumentsFilterableAttributes).issuer }
            .map { filterableItem ->
                with(filterableItem.attributes as DocumentsFilterableAttributes) {
                    FilterItem(
                        id = issuer,
                        name = issuer,
                        selected = true,
                        isDefault = true,
                    )
                }
            }
    }

}