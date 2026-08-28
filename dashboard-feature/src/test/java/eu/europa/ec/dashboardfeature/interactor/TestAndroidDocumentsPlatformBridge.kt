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
import eu.europa.ec.corelogic.controller.DeleteDocumentPartialState
import eu.europa.ec.corelogic.controller.IssueDeferredDocumentPartialState
import eu.europa.ec.corelogic.config.WalletCoreConfig
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.model.DeferredDocumentDataDomain
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.dashboardfeature.util.mockedPendingMdlUi
import eu.europa.ec.dashboardfeature.util.mockedPendingPidUi
import eu.europa.ec.eudi.wallet.document.Document
import eu.europa.ec.eudi.wallet.document.DocumentId
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.testfeature.util.copy
import eu.europa.ec.testfeature.util.getMockedFullDocuments
import eu.europa.ec.testfeature.util.mockedExceptionWithMessage
import eu.europa.ec.testfeature.util.mockedExceptionWithNoMessage
import eu.europa.ec.testfeature.util.mockedGenericErrorMessage
import eu.europa.ec.testfeature.util.mockedPlainFailureMessage
import eu.europa.ec.testlogic.extension.runFlowTest
import eu.europa.ec.testlogic.extension.runTest
import eu.europa.ec.testlogic.extension.toFlow
import eu.europa.ec.testlogic.rule.CoroutineTestRule
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The Android [DocumentsPlatformBridge] — deferred issuance and deletion, i.e. everything about the
 * documents feature that genuinely needs wallet-core.
 *
 * These twelve cases came verbatim from `TestDocumentsInteractor` when the logic moved here, rather
 * than being replaced by assertions that the interactor delegates: what is worth testing is the
 * mapping of wallet-core's states onto the interactor's, and that mapping now lives in this class.
 */
class TestAndroidDocumentsPlatformBridge {

    @Mock
    private lateinit var resourceProvider: ResourceProvider

    @Mock
    private lateinit var walletCoreDocumentsController: WalletCoreDocumentsController

    @Mock
    private lateinit var walletCoreConfig: WalletCoreConfig

    @Mock
    private lateinit var configLogic: ConfigLogic

    @Mock
    private lateinit var prefKeys: PrefKeys

    private lateinit var bridge: DocumentsPlatformBridge

    private lateinit var closeable: AutoCloseable

    private lateinit var mockDocumentId: String

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    @Before
    fun before() {
        closeable = MockitoAnnotations.openMocks(this)

        bridge = AndroidDocumentsPlatformBridge(
            resourceProvider = resourceProvider,
            walletCoreDocumentsController = walletCoreDocumentsController,
            walletCoreConfig = walletCoreConfig,
            configLogic = configLogic,
            prefKeys = prefKeys,
        )

        whenever(resourceProvider.genericErrorMessage()).thenReturn(mockedGenericErrorMessage)
        whenever(configLogic.forcePidActivation).thenReturn(true)

        mockDocumentId = "mockDocumentId"
    }

    @After
    fun after() {
        closeable.close()
    }

    // region deleteDocument
    // Case 1:
    // walletCoreDocumentsController.getAllDocuments() returns a list of Documents
    // with a size of two.

    // Case 1 Expected Result:
    // DocumentInteractorDeleteDocumentPartialState.SingleDocumentDeleted state.
    @Test
    fun `Given Case 1, When deleteDocument is called, Then Case 1 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val mockedFullDocuments = getMockedFullDocuments()
            whenever(walletCoreDocumentsController.getAllDocuments())
                .thenReturn(mockedFullDocuments)
            assert(walletCoreDocumentsController.getAllDocuments().size == 2)
            mockDeleteDocumentCall(response = DeleteDocumentPartialState.Success)

            // When
            bridge.deleteDocument(mockDocumentId).runFlowTest {
                // Then
                val expectedFlow =
                    DocumentInteractorDeleteDocumentPartialState.SingleDocumentDeleted

                assertEquals(expectedFlow, awaitItem())
            }
        }
    }

    // Case 2:
    // walletCoreDocumentsController.getAllDocuments() returns an empty list.

    // Case 2 Expected Result:
    // DocumentInteractorDeleteDocumentPartialState.AllDocumentsDeleted state
    @Test
    fun `Given Case 2, When deleteDocument is called, Then Case 2 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val mockDocumentsList = mock<List<Document>>()
            whenever(walletCoreDocumentsController.getAllDocuments()).thenReturn(mockDocumentsList)
            whenever(mockDocumentsList.isEmpty()).thenReturn(true)
            assert(walletCoreDocumentsController.getAllDocuments().isEmpty())

            mockDeleteDocumentCall(response = DeleteDocumentPartialState.Success)

