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

import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.controller.storage.PrefKeys
import eu.europa.ec.businesslogic.validator.FilterValidator
import eu.europa.ec.businesslogic.validator.FilterValidatorPartialState
import eu.europa.ec.businesslogic.validator.model.FilterAction
import eu.europa.ec.businesslogic.validator.model.FilterElement.FilterItem
import eu.europa.ec.businesslogic.validator.model.FilterGroup
import eu.europa.ec.businesslogic.validator.model.FilterMultipleAction
import eu.europa.ec.businesslogic.validator.model.FilterSort
import eu.europa.ec.businesslogic.validator.model.FilterableAttributes
import eu.europa.ec.businesslogic.validator.model.FilterableItem
import eu.europa.ec.businesslogic.validator.model.FilterableItemPayload
import eu.europa.ec.businesslogic.validator.model.FilterableList
import eu.europa.ec.businesslogic.validator.model.Filters
import eu.europa.ec.businesslogic.validator.model.SortOrder
import eu.europa.ec.corelogic.controller.DeleteDocumentPartialState
import eu.europa.ec.corelogic.controller.IssueDeferredDocumentPartialState
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.shared.wallet.WalletDocument
import eu.europa.ec.shared.wallet.WalletDocumentIssuanceState
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.corelogic.model.DeferredDocumentDataDomain
import eu.europa.ec.corelogic.model.DocumentCategories
import eu.europa.ec.corelogic.model.DocumentCategory
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.dashboardfeature.ui.documents.detail.model.DocumentIssuanceStateUi
import eu.europa.ec.dashboardfeature.ui.documents.list.model.DocumentFilterIds
import eu.europa.ec.dashboardfeature.ui.documents.list.model.DocumentUi
import eu.europa.ec.dashboardfeature.ui.documents.list.model.DocumentsFilterableAttributes
import eu.europa.ec.dashboardfeature.util.mockedPendingMdlUi
import eu.europa.ec.dashboardfeature.util.mockedPendingPidUi
import eu.europa.ec.eudi.wallet.document.Document
import eu.europa.ec.eudi.wallet.document.DocumentId
import eu.europa.ec.testfeature.util.copy
import eu.europa.ec.testfeature.util.getMockedFullDocuments
import eu.europa.ec.testfeature.util.mockedDefaultLocale
import eu.europa.ec.testfeature.util.mockedMdocPidDocType
import eu.europa.ec.testfeature.util.mockedExceptionWithMessage
import eu.europa.ec.testfeature.util.mockedExceptionWithNoMessage
import eu.europa.ec.testfeature.util.mockedGenericErrorMessage
import eu.europa.ec.testfeature.util.mockedPlainFailureMessage
import eu.europa.ec.testlogic.extension.runFlowTest
import eu.europa.ec.testlogic.extension.runTest
import eu.europa.ec.testlogic.extension.toFlow
import eu.europa.ec.testlogic.rule.CoroutineTestRule
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.time.toKotlinInstant
import java.time.temporal.ChronoUnit
import kotlin.test.assertTrue

class TestDocumentsInteractor {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    @Mock
    private lateinit var strings: StringCatalog

    @Mock
    private lateinit var platform: DocumentsPlatformBridge

    @Mock
    private lateinit var walletEngine: WalletEngine

    @Mock
    private lateinit var filterValidator: FilterValidator

    private lateinit var interactor: DocumentsInteractor

    private lateinit var closeable: AutoCloseable

    private lateinit var mockDocumentId: String
    private lateinit var mockDocumentName: String

    @Before
    fun before() {
        closeable = MockitoAnnotations.openMocks(this)

        interactor = DocumentsInteractorImpl(
            strings = strings,
            walletEngine = walletEngine,
            filterValidator = filterValidator,
            platform = platform,
        )

        whenever(strings[Res.string.generic_error_message]).thenReturn(mockedGenericErrorMessage)

        mockDocumentId = "mockDocumentId"
        mockDocumentName = "mockDocumentName"
    }

    @After
    fun after() {
        closeable.close()
    }

    private fun documentsAttributes(
        name: String = "PID",
        issuedDate: kotlin.time.Instant? = null,
        expiryDate: kotlin.time.Instant? = null,
        issuer: String = "Issuer",
        category: DocumentCategory = DocumentCategory.Government,
        isRevoked: Boolean = false,
    ): DocumentsFilterableAttributes =
        DocumentsFilterableAttributes(
            searchTags = emptyList(),
            name = name,
            expiryDate = expiryDate,
            issuedDate = issuedDate,
            issuer = issuer,
            category = category,
            isRevoked = isRevoked,
        )


    // Case 7:
    // Empty state was returned when onFilterStateChange is collected.

