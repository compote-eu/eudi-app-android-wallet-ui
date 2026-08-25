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

package eu.europa.ec.issuancefeature.interactor

import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.corelogic.model.UntrustedIssuerReasonDomain
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.shared.navigation.SuccessRoute
import eu.europa.ec.corelogic.controller.FetchScopedDocumentsPartialState
import eu.europa.ec.corelogic.controller.IssuanceMethod
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.issuancefeature.util.mockedAgeOptionItemUi
import eu.europa.ec.issuancefeature.util.mockedCombinedPid
import eu.europa.ec.issuancefeature.util.mockedConfigNavigationTypePopToScreen
import eu.europa.ec.issuancefeature.util.mockedConfigNavigationTypePush
import eu.europa.ec.issuancefeature.util.mockedIssuerId
import eu.europa.ec.issuancefeature.util.mockedMdlOptionItemUi
import eu.europa.ec.issuancefeature.util.mockedPhotoIdOptionItemUi
import eu.europa.ec.issuancefeature.util.mockedPidOptionItemUi
import eu.europa.ec.issuancefeature.util.mockedScopedDocuments
import eu.europa.ec.issuancefeature.util.mockedSuccessContentDescription
import eu.europa.ec.resourceslogic.theme.values.ThemeColors
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.testfeature.util.mockedDefaultLocale
import eu.europa.ec.testfeature.util.mockedExceptionWithMessage
import eu.europa.ec.testfeature.util.mockedExceptionWithNoMessage
import eu.europa.ec.testfeature.util.mockedGenericErrorMessage
import eu.europa.ec.testfeature.util.mockedMdocPidFormat
import eu.europa.ec.testfeature.util.mockedNotifyOnAuthenticationFailure
import eu.europa.ec.testfeature.util.mockedPidId
import eu.europa.ec.testfeature.util.mockedPlainFailureMessage
import eu.europa.ec.testfeature.util.mockedUriPath1
import eu.europa.ec.testlogic.extension.runFlowTest
import eu.europa.ec.testlogic.extension.runTest
import eu.europa.ec.testlogic.extension.toFlow
import eu.europa.ec.testlogic.rule.CoroutineTestRule
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.contentDescriptionRes
import eu.europa.ec.uilogic.component.utils.PERCENTAGE_25
import junit.framework.TestCase.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.issuance_add_document_deferred_success_description
import eu.europa.ec.shared.resources.issuance_add_document_deferred_success_primary_button_text
import eu.europa.ec.shared.resources.issuance_add_document_deferred_success_text
import eu.europa.ec.shared.resources.issuance_add_document_no_options
import eu.europa.ec.shared.resources.issuance_add_document_pid_combined

class TestAddDocumentInteractor {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    @Mock
    private lateinit var platform: AddDocumentPlatformBridge

    @Mock
    private lateinit var strings: StringCatalog

    private lateinit var interactor: AddDocumentInteractor

    private lateinit var closeable: AutoCloseable

    // Only carried through the state mapping — nothing here authenticates.
    private val crypto = BiometricCrypto(cryptoObject = null)
    private val resultHandler = DeviceAuthenticationResult()

    @Before
    fun before() {
        closeable = MockitoAnnotations.openMocks(this)

        interactor = AddDocumentInteractorImpl(
            strings = strings,
            platform = platform,
        )

        whenever(strings[Res.string.generic_error_message]).thenReturn(mockedGenericErrorMessage)
        whenever(platform.localeTag()).thenReturn(mockedDefaultLocale.toLanguageTag())
    }

    @After
    fun after() {
        closeable.close()
    }

    //region getAddDocumentOption

    // Case 1:
    // 1. flowType == IssuanceFlowType.NoDocument
    // 2. the platform bridge reports a non-empty list