            // When
            bridge.deleteDocument(mockDocumentId).runFlowTest {
                val expectedFlow = DocumentInteractorDeleteDocumentPartialState.AllDocumentsDeleted

                // Then
                assertEquals(expectedFlow, awaitItem())
            }
        }
    }

    // Case 3:
    // walletCoreDocumentsController.getAllDocuments() returns Failure

    // Case 3 Expected Result:
    // DocumentInteractorDeleteDocumentPartialState.Failure state.
    @Test
    fun `Given Case 3, When deleteDocument is called, Then Case 3 Expected Result is returned`() =
        coroutineRule.runTest {
            // Given
            mockDeleteDocumentCall(
                response = DeleteDocumentPartialState.Failure(
                    errorMessage = mockedPlainFailureMessage
                )
            )

            // When
            bridge.deleteDocument(mockDocumentId).runFlowTest {
                // Then
                assertEquals(
                    DocumentInteractorDeleteDocumentPartialState.Failure(
                        errorMessage = mockedPlainFailureMessage
                    ),
                    awaitItem()
                )
            }
        }

    // Case 4:
    // walletCoreDocumentsController.deleteDocument() throws an exception with a message.

    // Case 4 Expected Result:
    // DocumentInteractorDeleteDocumentPartialState.Failure state with exception's localized message.
    @Test
    fun `Given Case 4, When deleteDocument is called, Then Case 4 Expected Result is returned`() =
        coroutineRule.runTest {
            // Given
            whenever(walletCoreDocumentsController.deleteDocument(mockDocumentId))
                .thenThrow(mockedExceptionWithMessage)

            // When
            bridge.deleteDocument(mockDocumentId).runFlowTest {
                // Then
                assertEquals(
                    DocumentInteractorDeleteDocumentPartialState.Failure(
                        errorMessage = mockedExceptionWithMessage.localizedMessage!!
                    ),
                    awaitItem()
                )
            }
        }

    // Case 5:
    // walletCoreDocumentsController.deleteDocument() throws an exception with no message.

    // Case 5 Expected Result:
    // DocumentInteractorDeleteDocumentPartialState.Failure state with the generic error message.
    @Test
    fun `Given Case 5, When deleteDocument is called, Then Case 5 Expected Result is returned`() =
        coroutineRule.runTest {
            // Given
            whenever(walletCoreDocumentsController.deleteDocument(mockDocumentId))
                .thenThrow(mockedExceptionWithNoMessage)

            // When
            bridge.deleteDocument(mockDocumentId).runFlowTest {
                // Then
                assertEquals(
                    DocumentInteractorDeleteDocumentPartialState.Failure(
                        errorMessage = mockedGenericErrorMessage
                    ),
                    awaitItem()
                )
            }
        }
    //endregion

    //region tryIssuingDeferredDocumentsFlow

    // Case 1:
    // When issueDeferredDocument was called:
    // 1. IssueDeferredDocumentPartialState.Issued was emitted, with
    //  - successData, the successfully issued deferred document's DeferredDocumentData, and also,
    // 2. IssueDeferredDocumentPartialState.Failed was emitted, with
    //  - documentId, the failed deferred document's DocumentId.

    // Case 1 Expected Result:
    // DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result state, with
    //  - successfullyIssuedDeferredDocuments: a list with the successfully issued deferred document's DeferredDocumentData,
    //  - failedIssuedDeferredDocuments: a list with the failed deferred document's DocumentId.
    @Test
    fun `Given Case 1, When tryIssuingDeferredDocumentsFlow is called, Then Case 1 Expected Result is returned`() =
        coroutineRule.runTest {
            // Given
            val mockDeferredPendingDocId1 = mockedPendingPidUi.documentId
            val mockDeferredPendingType1 = mockedPendingPidUi.documentIdentifier.formatType
            val mockDeferredPendingName1 = mockedPendingPidUi.documentName

            val mockDeferredPendingDocId2 = mockedPendingMdlUi.documentId
            val mockDeferredPendingType2 = mockedPendingMdlUi.documentIdentifier.formatType

            val deferredDocuments: Map<DocumentId, FormatType> = mapOf(
                mockDeferredPendingDocId1 to mockDeferredPendingType1,
                mockDeferredPendingDocId2 to mockDeferredPendingType2
            )
            val successData = DeferredDocumentDataDomain(
                documentId = mockDeferredPendingDocId1,
                formatType = mockDeferredPendingType1,
                docName = mockDeferredPendingName1
            )

            mockIssueDeferredDocumentCall(
                docId = mockDeferredPendingDocId1,
                response = IssueDeferredDocumentPartialState.Issued(
                    deferredDocumentData = successData
                )
            )
            mockIssueDeferredDocumentCall(
                docId = mockDeferredPendingDocId2,
                response = IssueDeferredDocumentPartialState.Failed(
                    documentId = mockDeferredPendingDocId2,
                    errorMessage = mockedPlainFailureMessage
                )
            )

            // When
            bridge.tryIssuingDeferredDocuments(deferredDocuments)
                .runFlowTest {
                    // Then
                    val expectedResult =
                        DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result(
                            successfullyIssuedDeferredDocuments = listOf(successData),
                            failedIssuedDeferredDocuments = listOf(mockDeferredPendingDocId2)
                        )
                    assertEquals(expectedResult, awaitItem())
                }
        }

    // Case 2:
    // IssueDeferredDocumentPartialState.Expired was emitted when issueDeferredDocument was called.

    // Case 2 Expected Result:
    // DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result with,
    // - successfullyIssuedDeferredDocuments = emptyList.
    // - failedIssuedDeferredDocuments = emptyList.
    @Test
    fun `Given Case 2, When tryIssuingDeferredDocumentsFlow is called, Then Case 2 Expected Result is returned`() =
        coroutineRule.runTest {
            // Given
            val mockDeferredExpiredDocId = mockedPendingPidUi.documentId
            val mockDeferredExpiredDocType = mockedPendingPidUi.documentIdentifier.formatType

            val deferredDocuments: Map<DocumentId, FormatType> = mapOf(
                mockDeferredExpiredDocId to mockDeferredExpiredDocType
            )

            mockIssueDeferredDocumentCall(
                docId = mockDeferredExpiredDocId,
                response = IssueDeferredDocumentPartialState.Expired(
                    documentId = mockDeferredExpiredDocId
                )
            )

            // When
            bridge.tryIssuingDeferredDocuments(deferredDocuments)
                .runFlowTest {
                    // Then
                    val expectedResult =
                        DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result(
                            successfullyIssuedDeferredDocuments = emptyList(),
                            failedIssuedDeferredDocuments = emptyList()
                        )
                    assertEquals(expectedResult, awaitItem())
                }
        }

    // Case 3:
    // IssueDeferredDocumentPartialState.NotReady was emitted when issueDeferredDocument was called.

    // Case 3 Expected Result:
    // DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result with,
    // - successfullyIssuedDeferredDocuments = emptyList.
    // - failedIssuedDeferredDocuments = emptyList.
    @Test
    fun `Given Case 3, When tryIssuingDeferredDocumentsFlow is called, Then Case 3 Expected Result is returned`() =
        coroutineRule.runTest {
            val mockDeferredPendingDocId = mockedPendingPidUi.documentId
            val mockDeferredPendingType = mockedPendingPidUi.documentIdentifier.formatType
            val mockDeferredPendingName = mockedPendingPidUi.documentName

            val deferredDocuments: Map<DocumentId, FormatType> = mapOf(
                mockDeferredPendingDocId to mockDeferredPendingType
            )
            val successData = DeferredDocumentDataDomain(
                documentId = mockDeferredPendingDocId,
                formatType = mockDeferredPendingType,
                docName = mockDeferredPendingName
            )

            mockIssueDeferredDocumentCall(
                docId = mockDeferredPendingDocId,
                response = IssueDeferredDocumentPartialState.NotReady(
                    deferredDocumentData = successData
                )
            )

            // When
            bridge.tryIssuingDeferredDocuments(deferredDocuments).runFlowTest {
                // Then
                val expectedResult =
                    DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result(
                        successfullyIssuedDeferredDocuments = emptyList(),
                        failedIssuedDeferredDocuments = emptyList()
                    )
                assertEquals(expectedResult, awaitItem())
            }
        }

    // Case 4:
    // walletCoreDocumentsController.issueDeferredDocument() throws an exception with a message.

    // Case 4 Expected Result:
    // DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Failure state with exception's localized message.
    @Test
    fun `Given Case 4, When tryIssuingDeferredDocumentsFlow is called, Then Case 4 Expected Result is returned`() =
        coroutineRule.runTest {
            // Given
            val mockDeferredPendingDocId = mockedPendingPidUi.documentId
            val mockDeferredPendingType = mockedPendingPidUi.documentIdentifier.formatType

            val deferredDocuments: Map<DocumentId, FormatType> = mapOf(
                mockDeferredPendingDocId to mockDeferredPendingType
            )
            whenever(walletCoreDocumentsController.issueDeferredDocument(mockDeferredPendingDocId))
                .thenThrow(mockedExceptionWithMessage)

            // When
            bridge.tryIssuingDeferredDocuments(deferredDocuments).runFlowTest {
                // Then
                assertEquals(
                    DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Failure(
                        errorMessage = mockedExceptionWithMessage.localizedMessage!!
                    ),
                    awaitItem()
                )
            }
        }

    // Case 5:
    // walletCoreDocumentsController.issueDeferredDocument() throws an exception with no message.

    // Case 5 Expected Result:
    // DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Failure state with the generic error message.
    @Test
    fun `Given Case 5, When tryIssuingDeferredDocumentsFlow is called, Then Case 5 Expected Result is returned`() =
        coroutineRule.runTest {
            // Given
            val mockDeferredPendingDocId = mockedPendingPidUi.documentId
            val mockDeferredPendingType = mockedPendingPidUi.documentIdentifier.formatType

            val deferredDocuments: Map<DocumentId, FormatType> = mapOf(
                mockDeferredPendingDocId to mockDeferredPendingType
            )
            whenever(walletCoreDocumentsController.issueDeferredDocument(mockDeferredPendingDocId))
                .thenThrow(mockedExceptionWithNoMessage)

            // When
            bridge.tryIssuingDeferredDocuments(deferredDocuments).runFlowTest {
                // Then
                assertEquals(
                    DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Failure(
                        errorMessage = mockedGenericErrorMessage
                    ),
                    awaitItem()
                )
            }
        }

    // Case 6:
    // emptyFlow was returned when issueDeferredDocument was called.

    // Case 6 Expected Result:
    // DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result state.
    @Test
    fun `Given Case 6, When tryIssuingDeferredDocumentsFlow is called, Then Case 6 Expected Result is returned`() =
        coroutineRule.runTest {
            // Given
            val mockDeferredPendingDocId = mockedPendingPidUi.documentId
            val mockDeferredPendingType = mockedPendingPidUi.documentIdentifier.formatType

            val deferredDocuments: Map<DocumentId, FormatType> = mapOf(
                mockDeferredPendingDocId to mockDeferredPendingType
            )
            whenever(walletCoreDocumentsController.issueDeferredDocument(mockDeferredPendingDocId))
                .thenReturn(emptyFlow())

            // When
            bridge.tryIssuingDeferredDocuments(deferredDocuments).runFlowTest {
                // Then
                val expectedResult =
                    DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result(
                        successfullyIssuedDeferredDocuments = emptyList(),
                        failedIssuedDeferredDocuments = listOf(mockDeferredPendingDocId)
                    )
                assertEquals(expectedResult, awaitItem())
            }
        }

    // A trust-caused deferred failure (IssueDeferredDocumentPartialState.IssuerNotTrusted) is
    // terminal: the pending document is deleted (so the polling loop stops retrying it) and it
    // is never reported as a failed deferred document.
    @Test
    fun `Given a deferred document from an untrusted issuer, When tryIssuingDeferredDocumentsFlow is called, Then the document is deleted and not reported as failed`() =
        coroutineRule.runTest {
            // Given
            val mockDeferredUntrustedDocId = mockedPendingPidUi.documentId
            val mockDeferredUntrustedType = mockedPendingPidUi.documentIdentifier.formatType

            val deferredDocuments: Map<DocumentId, FormatType> = mapOf(
                mockDeferredUntrustedDocId to mockDeferredUntrustedType
            )

            mockIssueDeferredDocumentCall(
                docId = mockDeferredUntrustedDocId,
                response = IssueDeferredDocumentPartialState.IssuerNotTrusted(
                    documentId = mockDeferredUntrustedDocId
                )
            )
            // A completing flow — the shared toFlow()/StateFlow stub never completes, which would
            // hang the SUT's deleteDocument(...).lastOrNull().
            whenever(walletCoreDocumentsController.deleteDocument(mockDeferredUntrustedDocId))
                .thenReturn(flowOf(DeleteDocumentPartialState.Success))

            // When
            bridge.tryIssuingDeferredDocuments(deferredDocuments)
                .runFlowTest {
                    // Then
                    val expectedResult =
                        DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result(
                            successfullyIssuedDeferredDocuments = emptyList(),
                            failedIssuedDeferredDocuments = emptyList()
                        )
                    assertEquals(expectedResult, awaitItem())
                    verify(walletCoreDocumentsController)
                        .deleteDocument(mockDeferredUntrustedDocId)
                }
        }

    private fun mockDeleteDocumentCall(response: DeleteDocumentPartialState) {
        whenever(walletCoreDocumentsController.deleteDocument(anyString()))
            .thenReturn(response.toFlow())
    }

    private fun mockIssueDeferredDocumentCall(
        docId: DocumentId,
        response: IssueDeferredDocumentPartialState,
    ) {
        whenever(walletCoreDocumentsController.issueDeferredDocument(docId))
            .thenReturn(response.toFlow())
    }
}