    // Case 7 Expected Result:
    // DocumentInteractorFilterPartialState.FilterApplyResult state with empty documents.
    @Test
    fun `Given Case 7, When onFilterStateChange is called, Then Case 7 Expected Result is returned`() =
        coroutineRule.runTest {
            mockOnFilterChangedEvent(
                FilterValidatorPartialState.FilterListResult.FilterListEmptyResult(
                    updatedFilters = Filters.emptyFilters(), allDefaultFiltersAreSelected = false
                )
            )

            interactor.onFilterStateChange().runFlowTest {
                val state = awaitItem()
                assertTrue(state is DocumentInteractorFilterPartialState.FilterApplyResult)
                assertTrue(state.documents.isEmpty())
            }
        }

    // Case 8:
    // Valid state with documents was returned when onFilterStateChange is collected.

    // Case 8 Expected Result:
    // DocumentInteractorFilterPartialState.FilterApplyResult state with correct data.
    @Test
    fun `Given Case 8, When onFilterStateChange is called, Then Case 8 Expected Result is returned`() =
        coroutineRule.runTest {
            mockOnFilterChangedEvent(
                FilterValidatorPartialState.FilterListResult.FilterApplyResult(
                    filteredList = FilterableList(items = listOf(mockFilterableItem)),
                    allDefaultFiltersAreSelected = false,
                    updatedFilters = Filters.emptyFilters()
                )
            )

            interactor.onFilterStateChange().runFlowTest {
                val state = awaitItem()
                assertTrue(state is DocumentInteractorFilterPartialState.FilterApplyResult)
                assertEquals(state.documents.first().second.first().documentCategory.id, 1)
                assertEquals(
                    (state.documents.first().second.first().uiData.mainContentData as ListItemMainContentDataUi.Text).text,
                    "test"
                )
            }
        }

    // Case 9:
    // Updated filters state was returned when onFilterStateChange is collected.

    // Case 9 Expected Result:
    // DocumentInteractorFilterPartialState.FilterUpdateResult state with updated ui filters.
    @Test
    fun `Given Case 9, When onFilterStateChange is called, Then Case 9 Expected Result is returned`() =
        coroutineRule.runTest {
            mockOnFilterChangedEvent(
                FilterValidatorPartialState.FilterUpdateResult(updatedFilters = mockFilters)
            )

            interactor.onFilterStateChange().runFlowTest {
                val state = awaitItem()
                assertTrue(state is DocumentInteractorFilterPartialState.FilterUpdateResult)
                assertEquals(state.filters.size, mockFilters.filterGroups.size)
                assertEquals(
                    (state.filters.first().header.mainContentData as ListItemMainContentDataUi.Text).text,
                    mockFilters.filterGroups.first().name
                )
                assertEquals(
                    state.filters.first().nestedItems.size,
                    mockFilters.filterGroups.first().filters.size
                )
                assertEquals(false, state.filters.first().isExpanded)
                val trailingContent = state.filters.first().header.trailingContentData
                val trailingIcon = trailingContent as ListItemTrailingContentDataUi.Icon
                assertEquals(
                    AppIcons.KeyboardArrowDown,
                    trailingIcon.iconData
                )
            }
        }

    //endregion

    //region getDocuments