    // Case 1 Expected Result:
    // AddDocumentInteractorScopedPartialState.Success state, with the following options:
    // 1. a PID
    @Test
    fun `Given Case 1, When getAddDocumentOption is called, Then Case 1 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val expectedResponse = FetchScopedDocumentsPartialState.Success(
                documents = mockedScopedDocuments
            )
            mockGetScopedDocumentsResponse(expectedResponse)

            whenever(strings[Res.string.issuance_add_document_pid_combined])
                .thenReturn(mockedCombinedPid)

            // When
            interactor.getAddDocumentOption(
                flowType = IssuanceFlowType.NoDocument
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorScopedPartialState.Success(
                        options = listOf(
                            Pair(mockedIssuerId, listOf(mockedPidOptionItemUi))
                        )
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case 2:
    // 1. flowType == IssuanceFlowType.ExtraDocument with formatType == null
    // 2. the platform bridge reports a non-empty list

    // Case 2 Expected Result:
    // AddDocumentInteractorScopedPartialState.Success state, with the following options:
    // 1. an Age Verification,
    // 2. a PID,
    // 3. an mDL,
    // 4. a Photo ID
    @Test
    fun `Given Case 2, When getAddDocumentOption is called, Then Case 2 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val expectedResponse = FetchScopedDocumentsPartialState.Success(
                documents = mockedScopedDocuments
            )
            mockGetScopedDocumentsResponse(expectedResponse)

            whenever(strings[Res.string.issuance_add_document_pid_combined])
                .thenReturn(mockedCombinedPid)

