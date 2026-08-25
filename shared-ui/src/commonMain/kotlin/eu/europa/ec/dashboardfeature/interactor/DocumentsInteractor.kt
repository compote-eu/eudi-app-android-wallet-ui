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

// Phase 2: the *contract* is KMP — every type in these signatures is now platform-neutral (the
// filter framework and the document models moved to shared, and `DocumentId` was only ever a
// typealias for String) — so it lives in commonMain next to `DocumentsViewModel`, its only
// consumer. `DocumentsInteractorImpl` stays in :dashboard-feature: it resolves strings through
// ResourceProvider and drives wallet-core's issuance/deletion controller. Package unchanged.
package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.businesslogic.extension.ioDispatcher
import eu.europa.ec.businesslogic.validator.model.FilterableList
import eu.europa.ec.businesslogic.validator.model.Filters
import eu.europa.ec.corelogic.model.DeferredDocumentDataDomain
import eu.europa.ec.corelogic.model.DocumentCategory
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.dashboardfeature.ui.documents.list.model.DocumentUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

sealed class DocumentInteractorFilterPartialState {
    data class FilterApplyResult(
        val documents: List<Pair<DocumentCategory, List<DocumentUi>>>,
        val filters: List<ExpandableListItemUi.NestedListItem>,
        val allDefaultFiltersAreSelected: Boolean,
    ) : DocumentInteractorFilterPartialState()

    data class FilterUpdateResult(
        val filters: List<ExpandableListItemUi.NestedListItem>,
    ) : DocumentInteractorFilterPartialState()
}

sealed class DocumentInteractorGetDocumentsPartialState {
    data class Success(
        val allDocuments: FilterableList,
        val shouldAllowUserInteraction: Boolean,
    ) : DocumentInteractorGetDocumentsPartialState()

    data class Failure(val error: String) : DocumentInteractorGetDocumentsPartialState()
}

sealed class DocumentInteractorDeleteDocumentPartialState {
    data object SingleDocumentDeleted : DocumentInteractorDeleteDocumentPartialState()
    data object AllDocumentsDeleted : DocumentInteractorDeleteDocumentPartialState()
    data class Failure(val errorMessage: String) :
        DocumentInteractorDeleteDocumentPartialState()
}

sealed class DocumentInteractorRetryIssuingDeferredDocumentPartialState {
    data class Success(
        val deferredDocumentData: DeferredDocumentDataDomain,
    ) : DocumentInteractorRetryIssuingDeferredDocumentPartialState()

    data class NotReady(
        val deferredDocumentData: DeferredDocumentDataDomain,
    ) : DocumentInteractorRetryIssuingDeferredDocumentPartialState()

    data class Failure(
        val documentId: String,
        val errorMessage: String,
    ) : DocumentInteractorRetryIssuingDeferredDocumentPartialState()

    data class Expired(
        val documentId: String,
    ) : DocumentInteractorRetryIssuingDeferredDocumentPartialState()

    data class IssuerNotTrusted(
        val documentId: String,
    ) : DocumentInteractorRetryIssuingDeferredDocumentPartialState()
}

sealed class DocumentInteractorRetryIssuingDeferredDocumentsPartialState {
    data class Result(
        val successfullyIssuedDeferredDocuments: List<DeferredDocumentDataDomain>,
        val failedIssuedDeferredDocuments: List<String>,
    ) : DocumentInteractorRetryIssuingDeferredDocumentsPartialState()

    data class Failure(
        val errorMessage: String,
    ) : DocumentInteractorRetryIssuingDeferredDocumentsPartialState()
}

interface DocumentsInteractor {
    fun getDocuments(): Flow<DocumentInteractorGetDocumentsPartialState>

    fun tryIssuingDeferredDocumentsFlow(
        deferredDocuments: Map<String, FormatType>,
        dispatcher: CoroutineDispatcher = ioDispatcher,
    ): Flow<DocumentInteractorRetryIssuingDeferredDocumentsPartialState>

    fun deleteDocument(
        documentId: String,
    ): Flow<DocumentInteractorDeleteDocumentPartialState>

    fun onFilterStateChange(): Flow<DocumentInteractorFilterPartialState>
    fun initializeFilters(
        filterableList: FilterableList,
    )

    fun updateLists(filterableList: FilterableList)
    fun applyFilters()
    fun applySearch(query: String)
    fun resetFilters()
    fun revertFilters()
    fun updateFilter(filterGroupId: String, filterId: String)
    fun updateSort(filterId: String)
    fun addDynamicFilters(
        documents: FilterableList,
        filters: Filters = Filters.emptyFilters(),
    ): Filters

    fun getFilters(): Filters

    /**
     * The supporting line shown under a deferred document whose issuance failed.
     *
     * It belongs here rather than in the view-model because `ListItemDataUi.supportingContentData` is a
     * resolved `String` — this interactor already fills it for the *pending* case a few lines away —
     * and exposing it is what lets `DocumentsViewModel` stop injecting a resolver of its own.
     */
    val deferredFailedSupportingText: String
}

