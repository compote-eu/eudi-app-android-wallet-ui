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

// DocumentsViewModel. Entirely in commonTest — nothing on this screen takes a `PlatformContext` or
// `PlatformIntent`, so every path is reachable from common code and therefore runs on iOS too.
package eu.europa.ec.dashboardfeature.ui.documents.list

import eu.europa.ec.businesslogic.validator.model.FilterableItem
import eu.europa.ec.businesslogic.validator.model.FilterableList
import eu.europa.ec.businesslogic.validator.model.Filters
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.corelogic.model.DeferredDocumentDataDomain
import eu.europa.ec.corelogic.model.DocumentCategory
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.corelogic.model.toDocumentIdentifier
import eu.europa.ec.dashboardfeature.interactor.DocumentInteractorDeleteDocumentPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentInteractorFilterPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentInteractorGetDocumentsPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentInteractorRetryIssuingDeferredDocumentsPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentsInteractor
import eu.europa.ec.dashboardfeature.ui.documents.detail.model.DocumentIssuanceStateUi
import eu.europa.ec.dashboardfeature.ui.documents.list.model.DocumentUi
import eu.europa.ec.dashboardfeature.ui.documents.list.model.DocumentsFilterableAttributes
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentDetailsRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.shared.navigation.SplashRoute
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.component.ListItemSupportingContentDataUi
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PID_FORMAT = "eu.europa.ec.eudi.pid.1"

internal class FakeDocumentsInteractor(
    private val filterStates: Flow<DocumentInteractorFilterPartialState> = MutableSharedFlow(),
    private val getDocumentsResults: List<DocumentInteractorGetDocumentsPartialState> = emptyList(),
    private val deferredResult: DocumentInteractorRetryIssuingDeferredDocumentsPartialState? = null,
    private val deleteResult: DocumentInteractorDeleteDocumentPartialState? = null,
    override val deferredFailedSupportingText: String = "Issuance failed",
) : DocumentsInteractor {

    var getDocumentsCalls: Int = 0
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
    var deferredRetryCalls: MutableList<Map<String, FormatType>> = mutableListOf()
        private set
    var deletedIds: MutableList<String> = mutableListOf()
        private set

    override fun onFilterStateChange(): Flow<DocumentInteractorFilterPartialState> = filterStates

    override fun getDocuments(): Flow<DocumentInteractorGetDocumentsPartialState> = flow {
        // Successive calls can return different results, so a refetch can be distinguished from the
        // first fetch. The last entry repeats once exhausted.
        val index = getDocumentsCalls.coerceAtMost(getDocumentsResults.lastIndex)
        getDocumentsCalls++
        getDocumentsResults.getOrNull(index)?.let { emit(it) }
    }

    override fun tryIssuingDeferredDocumentsFlow(
        deferredDocuments: Map<String, FormatType>,
        dispatcher: CoroutineDispatcher,
    ): Flow<DocumentInteractorRetryIssuingDeferredDocumentsPartialState> = flow {
        deferredRetryCalls.add(deferredDocuments)
        deferredResult?.let { emit(it) }
    }

    override fun deleteDocument(
        documentId: String,
    ): Flow<DocumentInteractorDeleteDocumentPartialState> = flow {
        deletedIds.add(documentId)
        deleteResult?.let { emit(it) }
    }

    override fun initializeFilters(filterableList: FilterableList) {
        initializedWith.add(filterableList)
    }

    override fun updateLists(filterableList: FilterableList) {
        updatedWith.add(filterableList)
    }

    override fun applyFilters() {
        applyFilterCalls++
    }

    override fun applySearch(query: String) {
        lastSearchQuery = query
    }

    override fun resetFilters() {
        resetFilterCalls++
    }

    override fun revertFilters() {
        revertFilterCalls++
    }

    override fun updateFilter(filterGroupId: String, filterId: String) {
        updatedFilters.add(filterGroupId to filterId)
    }

    override fun updateSort(filterId: String) = Unit

    override fun addDynamicFilters(documents: FilterableList, filters: Filters): Filters = filters

    override fun getFilters(): Filters = Filters.emptyFilters()
}