    @Test
    fun `Given two issued documents, When getDocuments is called, Then Success with the documents is emitted`() {
        coroutineRule.runTest {
            // Given
            val documents = listOf(
                issuedWalletDocument(id = "doc-1"),
                issuedWalletDocument(id = "doc-2"),
            )
            mockShowBatchIssuanceCounterPreference(response = true)
            mockGetDocumentsBaseCalls(documents = documents)
            mockGetDocumentsResourceStrings()

            // When
            interactor.getDocuments().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorGetDocumentsPartialState.Success)
                assertEquals(true, state.shouldAllowUserInteraction)
                assertEquals(documents.size, state.allDocuments.items.size)
            }
        }
    }

    @Test
    fun `Given a revoked document, When getDocuments is called, Then documentState is Revoked`() {
        coroutineRule.runTest {
            // Given
            mockShowBatchIssuanceCounterPreference(response = true)
            mockGetDocumentsBaseCalls(
                documents = listOf(issuedWalletDocument(isRevoked = true)),
            )
            mockGetDocumentsResourceStrings()

            // When
            interactor.getDocuments().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorGetDocumentsPartialState.Success)
                val firstItem = state.allDocuments.items.first()
                val payload = firstItem.payload as DocumentUi
                assertEquals(DocumentIssuanceStateUi.Revoked, payload.documentIssuanceState)
            }
        }
    }

    @Test
    fun `Given a low-on-credentials document and show batch issuance counter preference is true, When getDocuments is called, Then TextWithIcon trailing is set`() {
        coroutineRule.runTest {
            // Given
            mockShowBatchIssuanceCounterPreference(response = true)
            mockGetDocumentsBaseCalls(
                documents = listOf(issuedWalletDocument(isLowOnCredentials = true)),
            )
            mockGetDocumentsResourceStrings()

            // When
            interactor.getDocuments().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorGetDocumentsPartialState.Success)
                val firstItem = state.allDocuments.items.first()
                val payload = firstItem.payload as DocumentUi
                assertTrue(payload.uiData.trailingContentData is ListItemTrailingContentDataUi.TextWithIcon)
            }
        }
    }

    @Test
    fun `Given show batch issuance counter preference is false, When getDocuments is called, Then counter text is hidden`() {
        coroutineRule.runTest {
            // Given
            mockShowBatchIssuanceCounterPreference(response = false)
            mockGetDocumentsBaseCalls(
                documents = listOf(issuedWalletDocument()),
            )
            mockGetDocumentsResourceStrings()

            // When
            interactor.getDocuments().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorGetDocumentsPartialState.Success)
                val firstItem = state.allDocuments.items.first()
                val payload = firstItem.payload as DocumentUi
                assertTrue(payload.uiData.trailingContentData is ListItemTrailingContentDataUi.Icon)
            }
        }
    }

    @Test
    fun `Given no main PID document, When getDocuments is called, Then shouldAllowUserInteraction is false`() {
        coroutineRule.runTest {
            // Given
            mockShowBatchIssuanceCounterPreference(response = true)
            mockGetDocumentsBaseCalls(documents = emptyList(), hasMainPid = false)
            whenever(platform.localeTag())
                .thenReturn(mockedDefaultLocale.toLanguageTag())

            // When
            interactor.getDocuments().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorGetDocumentsPartialState.Success)
                assertEquals(false, state.shouldAllowUserInteraction)
                assertEquals(0, state.allDocuments.items.size)
            }
        }
    }

    @Test
    fun `Given the wallet engine throws with message, When getDocuments is called, Then Failure is emitted`() {
        coroutineRule.runTest {
            // Given
            mockShowBatchIssuanceCounterPreference(response = true)
            whenever(walletEngine.getMainPidDocument()).thenReturn(null)
            whenever(walletEngine.getAllDocumentsWithDetails(anyString()))
                .thenThrow(mockedExceptionWithMessage)
            whenever(platform.documentCategories)
                .thenReturn(DocumentCategories(value = emptyMap()))
            whenever(platform.localeTag())
                .thenReturn(mockedDefaultLocale.toLanguageTag())

            // When
            interactor.getDocuments().runFlowTest {
                // Then
                assertEquals(
                    DocumentInteractorGetDocumentsPartialState.Failure(
                        error = mockedExceptionWithMessage.localizedMessage!!
                    ),
                    awaitItem()
                )
            }
        }
    }

    @Test
    fun `Given the wallet engine throws no message, When getDocuments is called, Then Failure with generic message is emitted`() {
        coroutineRule.runTest {
            // Given
            mockShowBatchIssuanceCounterPreference(response = true)
            whenever(walletEngine.getMainPidDocument()).thenReturn(null)
            whenever(walletEngine.getAllDocumentsWithDetails(anyString()))
                .thenThrow(mockedExceptionWithNoMessage)
            whenever(platform.documentCategories)
                .thenReturn(DocumentCategories(value = emptyMap()))
            whenever(platform.localeTag())
                .thenReturn(mockedDefaultLocale.toLanguageTag())

            // When
            interactor.getDocuments().runFlowTest {
                // Then
                assertEquals(
                    DocumentInteractorGetDocumentsPartialState.Failure(
                        error = mockedGenericErrorMessage
                    ),
                    awaitItem()
                )
            }
        }
    }
    //endregion

    //region onFilterStateChange filter-group mapping

    @Test
    fun `Given a FilterApplyResult with all four group types, When onFilterStateChange emits it, Then Checkbox and RadioButton trailing types are mapped for each`() {
        coroutineRule.runTest {
            // Given
            val multiple = FilterGroup.MultipleSelectionFilterGroup<DocumentsFilterableAttributes>(
                id = "multi",
                name = "Multi",
                filters = listOf(FilterItem(id = "m1", name = "M1", selected = true)),
                filterableAction = FilterMultipleAction { _, _ -> true },
            )
            val reversibleMultiple =
                FilterGroup.ReversibleMultipleSelectionFilterGroup<DocumentsFilterableAttributes>(
                    id = "rmulti",
                    name = "RMulti",
                    filters = listOf(FilterItem(id = "rm1", name = "RM1", selected = false)),
                    filterableAction = FilterMultipleAction { _, _ -> true },
                )
            val single = FilterGroup.SingleSelectionFilterGroup(
                id = "single",
                name = "Single",
                filters = listOf(FilterItem(id = "s1", name = "S1", selected = true)),
            )
            val reversibleSingle = FilterGroup.ReversibleSingleSelectionFilterGroup(
                id = "rsingle",
                name = "RSingle",
                filters = listOf(FilterItem(id = "rs1", name = "RS1", selected = false)),
            )

            val updatedFilters = Filters(
                filterGroups = listOf(multiple, reversibleMultiple, single, reversibleSingle),
                sortOrder = SortOrder.Ascending(isDefault = true),
            )

            mockOnFilterChangedEvent(
                FilterValidatorPartialState.FilterListResult.FilterApplyResult(
                    filteredList = FilterableList(items = emptyList()),
                    allDefaultFiltersAreSelected = true,
                    updatedFilters = updatedFilters,
                )
            )

            // When
            interactor.onFilterStateChange().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorFilterPartialState.FilterApplyResult)

                val trailing = state.filters.flatMap { nested ->
                    nested.nestedItems.map { it.header.trailingContentData }
                }
                assertTrue(trailing.any { it is ListItemTrailingContentDataUi.Checkbox })
                assertTrue(trailing.any { it is ListItemTrailingContentDataUi.RadioButton })
            }
        }
    }

    @Test
    fun `Given a FilterUpdateResult with sort config, When onFilterStateChange emits it, Then only filter groups are exposed`() {
        coroutineRule.runTest {
            // Given
            val sort = FilterSort(
                id = DocumentFilterIds.FILTER_SORT_GROUP_ID,
                name = "Sort",
                filters = listOf(FilterItem(id = "sort", name = "Sort", selected = true)),
            )
            val single = FilterGroup.SingleSelectionFilterGroup(
                id = "single",
                name = "Single",
                filters = listOf(FilterItem(id = "s1", name = "S1", selected = true)),
            )
            val updatedFilters = Filters(
                filterGroups = listOf(single),
                sortOrder = SortOrder.Descending(),
                sort = sort,
            )
            mockOnFilterChangedEvent(
                FilterValidatorPartialState.FilterUpdateResult(updatedFilters = updatedFilters)
            )

            // When
            interactor.onFilterStateChange().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorFilterPartialState.FilterUpdateResult)
                assertEquals(
                    listOf("single"),
                    state.filters.map { it.header.itemId }
                )
            }
        }
    }
    //endregion

    //region initializeFilters

    @Test
    fun `When initializeFilters is called, Then filterValidator#initializeValidator is invoked`() {
        // Given
        whenever(strings.get(any())).thenReturn("mocked")
        val list = FilterableList(items = emptyList())

        // When
        interactor.initializeFilters(filterableList = list)

        // Then
        verify(filterValidator, times(1))
            .initializeValidator(
                any(),
                eq(list),
            )
    }
    //endregion

    //region getDocuments — pending & expired documents

    @Test
    fun `Given a pending document from the seam, When getDocuments is called, Then it is mapped with Pending state`() {
        coroutineRule.runTest {
            // Given a document the seam reports as not-yet-issued (wallet-core's UnsignedDocument /
            // DeferredDocument), alongside a normal issued one.
            mockShowBatchIssuanceCounterPreference(response = true)
            mockGetDocumentsBaseCalls(
                documents = listOf(
                    issuedWalletDocument(id = "issued-id"),
                    WalletDocument(
                        id = "unsigned-id",
                        name = "Unsigned Doc",
                        formatType = mockedMdocPidDocType,
                        issuanceState = WalletDocumentIssuanceState.Pending,
                    ),
                ),
            )
            mockGetDocumentsResourceStrings()

            // When
            interactor.getDocuments().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorGetDocumentsPartialState.Success)
                val unsignedItem = state.allDocuments.items.firstOrNull {
                    (it.payload as? DocumentUi)?.uiData?.itemId == "unsigned-id"
                }
                assertNotNull(unsignedItem)
                val payload = unsignedItem!!.payload as DocumentUi
                assertEquals(DocumentIssuanceStateUi.Pending, payload.documentIssuanceState)
                // A pending document shows the waiting clock, never a credential counter.
                assertTrue(payload.uiData.trailingContentData is ListItemTrailingContentDataUi.Icon)
                assertEquals(
                    AppIcons.ClockTimer,
                    (payload.uiData.trailingContentData as ListItemTrailingContentDataUi.Icon).iconData
                )
            }
        }
    }

    @Test
    fun `Given a pending document that the seam also reports revoked, When getDocuments is called, Then Pending wins`() {
        coroutineRule.runTest {
            // Given — pending is checked first, so a not-yet-issued document never renders as
            // revoked or expired even if those flags happen to be set.
            mockShowBatchIssuanceCounterPreference(response = true)
            mockGetDocumentsBaseCalls(
                documents = listOf(
                    WalletDocument(
                        id = "unsigned-id",
                        name = "Unsigned Doc",
                        formatType = mockedMdocPidDocType,
                        issuanceState = WalletDocumentIssuanceState.Pending,
                        isRevoked = true,
                    ),
                ),
            )
            mockGetDocumentsResourceStrings()

            // When
            interactor.getDocuments().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorGetDocumentsPartialState.Success)
                val payload = state.allDocuments.items.first().payload as DocumentUi
                assertEquals(DocumentIssuanceStateUi.Pending, payload.documentIssuanceState)
                // ...but the attribute the filters read still carries the engine's verdict.
                val attributes =
                    state.allDocuments.items.first().attributes as DocumentsFilterableAttributes
                assertEquals(true, attributes.isRevoked)
            }
        }
    }

    @Test
    fun `Given an expired document, When getDocuments is called, Then documentIssuanceState is Expired`() {
        coroutineRule.runTest {
            // Given
            mockShowBatchIssuanceCounterPreference(response = true)
            mockGetDocumentsBaseCalls(
                documents = listOf(
                    issuedWalletDocument(
                        isExpired = true,
                        expiresAt = kotlin.time.Instant.parse("2020-01-01T00:00:00Z"),
                    ),
                ),
            )
            mockGetDocumentsResourceStrings()

            // When
            interactor.getDocuments().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorGetDocumentsPartialState.Success)
                val payload = state.allDocuments.items.first().payload as DocumentUi
                assertEquals(DocumentIssuanceStateUi.Expired, payload.documentIssuanceState)
            }
        }
    }

    @Test
    fun `Given a document with no issuer name, When getDocuments is called, Then the unknown-issuer wording is used`() {
        coroutineRule.runTest {
            // Given the engine found no localized issuer display for this document. Resolving the
            // fallback wording is the interactor's job, not the engine's.
            mockShowBatchIssuanceCounterPreference(response = true)
            mockGetDocumentsBaseCalls(
                documents = listOf(issuedWalletDocument().copy(issuerName = null)),
            )
            mockGetDocumentsResourceStrings(genericString = "unknown-issuer")

            // When
            interactor.getDocuments().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorGetDocumentsPartialState.Success)
                val payload = state.allDocuments.items.first().payload as DocumentUi
                assertEquals("unknown-issuer", payload.uiData.overlineText)
            }
        }
    }
    //endregion

    //region onFilterStateChange — non-DocumentUi payload

    @Test
    fun `Given a FilterApplyResult that contains a non-DocumentUi payload, When onFilterStateChange emits it, Then that item is filtered out`() {
        coroutineRule.runTest {
            // Given
            val foreignItem = FilterableItem(
                payload = ForeignDocPayload,
                attributes = object : FilterableAttributes {
                    override val searchTags: List<String> = emptyList()
                },
            )
            mockOnFilterChangedEvent(
                FilterValidatorPartialState.FilterListResult.FilterApplyResult(
                    filteredList = FilterableList(items = listOf(foreignItem)),
                    allDefaultFiltersAreSelected = true,
                    updatedFilters = Filters.emptyFilters(),
                )
            )

            // When
            interactor.onFilterStateChange().runFlowTest {
                // Then
                val state = awaitItem()
                assertTrue(state is DocumentInteractorFilterPartialState.FilterApplyResult)
                val total = state.documents.sumOf { it.second.size }
                assertEquals(0, total)
            }
        }
    }

    private object ForeignDocPayload : FilterableItemPayload
    //endregion

    //region getFilters lambdas

    @Test
    fun `When getFilters is called, Then the expected static filter groups are returned`() {
        // Given
        whenever(strings.get(any())).thenReturn("mocked")

        // When
        val filters = interactor.getFilters()

        // Then
        val ids = filters.filterGroups.map { it.id }
        assertEquals(
            listOf(
                DocumentFilterIds.FILTER_BY_PERIOD_GROUP_ID,
                DocumentFilterIds.FILTER_BY_ISSUER_GROUP_ID,
                DocumentFilterIds.FILTER_BY_DOCUMENT_CATEGORY_GROUP_ID,
                DocumentFilterIds.FILTER_BY_STATE_GROUP_ID,
            ),
            ids
        )
        assertEquals(
            DocumentFilterIds.FILTER_SORT_GROUP_ID,
            filters.sort?.id
        )
    }

    @Test
    fun `When the default sort selector is applied, Then it returns the normalized document name`() {
        // Given
        whenever(strings.get(any())).thenReturn("mocked")
        val attrs = documentsAttributes(
            name = "PID",
        )
        val sort = interactor.getFilters().sort!!

        @Suppress("UNCHECKED_CAST")
        val defaultSort =
            sort.filters[0].filterableAction as FilterAction.Sort<DocumentsFilterableAttributes, String>

        // When + Then
        assertEquals(
            listOf(DocumentFilterIds.FILTER_SORT_DEFAULT),
            sort.filters.map { it.id },
        )
        assertEquals("pid", defaultSort.selector(attrs))
    }

    @Test
    fun `When the issuer filter predicate is applied, Then it matches by issuer name`() {
        // Given
        whenever(strings.get(any())).thenReturn("mocked")
        val group = interactor.getFilters().filterGroups.first {
            it.id == DocumentFilterIds.FILTER_BY_ISSUER_GROUP_ID
        }

        @Suppress("UNCHECKED_CAST")
        val action =
            (group as FilterGroup.MultipleSelectionFilterGroup<DocumentsFilterableAttributes>)
                .filterableAction
        val acmeFilter =
            FilterItem(id = "Acme", name = "Acme", selected = true, isDefault = false)
        val otherFilter =
            FilterItem(id = "Other", name = "Other", selected = true, isDefault = false)

        // When + Then
        assertTrue(action.predicate(documentsAttributes(issuer = "Acme"), acmeFilter))
        assertTrue(!action.predicate(documentsAttributes(issuer = "Other"), acmeFilter))
        assertTrue(action.predicate(documentsAttributes(issuer = "Other"), otherFilter))
    }

    @Test
    fun `When the category filter predicate is applied, Then it matches by category id`() {
        // Given
        whenever(strings.get(any())).thenReturn("mocked")
        val group = interactor.getFilters().filterGroups.first {
            it.id == DocumentFilterIds.FILTER_BY_DOCUMENT_CATEGORY_GROUP_ID
        }

        @Suppress("UNCHECKED_CAST")
        val action =
            (group as FilterGroup.MultipleSelectionFilterGroup<DocumentsFilterableAttributes>)
                .filterableAction
        val category = DocumentCategory.Government
        val matchFilter = FilterItem(
            id = category.id.toString(),
            name = "Government",
            selected = true,
            isDefault = false,
        )
        val nonMatchFilter = FilterItem(
            id = "9999",
            name = "Other",
            selected = true,
            isDefault = false,
        )

        // When + Then
        assertTrue(action.predicate(documentsAttributes(category = category), matchFilter))
        assertTrue(!action.predicate(documentsAttributes(category = category), nonMatchFilter))
    }

    @Test
    fun `When the state filter predicate is applied, Then VALID EXPIRED REVOKED and else arms all evaluate`() {
        // Given
        whenever(strings.get(any())).thenReturn("mocked")
        val group = interactor.getFilters().filterGroups.first {
            it.id == DocumentFilterIds.FILTER_BY_STATE_GROUP_ID
        }

        @Suppress("UNCHECKED_CAST")
        val action =
            (group as FilterGroup.MultipleSelectionFilterGroup<DocumentsFilterableAttributes>)
                .filterableAction
        val validFilter = FilterItem(
            id = DocumentFilterIds.FILTER_BY_STATE_VALID,
            name = "Valid",
            selected = true,
            isDefault = true,
        )
        val expiredFilter = FilterItem(
            id = DocumentFilterIds.FILTER_BY_STATE_EXPIRED,
            name = "Expired",
            selected = true,
            isDefault = false,
        )
        val revokedFilter = FilterItem(
            id = DocumentFilterIds.FILTER_BY_STATE_REVOKED,
            name = "Revoked",
            selected = true,
            isDefault = false,
        )
        val otherFilter = FilterItem(
            id = "other",
            name = "Other",
            selected = true,
            isDefault = false,
        )
        val nullExpiry = documentsAttributes(expiryDate = null, isRevoked = false)
        val expired =
            documentsAttributes(
                expiryDate = Instant.parse("2020-01-01T00:00:00Z").toKotlinInstant(),
                isRevoked = false,
            )
        val valid =
            documentsAttributes(
                expiryDate = Instant.parse("2099-01-01T00:00:00Z").toKotlinInstant(),
                isRevoked = false,
            )
        val revoked = documentsAttributes(expiryDate = null, isRevoked = true)

        // When + Then
        assertTrue(action.predicate(valid, validFilter))
        assertTrue(action.predicate(nullExpiry, validFilter))
        assertTrue(!action.predicate(revoked, validFilter))

        assertTrue(action.predicate(expired, expiredFilter))
        assertTrue(!action.predicate(valid, expiredFilter))

        assertTrue(action.predicate(revoked, revokedFilter))
        assertTrue(!action.predicate(valid, revokedFilter))

        assertTrue(action.predicate(valid, otherFilter))
    }
    //endregion

    //region addDynamicFilters default parameter
    // The interface declares `filters: Filters = Filters.emptyFilters()`. Calling without
    // the filters argument exercises the default-parameter synthetic.
    @Test
    fun `When addDynamicFilters is called without filters, Then the default emptyFilters is used`() {
        // Given
        whenever(strings.get(any())).thenReturn("mocked")
        val documents = FilterableList(items = emptyList())

        // When
        val result = interactor.addDynamicFilters(documents = documents)

        // Then
        // Default filters is Filters.emptyFilters() which has empty filterGroups.
        assertEquals(0, result.filterGroups.size)
    }
    //endregion

    //region addDynamicFilters & period predicates

    @Test
    fun `Given a documents list with issuers and categories, When addDynamicFilters is called, Then the ISSUER and CATEGORY groups are populated`() {
        // Given
        whenever(strings.get(any())).thenReturn("mocked")
        val itemAcme = FilterableItem(
            payload = stubDocumentUi(),
            attributes = documentsAttributes(
                issuer = "Acme",
                category = DocumentCategory.Government,
            ),
        )
        val itemSchool = FilterableItem(
            payload = stubDocumentUi(),
            attributes = documentsAttributes(
                issuer = "School",
                category = DocumentCategory.Education,
            ),
        )
        val documents = FilterableList(items = listOf(itemAcme, itemSchool))
        val initialFilters = interactor.getFilters()

        // When
        val result = interactor.addDynamicFilters(documents, initialFilters)

        // Then
        val issuerGroup = result.filterGroups.first {
            it.id == DocumentFilterIds.FILTER_BY_ISSUER_GROUP_ID
        }
        val categoryGroup = result.filterGroups.first {
            it.id == DocumentFilterIds.FILTER_BY_DOCUMENT_CATEGORY_GROUP_ID
        }
        assertEquals(2, issuerGroup.filters.size)
        assertEquals(2, categoryGroup.filters.size)
    }

    @Test
    @Suppress("UNCHECKED_CAST") // FilterAction.Filter<T> is erased at runtime — the test asserts the predicate type from the filter group definition.
    fun `When the period filter predicates are applied, Then default issuance and expiry-window predicates evaluate as expected`() {
        // Given
        whenever(strings.get(any())).thenReturn("mocked")
        val periodGroup = interactor.getFilters().filterGroups.first {
            it.id == DocumentFilterIds.FILTER_BY_PERIOD_GROUP_ID
        }

        val defaultPred =
            periodGroup.filters[0].filterableAction as FilterAction.Filter<DocumentsFilterableAttributes>
        val next7Pred =
            periodGroup.filters[1].filterableAction as FilterAction.Filter<DocumentsFilterableAttributes>
        val next30Pred =
            periodGroup.filters[2].filterableAction as FilterAction.Filter<DocumentsFilterableAttributes>
        val beyond30Pred =
            periodGroup.filters[3].filterableAction as FilterAction.Filter<DocumentsFilterableAttributes>
        val expiredPred =
            periodGroup.filters[4].filterableAction as FilterAction.Filter<DocumentsFilterableAttributes>

        val dummyFilter = FilterItem(
            id = "x", name = "x", selected = true, isDefault = false,
        )

        val expired = documentsAttributes(
            expiryDate = Instant.parse("2020-01-01T00:00:00Z").toKotlinInstant(),
        )
        val tomorrow = documentsAttributes(
            expiryDate = Instant.now().plus(1, ChronoUnit.DAYS).toKotlinInstant(),
        )
        val nextMonth = documentsAttributes(
            expiryDate = Instant.now().plus(20, ChronoUnit.DAYS).toKotlinInstant(),
        )
        val farFuture = documentsAttributes(
            expiryDate = Instant.now().plus(365, ChronoUnit.DAYS).toKotlinInstant(),
        )

        // When + Then
        assertTrue(defaultPred.predicate(expired, dummyFilter))
        assertTrue(next7Pred.predicate(tomorrow, dummyFilter))
        assertTrue(!next7Pred.predicate(farFuture, dummyFilter))
        assertTrue(next30Pred.predicate(nextMonth, dummyFilter))
        assertTrue(!next30Pred.predicate(farFuture, dummyFilter))
        assertTrue(beyond30Pred.predicate(farFuture, dummyFilter))
        assertTrue(!beyond30Pred.predicate(tomorrow, dummyFilter))
        assertTrue(expiredPred.predicate(expired, dummyFilter))
        assertTrue(!expiredPred.predicate(farFuture, dummyFilter))
    }

    private fun stubDocumentUi(): DocumentUi = DocumentUi(
        documentIssuanceState = DocumentIssuanceStateUi.Issued,
        uiData = ListItemDataUi(
            itemId = "any",
            mainContentData = ListItemMainContentDataUi.Text("any"),
        ),
        documentIdentifier = DocumentIdentifier.MdocPid,
        documentCategory = DocumentCategory.Government,
    )
    //endregion

    //region filterValidator delegation
    // These tests verify the simple delegation methods to filterValidator.

    @Test
    fun `When updateLists is called, Then filterValidator#updateLists is invoked`() {
        // Given
        val list = FilterableList(items = emptyList())

        // When
        interactor.updateLists(filterableList = list)

        // Then
        verify(filterValidator, times(1)).updateLists(list)
    }

    @Test
    fun `When applySearch is called, Then filterValidator#applySearch is invoked`() {
        // When
        interactor.applySearch(query = "abc")

        // Then
        verify(filterValidator, times(1)).applySearch("abc")
    }

    @Test
    fun `When revertFilters is called, Then filterValidator#revertFilters is invoked`() {
        // When
        interactor.revertFilters()

        // Then
        verify(filterValidator, times(1)).revertFilters()
    }

    @Test
    fun `When updateFilter is called, Then filterValidator#updateFilter is invoked`() {
        // When
        interactor.updateFilter(filterGroupId = "groupId", filterId = "filterId")

        // Then
        verify(filterValidator, times(1))
            .updateFilter("groupId", "filterId")
    }

    @Test
    fun `When updateSort is called, Then filterValidator#updateSort is invoked`() {
        // When
        interactor.updateSort(filterId = "sortId")

        // Then
        verify(filterValidator, times(1))
            .updateSort("sortId")
    }

    @Test
    fun `When applyFilters is called, Then filterValidator#applyFilters is invoked`() {
        // When
        interactor.applyFilters()

        // Then
        verify(filterValidator, times(1)).applyFilters()
    }

    @Test
    fun `When resetFilters is called, Then filterValidator#resetFilters is invoked`() {
        // When
        interactor.resetFilters()

        // Then
        verify(filterValidator, times(1)).resetFilters()
    }
    //endregion

    //region Mock Calls of the Dependencies
    private fun mockShowBatchIssuanceCounterPreference(response: Boolean) {
        whenever(suspend { platform.showBatchIssuanceCounter() }).thenReturn(response)
    }

    /**
     * A [WalletDocument] as the seam hands it over: already mapped, so the interactor's list-builder
     * has no wallet-core types left to see. Mirrors what `WalletEngineImpl` produces for an issued
     * document (whose own mapping is covered by `TestWalletEngineImpl` in :core-logic).
     */
    private fun issuedWalletDocument(
        id: String = "doc-1",
        name: String = "PID",
        isRevoked: Boolean = false,
        isExpired: Boolean = false,
        expiresAt: kotlin.time.Instant? = kotlin.time.Instant.parse("2099-01-01T00:00:00Z"),
        isLowOnCredentials: Boolean = false,
    ) = WalletDocument(
        id = id,
        name = name,
        formatType = mockedMdocPidDocType,
        issuanceState = WalletDocumentIssuanceState.Issued,
        issuedAt = kotlin.time.Instant.parse("2024-01-01T00:00:00Z"),
        expiresAt = expiresAt,
        isExpired = isExpired,
        isRevoked = isRevoked,
        credentialsCount = 2,
        initialCredentialsCount = 5,
        isLowOnCredentials = isLowOnCredentials,
        issuerName = "Issuer",
    )

    private suspend fun mockGetDocumentsBaseCalls(
        documents: List<WalletDocument>,
        hasMainPid: Boolean = true,
    ) {
        whenever(walletEngine.getMainPidDocument())
            .thenReturn(if (hasMainPid) WalletDocument(id = "mocked_pid_id") else null)
        whenever(walletEngine.getAllDocumentsWithDetails(anyString()))
            .thenReturn(documents)
        whenever(platform.documentCategories)
            .thenReturn(DocumentCategories(value = emptyMap()))
    }

    private fun mockGetDocumentsResourceStrings(
        genericString: String = "mocked",
        credentialsInfoText: String = "mocked-credentials-info",
        expiryMessage: String = "mocked-expiry-message",
    ) {
        whenever(platform.localeTag())
            .thenReturn(mockedDefaultLocale.toLanguageTag())
        whenever(strings.get(any()))
            .thenReturn(genericString)
        // The two argument-taking overloads are distinguished by arity, matching what the interactor
        // asks for: the credentials counter passes two Ints, the expiry message one String.
        whenever(
            strings.get(
                any(),
                any<Int>(),
                any<Int>()
            )
        ).thenReturn(credentialsInfoText)
        whenever(
            strings.get(
                any(),
                any<String>()
            )
        ).thenReturn(expiryMessage)
    }



    private fun mockOnFilterChangedEvent(response: FilterValidatorPartialState) {
        whenever(filterValidator.onFilterStateChange())
            .thenReturn(response.toFlow())
    }
    //endregion

    //region Mock domain models
    private val mockFilterableItem = FilterableItem(
        payload = DocumentUi(
            documentIssuanceState = DocumentIssuanceStateUi.Pending,
            uiData = ListItemDataUi(
                itemId = "sumo",
                mainContentData = ListItemMainContentDataUi.Text("test"),
                overlineText = null,
                supportingContentData = null,
                leadingContentData = null,
                trailingContentData = null
            ),
            documentIdentifier = DocumentIdentifier.MdocPid,
            documentCategory = DocumentCategory.Government,
        ), attributes = object : FilterableAttributes {
            override val searchTags: List<String>
                get() = listOf("docName", "issuerName")
        })

    private val mockFilters = Filters(
        filterGroups = listOf(
            FilterGroup.SingleSelectionFilterGroup(
                id = "group",
                name = "Group",
                filters = listOf(
                    FilterItem(
                        id = "f1",
                        name = "Filter1",
                        selected = true,
                        isDefault = true
                    ),
                    FilterItem(
                        id = "f2",
                        name = "Filter 2",
                        selected = false,
                        isDefault = false
                    )
                )
            )
        ), sortOrder = SortOrder.Ascending()
    )
    //endregion
}