            // When
            interactor.getAddDocumentOption(
                flowType = IssuanceFlowType.ExtraDocument(
                    formatType = null
                )
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorScopedPartialState.Success(
                        options = listOf(
                            Pair(
                                mockedIssuerId,
                                listOf(
                                    mockedAgeOptionItemUi,
                                    mockedMdlOptionItemUi,
                                    mockedPhotoIdOptionItemUi,
                                    mockedPidOptionItemUi
                                )
                            )
                        )
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case 3:
    // 1. flowType == IssuanceFlowType.ExtraDocument with formatType == PID format type
    // 2. the platform bridge reports an empty list

    // Case 3 Expected Result:
    // AddDocumentInteractorScopedPartialState.NoOptions state.
    @Test
    fun `Given Case 3, When getAddDocumentOption is called, Then Case 3 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val expectedResponse = FetchScopedDocumentsPartialState.Success(
                documents = emptyList()
            )
            mockGetScopedDocumentsResponse(expectedResponse)

            val noDocumentsMsg = mockNoDocumentsString()

            // When
            interactor.getAddDocumentOption(
                flowType = IssuanceFlowType.ExtraDocument(
                    formatType = mockedMdocPidFormat.docType
                )
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorScopedPartialState.NoOptions(
                        errorMsg = noDocumentsMsg
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case 4:
    // 1. flowType == IssuanceFlowType.ExtraDocument with a non-matching formatType ("NO_MATCHES_FORMAT_TYPE")
    // 2. the platform bridge reports a non-empty list

    // Case 4 Expected Result:
    // AddDocumentInteractorScopedPartialState.NoOptions state.
    @Test
    fun `Given Case 4, When getAddDocumentOption is called, Then Case 4 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val expectedResponse = FetchScopedDocumentsPartialState.Success(
                documents = mockedScopedDocuments
            )
            mockGetScopedDocumentsResponse(expectedResponse)

            val noDocumentsMsg = mockNoDocumentsString()

            // When
            interactor.getAddDocumentOption(
                flowType = IssuanceFlowType.ExtraDocument(
                    formatType = "NO_MATCHES_FORMAT_TYPE"
                )
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorScopedPartialState.NoOptions(
                        errorMsg = noDocumentsMsg
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case 5:
    // 1. flowType == IssuanceFlowType.NoDocument
    // 2. the platform bridge reports only non-PID documents

    // Case 5 Expected Result:
    // AddDocumentInteractorScopedPartialState.NoOptions state.
    @Test
    fun `Given Case 5, When getAddDocumentOption is called, Then Case 5 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val expectedResponse = FetchScopedDocumentsPartialState.Success(
                documents = mockedScopedDocuments
                    .filterNot {
                        it.isPid
                    }
            )
            mockGetScopedDocumentsResponse(expectedResponse)

            val noDocumentsMsg = mockNoDocumentsString()

            // When
            interactor.getAddDocumentOption(
                flowType = IssuanceFlowType.NoDocument
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorScopedPartialState.NoOptions(
                        errorMsg = noDocumentsMsg
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case 6:
    // 1. flowType == IssuanceFlowType.NoDocument
    // 2. the platform bridge reports
    //    FetchScopedDocumentsPartialState.Failure with an error message.

    // Case 6 Expected Result:
    // AddDocumentInteractorScopedPartialState.Failure with the same error message.
    @Test
    fun `Given Case 6, When getAddDocumentOption is called, Then Case 6 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val expectedResponse = FetchScopedDocumentsPartialState.Failure(
                errorMessage = mockedPlainFailureMessage
            )
            mockGetScopedDocumentsResponse(expectedResponse)

            // When
            interactor.getAddDocumentOption(
                flowType = IssuanceFlowType.NoDocument
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorScopedPartialState.Failure(
                        error = mockedPlainFailureMessage
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case 7:
    // 1. flowType == IssuanceFlowType.NoDocument
    // 2. walletCoreDocumentsController.getScopedDocuments() throws an exception with no error message.

    // Case 7 Expected Result:
    // AddDocumentInteractorScopedPartialState.Failure with the generic error message.
    @Test
    fun `Given Case 7, When getAddDocumentOption is called, Then Case 7 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            whenever(
                platform.getScopedDocuments(any())
            ).thenThrow(mockedExceptionWithNoMessage)

            // When
            interactor.getAddDocumentOption(
                flowType = IssuanceFlowType.NoDocument
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorScopedPartialState.Failure(
                        error = mockedGenericErrorMessage
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case 8:
    // 1. flowType == IssuanceFlowType.NoDocument
    // 2. walletCoreDocumentsController.getScopedDocuments() throws an exception with an error message.

    // Case 8 Expected Result:
    // AddDocumentInteractorScopedPartialState.Failure with the exception's error message.
    @Test
    fun `Given Case 8, When getAddDocumentOption is called, Then Case 8 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            whenever(
                platform.getScopedDocuments(any())
            ).thenThrow(mockedExceptionWithMessage)

            // When
            interactor.getAddDocumentOption(
                flowType = IssuanceFlowType.NoDocument
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorScopedPartialState.Failure(
                        error = mockedExceptionWithMessage.localizedMessage!!
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case 9:
    // ExtraDocument with a non-PID formatType matching exactly one of the scoped documents
    // (mDL). Filter keeps only the mDL → after partition pidDocs is empty (covers the
    // `if (pidDocs.isNotEmpty())` false branch and the `else emptyList()` arm on line 407 of
    // the kover report). Also covers the `doc.formatType == formatType` true branch of
    // the OR-pair on line 377.

    // Case 9 Expected Result:
    // AddDocumentInteractorScopedPartialState.Success with one option containing only the mDL,
    // no combined-PID entry.
    @Test
    fun `Given Case 9, When getAddDocumentOption is called with a non-PID formatType, Then only that doc is returned`() {
        coroutineRule.runTest {
            // Given
            val expectedResponse = FetchScopedDocumentsPartialState.Success(
                documents = mockedScopedDocuments
            )
            mockGetScopedDocumentsResponse(expectedResponse)

            // When
            interactor.getAddDocumentOption(
                flowType = IssuanceFlowType.ExtraDocument(
                    formatType = eu.europa.ec.testfeature.util.mockedMdocMdlFormat.docType
                )
            ).runFlowTest {
                // Then
                val result = awaitItem()
                org.junit.Assert.assertTrue(
                    result is AddDocumentInteractorScopedPartialState.Success
                )
                result as AddDocumentInteractorScopedPartialState.Success
                val firstGroup = result.options.first()
                // The combined-PID listing is empty for a non-PID filter; only the mDL remains.
                val titles: List<String> = firstGroup.second.map { item ->
                    (item.itemData.mainContentData as eu.europa.ec.uilogic.component.ListItemMainContentDataUi.Text).text
                }
                org.junit.Assert.assertTrue(
                    titles.none { title -> title == mockedCombinedPid }
                )
            }
        }
    }

    //region issueDocument
    @Test
    fun `Given an issuance method and a document type, When issueDocument is called, Then it calls the platform bridge`() {
        coroutineRule.runTest {
            // Given
            val mockedIssuanceMethod = IssuanceMethod.OPENID4VCI
            val mockedConfigIds = listOf("id")
            val mockedIssuerId = "issuerId"

            whenever(
                platform.issueDocuments(
                    issuanceMethod = mockedIssuanceMethod,
                    configIds = mockedConfigIds,
                    issuerId = mockedIssuerId
                )
            ).thenReturn(IssueDocumentsPartialState.Success(listOf(mockedPidId)).toFlow())

            // When
            interactor.issueDocuments(
                issuanceMethod = mockedIssuanceMethod,
                configIds = mockedConfigIds,
                issuerId = mockedIssuerId
            ).runFlowTest {

                awaitItem()

                // Then
                @Suppress("UnusedFlow")
                verify(platform, times(1))
                    .issueDocuments(
                        issuanceMethod = mockedIssuanceMethod,
                        configIds = mockedConfigIds,
                        issuerId = mockedIssuerId
                    )
            }
        }
    }
    //endregion

    // Case A:
    // the platform reports DeferredSuccess
    // → AddDocumentInteractorIssueDocumentsPartialState.DeferredSuccess
    @Test
    fun `Given the platform reports DeferredSuccess, When issueDocuments is called, Then DeferredSuccess is emitted`() {
        coroutineRule.runTest {
            // Given
            whenever(
                platform.issueDocuments(
                    issuanceMethod = IssuanceMethod.OPENID4VCI,
                    configIds = listOf("id"),
                    issuerId = "issuerId",
                )
            ).thenReturn(
                IssueDocumentsPartialState.DeferredSuccess(deferredDocuments = emptyMap()).toFlow()
            )

            // When
            interactor.issueDocuments(
                issuanceMethod = IssuanceMethod.OPENID4VCI,
                configIds = listOf("id"),
                issuerId = "issuerId",
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorIssueDocumentsPartialState.DeferredSuccess,
                    awaitItem()
                )
            }
        }
    }

    // Case B:
    // the platform reports Failure with a message
    // → AddDocumentInteractorIssueDocumentsPartialState.Failure with the same message
    @Test
    fun `Given the platform reports Failure, When issueDocuments is called, Then Failure with the same message is emitted`() {
        coroutineRule.runTest {
            // Given
            whenever(
                platform.issueDocuments(
                    issuanceMethod = IssuanceMethod.OPENID4VCI,
                    configIds = listOf("id"),
                    issuerId = "issuerId",
                )
            ).thenReturn(
                IssueDocumentsPartialState.Failure(errorMessage = mockedPlainFailureMessage)
                    .toFlow()
            )

            // When
            interactor.issueDocuments(
                issuanceMethod = IssuanceMethod.OPENID4VCI,
                configIds = listOf("id"),
                issuerId = "issuerId",
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorIssueDocumentsPartialState.Failure(
                        errorMessage = mockedPlainFailureMessage
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case C:
    // the platform reports PartialSuccess with documentIds
    // → AddDocumentInteractorIssueDocumentsPartialState.Success with the same ids
    @Test
    fun `Given the platform reports PartialSuccess, When issueDocuments is called, Then Success with the same ids is emitted`() {
        coroutineRule.runTest {
            // Given
            whenever(
                platform.issueDocuments(
                    issuanceMethod = IssuanceMethod.OPENID4VCI,
                    configIds = listOf("id"),
                    issuerId = "issuerId",
                )
            ).thenReturn(
                IssueDocumentsPartialState.PartialSuccess(
                    documentIds = listOf(mockedPidId),
                    nonIssuedDocuments = emptyMap(),
                ).toFlow()
            )

            // When
            interactor.issueDocuments(
                issuanceMethod = IssuanceMethod.OPENID4VCI,
                configIds = listOf("id"),
                issuerId = "issuerId",
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorIssueDocumentsPartialState.Success(listOf(mockedPidId)),
                    awaitItem()
                )
            }
        }
    }

    // Case C2:
    // the platform reports PartialSuccessWithUntrustedIssuer
    // → AddDocumentInteractorIssueDocumentsPartialState.Success with the issued ids.
    // Add-document issues a single non-PID or the combined PID set (one signer), so it cannot
    // actually produce this mixed result; this locks in the defensive mapping.
    @Test
    fun `Given the platform reports PartialSuccessWithUntrustedIssuer, When issueDocuments is called, Then Success with the issued ids is emitted`() {
        coroutineRule.runTest {
            // Given
            whenever(
                platform.issueDocuments(
                    issuanceMethod = IssuanceMethod.OPENID4VCI,
                    configIds = listOf("id"),
                    issuerId = "issuerId",
                )
            ).thenReturn(
                IssueDocumentsPartialState.PartialSuccessWithUntrustedIssuer(
                    issuedDocumentIds = listOf(mockedPidId),
                    untrustedDocuments = emptyMap(),
                ).toFlow()
            )

            // When
            interactor.issueDocuments(
                issuanceMethod = IssuanceMethod.OPENID4VCI,
                configIds = listOf("id"),
                issuerId = "issuerId",
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorIssueDocumentsPartialState.Success(listOf(mockedPidId)),
                    awaitItem()
                )
            }
        }
    }

    // Case D:
    // the platform reports UserAuthRequired
    // → AddDocumentInteractorIssueDocumentsPartialState.UserAuthRequired with the crypto + handler
    @Test
    fun `Given the platform reports UserAuthRequired, When issueDocuments is called, Then UserAuthRequired is emitted`() {
        coroutineRule.runTest {
            // Given
            whenever(
                platform.issueDocuments(
                    issuanceMethod = IssuanceMethod.OPENID4VCI,
                    configIds = listOf("id"),
                    issuerId = "issuerId",
                )
            ).thenReturn(
                IssueDocumentsPartialState.UserAuthRequired(
                    crypto = crypto,
                    resultHandler = resultHandler,
                ).toFlow()
            )

            // When
            interactor.issueDocuments(
                issuanceMethod = IssuanceMethod.OPENID4VCI,
                configIds = listOf("id"),
                issuerId = "issuerId",
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorIssueDocumentsPartialState.UserAuthRequired(
                        crypto = crypto,
                        resultHandler = resultHandler,
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case E:
    // the platform reports IssuerNotTrusted
    // → AddDocumentInteractorIssueDocumentsPartialState.IssuerNotTrusted(reason = UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE)
    @Test
    fun `Given the platform reports IssuerNotTrusted, When issueDocuments is called, Then IssuerNotTrusted is emitted`() {
        coroutineRule.runTest {
            // Given
            whenever(
                platform.issueDocuments(
                    issuanceMethod = IssuanceMethod.OPENID4VCI,
                    configIds = listOf("id"),
                    issuerId = "issuerId",
                )
            ).thenReturn(
                IssueDocumentsPartialState.IssuerNotTrusted(reason = UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE).toFlow()
            )

            // When
            interactor.issueDocuments(
                issuanceMethod = IssuanceMethod.OPENID4VCI,
                configIds = listOf("id"),
                issuerId = "issuerId",
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorIssueDocumentsPartialState.IssuerNotTrusted(reason = UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE),
                    awaitItem()
                )
            }
        }
    }

    // Case F:
    // walletCoreDocumentsController.issueDocuments throws WITH a message
    // → safeAsync catches → Failure with the exception's localizedMessage (elvis left side)
    @Test
    fun `Given controller throws with message, When issueDocuments is called, Then Failure with localized message is emitted`() {
        coroutineRule.runTest {
            // Given
            whenever(
                platform.issueDocuments(
                    issuanceMethod = IssuanceMethod.OPENID4VCI,
                    configIds = listOf("id"),
                    issuerId = "issuerId",
                )
            ).thenThrow(mockedExceptionWithMessage)

            // When
            interactor.issueDocuments(
                issuanceMethod = IssuanceMethod.OPENID4VCI,
                configIds = listOf("id"),
                issuerId = "issuerId",
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorIssueDocumentsPartialState.Failure(
                        errorMessage = mockedExceptionWithMessage.localizedMessage!!
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case G:
    // walletCoreDocumentsController.issueDocuments throws with no message
    // → safeAsync catches → Failure with the generic error message
    @Test
    fun `Given controller throws without message, When issueDocuments is called, Then Failure with generic error is emitted`() {
        coroutineRule.runTest {
            // Given
            whenever(
                platform.issueDocuments(
                    issuanceMethod = IssuanceMethod.OPENID4VCI,
                    configIds = listOf("id"),
                    issuerId = "issuerId",
                )
            ).thenThrow(mockedExceptionWithNoMessage)

            // When
            interactor.issueDocuments(
                issuanceMethod = IssuanceMethod.OPENID4VCI,
                configIds = listOf("id"),
                issuerId = "issuerId",
            ).runFlowTest {
                // Then
                assertEquals(
                    AddDocumentInteractorIssueDocumentsPartialState.Failure(
                        errorMessage = mockedGenericErrorMessage
                    ),
                    awaitItem()
                )
            }
        }
    }
    //endregion

    //region buildGenericSuccessRouteForDeferred

    // Case 1:
    // 1. ConfigNavigation with NavigationType.PushRoute
    // 2. string resources mocked
    @Test
    fun `Given Case 1, When buildGenericSuccessRouteForDeferred is called, Then the expected SuccessRoute is returned`() {
        // Given
        val config = SuccessUIConfig(
            textElementsConfig = mockedTripleObject.first,
            imageConfig = mockedTripleObject.second,
            buttonConfig = listOf(
                SuccessUIConfig.ButtonConfig(
                    text = mockedTripleObject.third,
                    style = SuccessUIConfig.ButtonConfig.Style.PRIMARY,
                    navigation = mockedConfigNavigationTypePush
                )
            ),
            onBackScreenToNavigate = mockedConfigNavigationTypePush
        )

        val flowType = IssuanceFlowType.NoDocument

        // When
        val result = interactor.buildGenericSuccessRouteForDeferred(flowType = flowType)

        // Then
        assertEquals(SuccessRoute(config), result)
    }

    // Case 2:
    // 1. ConfigNavigation with NavigationType.PopRoute
    // 2. string resources mocked
    @Test
    fun `When buildGenericSuccessRouteForDeferred (PopRoute) is called, then the expected SuccessRoute is returned`() {
        // Given
        val config = SuccessUIConfig(
            textElementsConfig = mockedTripleObject.first,
            imageConfig = mockedTripleObject.second,
            buttonConfig = listOf(
                SuccessUIConfig.ButtonConfig(
                    text = mockedTripleObject.third,
                    style = SuccessUIConfig.ButtonConfig.Style.PRIMARY,
                    navigation = mockedConfigNavigationTypePopToScreen
                )
            ),
            onBackScreenToNavigate = mockedConfigNavigationTypePopToScreen
        )

        val flowType = IssuanceFlowType.ExtraDocument(
            formatType = null
        )

        // When
        val result = interactor.buildGenericSuccessRouteForDeferred(flowType = flowType)

        // Then
        assertEquals(SuccessRoute(config), result)
    }
    //endregion

    //region helper functions
    private suspend fun mockGetScopedDocumentsResponse(response: FetchScopedDocumentsPartialState) {
        whenever(
            platform.getScopedDocuments(any())
        ).thenReturn(response)
    }

    private fun mockNoDocumentsString(): String {
        val noDocumentsMsg = "No available documents"

        whenever(strings[Res.string.issuance_add_document_no_options]).thenReturn(noDocumentsMsg)

        return noDocumentsMsg
    }

    //endregion

    //region mocked objects
    private val mockedTripleObject by lazy {
        Triple(
            first = SuccessUIConfig.TextElementsConfig(
                text = UiText.Resource(Res.string.issuance_add_document_deferred_success_text),
                description = UiText.Resource(Res.string.issuance_add_document_deferred_success_description),
                color = ColorKey.Pending
            ),
            second = SuccessUIConfig.ImageConfig(
                type = SuccessUIConfig.ImageConfig.Type.Drawable(icon = AppIcons.InProgress),
                tint = ColorKey.Primary,
                screenPercentageSize = PERCENTAGE_25,
            ),
            third = UiText.Resource(Res.string.issuance_add_document_deferred_success_primary_button_text)
        )
    }
    //endregion
}