private fun documentUi(
    id: String,
    issuanceState: DocumentIssuanceStateUi = DocumentIssuanceStateUi.Issued,
    formatType: String = PID_FORMAT,
    category: DocumentCategory = DocumentCategory.Government,
) = DocumentUi(
    documentIssuanceState = issuanceState,
    uiData = ListItemDataUi(
        itemId = id,
        mainContentData = ListItemMainContentDataUi.Text("Document $id"),
    ),
    documentIdentifier = formatType.toDocumentIdentifier(),
    documentCategory = category,
)

private fun filterableListOf(vararg documents: DocumentUi) = FilterableList(
    items = documents.map { document ->
        FilterableItem(
            payload = document,
            attributes = DocumentsFilterableAttributes(
                searchTags = emptyList(),
                name = "Document ${document.uiData.itemId}",
                expiryDate = null,
                issuedDate = null,
                issuer = "Issuer",
                category = document.documentCategory,
                isRevoked = false,
            ),
        )
    }
)

private fun successOf(vararg documents: DocumentUi, allowInteraction: Boolean = true) =
    DocumentInteractorGetDocumentsPartialState.Success(
        allDocuments = filterableListOf(*documents),
        shouldAllowUserInteraction = allowInteraction,
    )

/**
 * Mirrors what the interactor builds for a filter group. The trailing expansion icon is not
 * decoration: `toggleExpansionState` only toggles headers that carry one, so a group without it is
 * inert.
 */
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
class DocumentsViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The effect channel is a rendezvous [kotlinx.coroutines.channels.Channel]: an unread send stays
     * parked and a later collector would pick it up out of order. Every test that inspects effects
     * therefore starts collecting into a list *before* sending its event.
     */
    private fun CoroutineScope.collectEffects(viewModel: DocumentsViewModel): Pair<List<Effect>, Job> {
        val effects = mutableListOf<Effect>()
        val job = launch { viewModel.effect.collect { effects.add(it) } }
        return effects to job
    }

    //region init / filter-state collection

    @Test
    fun the_filter_collector_runs_from_init_not_from_an_event() = runTest(mainDispatcher) {
        val filterStates = MutableSharedFlow<DocumentInteractorFilterPartialState>()
        val viewModel = DocumentsViewModel(FakeDocumentsInteractor(filterStates = filterStates))
        advanceUntilIdle()

        // No event was ever sent. The collector must already be running: it used to be started from
        // an `Event.Init` whose `rememberSaveable` guard was restored as "already ran" after process
        // death, which left the list permanently empty.
        filterStates.emit(
            DocumentInteractorFilterPartialState.FilterApplyResult(
                documents = listOf(DocumentCategory.Government to listOf(documentUi("doc-1"))),
                filters = listOf(filterGroup("group-1")),
                allDefaultFiltersAreSelected = true,
            )
        )
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertEquals(1, state.documentsUi.size)
        assertEquals("doc-1", state.documentsUi.first().second.first().uiData.itemId)
        assertFalse(state.showNoResultsFound)
        // All defaults selected means filtering is not "active" (no chip on the toolbar).
        assertFalse(state.isFilteringActive)
    }

    @Test
    fun an_empty_filter_result_reports_no_results_found() = runTest(mainDispatcher) {
        val filterStates = MutableSharedFlow<DocumentInteractorFilterPartialState>()
        val viewModel = DocumentsViewModel(FakeDocumentsInteractor(filterStates = filterStates))
        advanceUntilIdle()

        filterStates.emit(
            DocumentInteractorFilterPartialState.FilterApplyResult(
                documents = emptyList(),
                filters = emptyList(),
                allDefaultFiltersAreSelected = false,
            )
        )
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertTrue(state.showNoResultsFound)
        // A non-default selection is what makes filtering "active".
        assertTrue(state.isFilteringActive)
    }

    @Test
    fun a_filter_update_replaces_only_the_filters_and_keeps_the_documents() =
        runTest(mainDispatcher) {
            val filterStates = MutableSharedFlow<DocumentInteractorFilterPartialState>()
            val viewModel = DocumentsViewModel(FakeDocumentsInteractor(filterStates = filterStates))
            advanceUntilIdle()

            filterStates.emit(
                DocumentInteractorFilterPartialState.FilterApplyResult(
                    documents = listOf(DocumentCategory.Government to listOf(documentUi("doc-1"))),
                    filters = listOf(filterGroup("group-1")),
                    allDefaultFiltersAreSelected = true,
                )
            )
            advanceUntilIdle()
            filterStates.emit(
                DocumentInteractorFilterPartialState.FilterUpdateResult(
                    filters = listOf(filterGroup("group-1"), filterGroup("group-2")),
                )
            )
            advanceUntilIdle()

            val state = viewModel.viewState.value
            assertEquals(2, state.filtersUi.size)
            // The documents are untouched: an update reflects a *pending* filter selection, which is
            // only applied to the list when the user confirms it.
            assertEquals(1, state.documentsUi.size)
        }

    //endregion

    //region getDocuments

    @Test
    fun fetching_documents_reports_the_pending_ones_so_deferred_issuing_can_start() =
        runTest(mainDispatcher) {
            val interactor = FakeDocumentsInteractor(
                getDocumentsResults = listOf(
                    successOf(
                        documentUi("issued-1"),
                        documentUi("pending-1", DocumentIssuanceStateUi.Pending),
                        documentUi("pending-2", DocumentIssuanceStateUi.Pending, formatType = "other"),
                    )
                ),
            )
            val viewModel = DocumentsViewModel(interactor)
            advanceUntilIdle()

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.GetDocuments)
            advanceUntilIdle()
            job.cancel()

            // Only the pending documents are handed back, keyed by id and carrying the format the
            // retry needs.
            val fetched = effects.filterIsInstance<Effect.DocumentsFetched>().single()
            assertEquals(
                mapOf("pending-1" to PID_FORMAT, "pending-2" to "other"),
                fetched.deferredDocs,
            )
            val state = viewModel.viewState.value
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertTrue(state.allowUserInteraction)
        }

    @Test
    fun every_fetch_rebuilds_the_filters_rather_than_only_swapping_the_list() =
        runTest(mainDispatcher) {
            val interactor = FakeDocumentsInteractor(
                getDocumentsResults = listOf(successOf(documentUi("doc-1"))),
            )
            val viewModel = DocumentsViewModel(interactor)
            advanceUntilIdle()

            val (_, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.GetDocuments)
            advanceUntilIdle()
            viewModel.setEvent(Event.GetDocuments)
            advanceUntilIdle()
            job.cancel()

            // This used to assert the opposite, justified by "rebuilding the filters from scratch
            // would discard the user's selection". It does not: `initializeFilters` merges, keeping
            // every selection whose filter still exists — `FilterValidatorTest` pins that. What the
            // cheap path really discarded was the *issuer* group's items, which are derived from the
            // documents, so a document from an issuer not in the old list was hidden outright.
            assertEquals(2, interactor.initializedWith.size)
            assertEquals(0, interactor.updatedWith.size)
            assertEquals(2, interactor.applyFilterCalls)
        }

    @Test
    fun a_document_that_appears_between_fetches_reaches_the_filters() = runTest(mainDispatcher) {
        // The severe half of the same defect. The issuer filter group has no unconditional entry — it
        // is one item per issuer found in the list — so a group derived from an empty list is *empty*,
        // and an empty multiple-selection group excludes every document rather than none.
        val interactor = FakeDocumentsInteractor(
            getDocumentsResults = listOf(
                successOf(),
                successOf(documentUi("doc-1")),
            ),
        )
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (_, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.GetDocuments)
        advanceUntilIdle()
        viewModel.setEvent(Event.GetDocuments)
        advanceUntilIdle()
        job.cancel()

        assertEquals(2, interactor.initializedWith.size)
        assertEquals(0, interactor.initializedWith.first().items.size)
        assertEquals(1, interactor.initializedWith.last().items.size)
    }

    @Test
    fun every_load_initializes_the_filters_pause_or_no_pause() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor(
            getDocumentsResults = listOf(successOf(documentUi("doc-1"))),
        )
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (_, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.GetDocuments)
        advanceUntilIdle()
        viewModel.setEvent(Event.OnPause)
        advanceUntilIdle()
        viewModel.setEvent(Event.GetDocuments)
        advanceUntilIdle()
        job.cancel()

        // Pausing used to be what made the next fetch rebuild the derived groups. It no longer
        // decides anything — every fetch rebuilds — and this is here so that a reintroduced special
        // case fails.
        assertEquals(2, interactor.initializedWith.size)
        assertEquals(0, interactor.updatedWith.size)
    }

    @Test
    fun documents_that_failed_deferred_issuance_are_marked_failed() = runTest(mainDispatcher) {
        // A failed id is only known after a retry, so the view-model rewrites those rows itself
        // rather than refetching them differently.
        val interactor = FakeDocumentsInteractor(
            getDocumentsResults = listOf(
                successOf(documentUi("pending-1", DocumentIssuanceStateUi.Pending)),
            ),
            deferredResult = DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result(
                successfullyIssuedDeferredDocuments = emptyList(),
                failedIssuedDeferredDocuments = listOf("pending-1"),
            ),
            deferredFailedSupportingText = "Issuance failed",
        )
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (_, job) = collectEffects(viewModel)
        viewModel.setEvent(
            Event.TryIssuingDeferredDocuments(deferredDocs = mapOf("pending-1" to PID_FORMAT))
        )
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("pending-1"), viewModel.viewState.value.deferredFailedDocIds)
        // The refetch that follows the retry is where the Failed row is produced.
        val refetched = interactor.updatedWith.lastOrNull() ?: interactor.initializedWith.last()
        val payload = refetched.items.single().payload as DocumentUi
        assertEquals(DocumentIssuanceStateUi.Failed, payload.documentIssuanceState)
        // The supporting line comes from the interactor, which is why this view-model needs no
        // string resolver of its own.
        assertEquals(
            ListItemSupportingContentDataUi.Text(
                text = "Issuance failed",
                textColorKey = ColorKey.Error,
            ),
            payload.uiData.supportingContentData
        )
        val trailing = assertIs<ListItemTrailingContentDataUi.Icon>(payload.uiData.trailingContentData)
        assertEquals(AppIcons.ErrorFilled, trailing.iconData)
        assertEquals(ColorKey.Error, trailing.tint)
    }

    @Test
    fun a_fetch_failure_offers_a_retry_and_a_cancel_that_pops() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor(
            getDocumentsResults = listOf(
                DocumentInteractorGetDocumentsPartialState.Failure(error = "boom"),
                successOf(documentUi("doc-1")),
            ),
        )
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.GetDocuments)
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        assertFalse(viewModel.viewState.value.isLoading)

        // Retrying re-runs the same event, which this time succeeds and clears the error.
        assertNotNull(error.onRetry).invoke()
        advanceUntilIdle()
        assertNull(viewModel.viewState.value.error)
        assertEquals(2, interactor.getDocumentsCalls)

        // Cancelling clears the error and leaves the screen.
        viewModel.setEvent(Event.GetDocuments)
        advanceUntilIdle()
        job.cancel()
    }

    @Test
    fun cancelling_a_fetch_error_clears_it_and_pops() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor(
            getDocumentsResults = listOf(
                DocumentInteractorGetDocumentsPartialState.Failure(error = "boom"),
            ),
        )
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.GetDocuments)
        advanceUntilIdle()

        assertNotNull(viewModel.viewState.value.error).onCancel()
        advanceUntilIdle()
        job.cancel()

        assertNull(viewModel.viewState.value.error)
        assertTrue(effects.any { it is Effect.Navigation.Pop })
    }

    //endregion

    //region navigation

    @Test
    fun opening_a_document_navigates_to_its_details() = runTest(mainDispatcher) {
        val viewModel = DocumentsViewModel(FakeDocumentsInteractor())
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.GoToDocumentDetails(docId = "doc-42"))
        advanceUntilIdle()
        job.cancel()

        val switch = assertIs<Effect.Navigation.SwitchScreen>(effects.single())
        assertEquals(DocumentDetailsRoute(documentId = "doc-42"), switch.route)
    }

    @Test
    fun popping_navigates_back() = runTest(mainDispatcher) {
        val viewModel = DocumentsViewModel(FakeDocumentsInteractor())
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.Navigation.Pop>(effects.single())
    }

    @Test
    fun adding_a_document_from_the_list_closes_the_sheet_and_starts_issuance() =
        runTest(mainDispatcher) {
            val viewModel = DocumentsViewModel(FakeDocumentsInteractor())
            advanceUntilIdle()

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.BottomSheet.AddDocument.FromList)
            advanceUntilIdle()
            job.cancel()

            assertTrue(effects.any { it is Effect.CloseBottomSheet })
            val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
            val route = assertIs<AddDocumentRoute>(switch.route)
            // No format is pre-selected — the user picks one on the next screen.
            assertEquals(IssuanceFlowType.ExtraDocument(formatType = null), route.config.flowType)
        }

    @Test
    fun scanning_a_qr_opens_the_scanner_in_the_issuance_flow() = runTest(mainDispatcher) {
        val viewModel = DocumentsViewModel(FakeDocumentsInteractor())
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BottomSheet.AddDocument.ScanQr)
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.any { it is Effect.CloseBottomSheet })
        val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
        val route = assertIs<QrScanRoute>(switch.route)
        val flow = assertIs<QrScanFlow.Issuance>(route.config.qrScanFlow)
        assertEquals(IssuanceFlowType.ExtraDocument(formatType = null), flow.issuanceFlowType)
    }

    //endregion

    //region bottom sheet

    @Test
    fun pressing_add_document_opens_the_add_document_sheet() = runTest(mainDispatcher) {
        val viewModel = DocumentsViewModel(FakeDocumentsInteractor())
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.AddDocumentPressed)
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.ShowBottomSheet>(effects.single())
        assertIs<DocumentsBottomSheetContent.AddDocument>(viewModel.viewState.value.sheetContent)
    }

    @Test
    fun selecting_a_not_ready_deferred_document_opens_its_sheet() = runTest(mainDispatcher) {
        val viewModel = DocumentsViewModel(FakeDocumentsInteractor())
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(
            Event.BottomSheet.DeferredDocument.DeferredNotReadyYet.DocumentSelected("pending-1")
        )
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.ShowBottomSheet>(effects.single())
        val content = assertIs<DocumentsBottomSheetContent.DeferredDocumentPressed>(
            viewModel.viewState.value.sheetContent
        )
        assertEquals("pending-1", content.documentId)
    }

    @Test
    fun declining_to_delete_a_deferred_document_only_closes_the_sheet() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor()
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(
            Event.BottomSheet.DeferredDocument.DeferredNotReadyYet.SecondaryButtonPressed("pending-1")
        )
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.CloseBottomSheet>(effects.single())
        assertTrue(interactor.deletedIds.isEmpty())
    }

    @Test
    fun choosing_a_newly_issued_deferred_document_opens_its_details() = runTest(mainDispatcher) {
        val viewModel = DocumentsViewModel(FakeDocumentsInteractor())
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(
            Event.BottomSheet.DeferredDocument
                .OptionListItemForSuccessfullyIssuingDeferredDocumentSelected("doc-7")
        )
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.any { it is Effect.CloseBottomSheet })
        val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
        assertEquals(DocumentDetailsRoute(documentId = "doc-7"), switch.route)
    }

    //endregion

    //region filters

    @Test
    fun opening_the_filters_sheet_collapses_every_group() = runTest(mainDispatcher) {
        val filterStates = MutableSharedFlow<DocumentInteractorFilterPartialState>()
        val viewModel = DocumentsViewModel(FakeDocumentsInteractor(filterStates = filterStates))
        advanceUntilIdle()

        filterStates.emit(
            DocumentInteractorFilterPartialState.FilterUpdateResult(
                filters = listOf(filterGroup("group-1", isExpanded = true)),
            )
        )
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.FiltersPressed)
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.ShowBottomSheet>(effects.single())
        assertIs<DocumentsBottomSheetContent.Filters>(viewModel.viewState.value.sheetContent)
        // Every group starts collapsed so the sheet opens at a predictable height.
        assertTrue(viewModel.viewState.value.filtersUi.none { it.isExpanded })
    }

    @Test
    fun expanding_a_group_toggles_only_that_group() = runTest(mainDispatcher) {
        val filterStates = MutableSharedFlow<DocumentInteractorFilterPartialState>()
        val viewModel = DocumentsViewModel(FakeDocumentsInteractor(filterStates = filterStates))
        advanceUntilIdle()

        filterStates.emit(
            DocumentInteractorFilterPartialState.FilterUpdateResult(
                filters = listOf(filterGroup("group-1"), filterGroup("group-2")),
            )
        )
        advanceUntilIdle()

        viewModel.setEvent(Event.OnFilterGroupExpansionChanged(groupId = "group-2"))
        advanceUntilIdle()

        val groups = viewModel.viewState.value.filtersUi
        assertFalse(groups.first { it.header.itemId == "group-1" }.isExpanded)
        assertTrue(groups.first { it.header.itemId == "group-2" }.isExpanded)
    }

    @Test
    fun changing_a_filter_delegates_and_arms_the_revert() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor()
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        viewModel.setEvent(
            Event.OnFilterSelectionChanged(filterId = "filter-1", groupId = "group-1")
        )
        advanceUntilIdle()

        assertEquals(listOf("group-1" to "filter-1"), interactor.updatedFilters)
        assertTrue(viewModel.viewState.value.shouldRevertFilterChanges)
    }

    @Test
    fun applying_filters_disarms_the_revert_and_closes_the_sheet() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor()
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.OnFiltersApply)
        advanceUntilIdle()
        job.cancel()

        assertEquals(1, interactor.applyFilterCalls)
        assertIs<Effect.CloseBottomSheet>(effects.single())
        // Dismissal must not undo what the user just confirmed.
        assertFalse(viewModel.viewState.value.shouldRevertFilterChanges)
    }

    @Test
    fun dismissing_the_filters_sheet_without_applying_reverts_the_changes() =
        runTest(mainDispatcher) {
            val interactor = FakeDocumentsInteractor()
            val viewModel = DocumentsViewModel(interactor)
            advanceUntilIdle()

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
            advanceUntilIdle()
            job.cancel()

            assertEquals(1, interactor.revertFilterCalls)
            assertTrue(effects.any { it is Effect.ResumeOnApplyFilter })
            assertFalse(viewModel.viewState.value.isBottomSheetOpen)
        }

    @Test
    fun dismissing_the_filters_sheet_after_applying_does_not_revert() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor()
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (_, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.OnFiltersApply)
        advanceUntilIdle()
        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
        advanceUntilIdle()
        job.cancel()

        assertEquals(0, interactor.revertFilterCalls)
        // The flag stays disarmed — it is re-armed by the next filter change, not by dismissal, so
        // reopening and dismissing without touching anything cannot undo the applied selection.
        assertFalse(viewModel.viewState.value.shouldRevertFilterChanges)

        viewModel.setEvent(
            Event.OnFilterSelectionChanged(filterId = "filter-1", groupId = "group-1")
        )
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.shouldRevertFilterChanges)
    }

    @Test
    fun resetting_filters_delegates_and_closes_the_sheet() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor()
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.OnFiltersReset)
        advanceUntilIdle()
        job.cancel()

        assertEquals(1, interactor.resetFilterCalls)
        assertIs<Effect.CloseBottomSheet>(effects.single())
    }

    @Test
    fun searching_delegates_the_query_and_records_it() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor()
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        viewModel.setEvent(Event.OnSearchQueryChanged(query = "pid"))
        advanceUntilIdle()

        assertEquals("pid", interactor.lastSearchQuery)
        // Kept in state so the field survives recomposition.
        assertEquals("pid", viewModel.viewState.value.searchText)
    }

    //endregion

    //region deferred issuing

    @Test
    fun no_deferred_documents_means_no_retry_is_attempted() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor()
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        viewModel.setEvent(Event.TryIssuingDeferredDocuments(deferredDocs = emptyMap()))
        advanceUntilIdle()

        assertTrue(interactor.deferredRetryCalls.isEmpty())
    }

    @Test
    fun successfully_issued_deferred_documents_open_a_sheet_of_options() =
        runTest(mainDispatcher) {
            val issued = DeferredDocumentDataDomain(
                documentId = "doc-9",
                formatType = PID_FORMAT,
                docName = "Freshly issued PID",
            )
            val interactor = FakeDocumentsInteractor(
                getDocumentsResults = listOf(successOf(documentUi("doc-9"))),
                deferredResult = DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result(
                    successfullyIssuedDeferredDocuments = listOf(issued),
                    failedIssuedDeferredDocuments = emptyList(),
                ),
            )
            val viewModel = DocumentsViewModel(interactor)
            advanceUntilIdle()

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(
                Event.TryIssuingDeferredDocuments(deferredDocs = mapOf("doc-9" to PID_FORMAT))
            )
            advanceUntilIdle()
            job.cancel()

            // The retry waits before asking the issuer again; `runTest`'s virtual clock skips it.
            assertEquals(listOf(mapOf("doc-9" to PID_FORMAT)), interactor.deferredRetryCalls)
            assertTrue(effects.any { it is Effect.ShowBottomSheet })
            val content = assertIs<DocumentsBottomSheetContent.DeferredDocumentsReady>(
                viewModel.viewState.value.sheetContent
            )
            assertEquals(listOf(issued), content.successfullyIssuedDeferredDocuments)
            val option = content.options.single()
            assertEquals("Freshly issued PID", option.title)
            val event = assertIs<Event.BottomSheet.DeferredDocument
            .OptionListItemForSuccessfullyIssuingDeferredDocumentSelected>(option.event)
            assertEquals("doc-9", event.documentId)
            // The list is refreshed too, so the new document appears behind the sheet.
            assertEquals(1, interactor.getDocumentsCalls)
        }

    @Test
    fun a_deferred_retry_failure_surfaces_a_retryable_error() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor(
            deferredResult = DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Failure(
                errorMessage = "issuer unreachable",
            ),
        )
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (_, job) = collectEffects(viewModel)
        viewModel.setEvent(
            Event.TryIssuingDeferredDocuments(deferredDocs = mapOf("doc-9" to PID_FORMAT))
        )
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        // Cancelling only dismisses the error here — it must not leave the screen, since the list
        // itself loaded fine.
        error.onCancel()
        advanceUntilIdle()
        job.cancel()

        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun pausing_stops_an_in_flight_deferred_retry() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor(
            deferredResult = DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result(
                successfullyIssuedDeferredDocuments = emptyList(),
                failedIssuedDeferredDocuments = emptyList(),
            ),
        )
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        // The retry sleeps before calling the issuer; pausing during that window must cancel it,
        // otherwise it would fire after the user left the screen.
        viewModel.setEvent(
            Event.TryIssuingDeferredDocuments(deferredDocs = mapOf("doc-9" to PID_FORMAT))
        )
        viewModel.setEvent(Event.OnPause)
        advanceUntilIdle()

        assertTrue(interactor.deferredRetryCalls.isEmpty())
    }

    //endregion

    //region deletion

    @Test
    fun deleting_one_document_refetches_the_list() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor(
            getDocumentsResults = listOf(successOf(documentUi("doc-1"))),
            deleteResult = DocumentInteractorDeleteDocumentPartialState.SingleDocumentDeleted,
        )
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (_, job) = collectEffects(viewModel)
        viewModel.setEvent(
            Event.BottomSheet.DeferredDocument.DeferredNotReadyYet.PrimaryButtonPressed("pending-1")
        )
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("pending-1"), interactor.deletedIds)
        assertEquals(1, interactor.getDocumentsCalls)
        assertFalse(viewModel.viewState.value.isLoading)
    }

    @Test
    fun deleting_the_last_document_restarts_the_app_at_the_splash_screen() =
        runTest(mainDispatcher) {
            val interactor = FakeDocumentsInteractor(
                deleteResult = DocumentInteractorDeleteDocumentPartialState.AllDocumentsDeleted,
            )
            val viewModel = DocumentsViewModel(interactor)
            advanceUntilIdle()

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(
                Event.BottomSheet.DeferredDocument.DeferredNotReadyYet
                    .PrimaryButtonPressed("pending-1")
            )
            advanceUntilIdle()
            job.cancel()

            // An empty wallet under forced-PID-activation cannot stay on the dashboard, so the
            // dashboard is popped inclusively and startup decides where to go next.
            val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
            assertEquals(SplashRoute, switch.route)
            assertEquals(DashboardRoute, switch.popUpTo)
            assertTrue(switch.inclusive)
            assertEquals(0, interactor.getDocumentsCalls)
        }

    @Test
    fun a_delete_failure_surfaces_an_error_without_leaving_the_screen() = runTest(mainDispatcher) {
        val interactor = FakeDocumentsInteractor(
            deleteResult = DocumentInteractorDeleteDocumentPartialState.Failure(
                errorMessage = "could not delete",
            ),
        )
        val viewModel = DocumentsViewModel(interactor)
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(
            Event.BottomSheet.DeferredDocument.DeferredNotReadyYet.PrimaryButtonPressed("pending-1")
        )
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        assertFalse(viewModel.viewState.value.isLoading)
        error.onCancel()
        advanceUntilIdle()
        job.cancel()

        assertNull(viewModel.viewState.value.error)
        assertTrue(effects.none { it is Effect.Navigation })
    }

    //endregion
}
