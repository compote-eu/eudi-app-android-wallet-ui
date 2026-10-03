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

import eu.europa.ec.businesslogic.util.FULL_DATETIME_PATTERN
import eu.europa.ec.businesslogic.util.formatLocalDateTime
import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.ClaimRefDomain
import eu.europa.ec.corelogic.model.CredentialClaimsDomain
import eu.europa.ec.corelogic.model.CredentialRefDomain
import eu.europa.ec.corelogic.model.DpaContactDomain
import eu.europa.ec.corelogic.model.InteractingPartyDomain
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsBodyUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsFieldUi
import eu.europa.ec.dashboardfeature.util.mockedDataDeletionLogDomain
import eu.europa.ec.dashboardfeature.util.mockedDeletionLogDomain
import eu.europa.ec.dashboardfeature.util.mockedDetailedPresentationLogDomain
import eu.europa.ec.dashboardfeature.util.mockedDpaReportLogDomain
import eu.europa.ec.dashboardfeature.util.mockedIssuanceDetails
import eu.europa.ec.dashboardfeature.util.mockedIssuanceLogDomain
import eu.europa.ec.dashboardfeature.util.mockedNestedTransactionClaims
import eu.europa.ec.dashboardfeature.util.mockedNotCompletedTransactionLogDomains
import eu.europa.ec.dashboardfeature.util.mockedOtherTransactionCredential
import eu.europa.ec.dashboardfeature.util.mockedPresentationLogDomain
import eu.europa.ec.dashboardfeature.util.mockedReissuanceLogDomain
import eu.europa.ec.dashboardfeature.util.mockedSigningLogDomain
import eu.europa.ec.dashboardfeature.util.mockedTransactionClaimPath
import eu.europa.ec.dashboardfeature.util.mockedTransactionClaims
import eu.europa.ec.dashboardfeature.util.mockedTransactionCredential
import eu.europa.ec.dashboardfeature.util.mockedTransactionDetailsStrings
import eu.europa.ec.dashboardfeature.util.mockedTransactionIntermediary
import eu.europa.ec.dashboardfeature.util.mockedTransactionLanguageTag
import eu.europa.ec.dashboardfeature.util.mockedTransactionLogDomains
import eu.europa.ec.dashboardfeature.util.mockedTransactionParty
import eu.europa.ec.dashboardfeature.util.mockedTransactionPartyName
import eu.europa.ec.dashboardfeature.util.mockedTransactionPartyNames
import eu.europa.ec.dashboardfeature.util.mockedTransactionPartyWithContacts
import eu.europa.ec.dashboardfeature.util.mockedTransactionQualifiedIdentifier
import eu.europa.ec.dashboardfeature.util.mockedTransactionRegistration
import eu.europa.ec.dashboardfeature.util.mockedTransactionTypeLabels
import eu.europa.ec.dashboardfeature.util.mockedUnnamedTransactionLogDomains
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.transaction_details_intermediary_contact_label
import eu.europa.ec.shared.resources.transaction_details_intermediary_name_label
import eu.europa.ec.testfeature.util.mockedExceptionWithMessage
import eu.europa.ec.testfeature.util.mockedExceptionWithNoMessage
import eu.europa.ec.testfeature.util.mockedGenericErrorMessage
import eu.europa.ec.testlogic.extension.runFlowTest
import eu.europa.ec.testlogic.extension.runTest
import eu.europa.ec.testlogic.rule.CoroutineTestRule
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.jetbrains.compose.resources.StringResource
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDateTime

class TestTransactionDetailsInteractor {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    @Mock
    private lateinit var platform: TransactionsPlatformBridge

    @Mock
    private lateinit var strings: StringCatalog

    private lateinit var interactor: TransactionDetailsInteractor

    private lateinit var closeable: AutoCloseable

    @Before
    fun before() {
        closeable = MockitoAnnotations.openMocks(this)

        // The one shared interactor over a mocked platform bridge, which hands over the same
        // TransactionLogDomain upstream's WalletCoreTransactionLogController does.
        interactor = TransactionDetailsInteractorImpl(
            platform = platform,
            strings = strings,
        )

        whenever(strings[Res.string.generic_error_message]).thenReturn(mockedGenericErrorMessage)
        mockTransactionDetailsStrings()
    }

    @After
    fun after() {
        closeable.close()
    }

    //region getTransactionDetails

    // Case 1:
    // 1. platform.getTransactionLog returns a CredentialIssuance with
    //    result = TransactionResultDomain.Completed.

    // Case 1 Expected Result:
    // Success with a TransactionDetailsCardUi where transactionIsCompleted is true,
    // the issuer name, both recorded counts and issued credential identifier are shown without a trigger.
    @Test
    fun `Given Case 1, When getTransactionDetails is called, Then Case 1 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val transaction = mockedIssuanceLogDomain.copy(
                id = mockedTransactionId,
                result = TransactionResultDomain.Completed,
                time = mockedCreationLocalDateTime,
            )
            whenever(platform.getTransactionLog(id = mockedTransactionId))
                .thenReturn(transaction)

            // When
            interactor.getTransactionDetails(transactionId = mockedTransactionId).runFlowTest {
                // Then
                val details =
                    (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                val card = details.transactionDetailsCardUi
                assertEquals(mockedTransactionId, details.transactionId)
                assertTrue(card.transactionIsCompleted)
                assertEquals(mockedIssuanceLabel, card.transactionTypeLabel)
                assertEquals(mockedCompletedLabel, card.transactionStatusLabel)
                assertEquals(mockedIssuanceDetails.issuer.name?.text, card.partyName)
                assertEquals(
                    mockedCreationLocalDateTime.formatLocalDateTime(pattern = FULL_DATETIME_PATTERN),
                    card.transactionDate,
                )
                assertNull(card.nonCompletionReason)
                val body = details.body as TransactionDetailsBodyUi.Issuance
                assertEquals("PIDProvider", card.providerType)
                assertEquals(
                    listOf(
                        TransactionDetailsFieldUi(
                            "requested-count",
                            "Credentials requested",
                            "1",
                            null
                        ),
                        TransactionDetailsFieldUi("issued-count", "Credentials issued", "1", null),
                    ),
                    card.metadata.single().fields,
                )
                assertEquals("CREDENTIALS ISSUED", body.credentials!!.title)
                assertEquals(
                    mockedTransactionCredential.identifier,
                    body.credentials!!.items.single().item.textValue()
                )
            }
        }
    }

    // Case 2:
    // 1. platform.getTransactionLog returns a CredentialIssuance with
    //    result = TransactionResultDomain.NotCompleted (mapped to TransactionStatusUi.NotCompleted).

    // Case 2 Expected Result:
    // Success with transactionIsCompleted = false and the Not completed status label and no invented reason.
    @Test
    fun `Given Case 2, When getTransactionDetails is called, Then Case 2 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val transaction = mockedIssuanceLogDomain.copy(
                id = mockedTransactionId,
                result = TransactionResultDomain.NotCompleted(reason = null),
                time = mockedCreationLocalDateTime,
            )
            whenever(platform.getTransactionLog(id = mockedTransactionId))
                .thenReturn(transaction)

            // When
            interactor.getTransactionDetails(transactionId = mockedTransactionId).runFlowTest {
                // Then
                val result = awaitItem()
                assertTrue(result is TransactionDetailsInteractorPartialState.Success)
                result as TransactionDetailsInteractorPartialState.Success

                val card = result.transactionDetailsUi.transactionDetailsCardUi
                assertEquals(false, card.transactionIsCompleted)
                assertEquals(mockedNotCompletedLabel, card.transactionStatusLabel)
                assertNull(card.nonCompletionReason)
                assertEquals(mockedIssuanceLabel, card.transactionTypeLabel)
            }
        }
    }

    // Case 3:
    // 1. platform.getTransactionLog returns a Presentation.

    // Case 3 Expected Result:
    // Success with the presentation label and party name, without a verified badge or claim values.
    @Test
    fun `Given Case 3, When getTransactionDetails is called, Then Case 3 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val transaction = mockedPresentationLogDomain.copy(
                id = mockedTransactionId,
                result = TransactionResultDomain.Completed,
                time = mockedCreationLocalDateTime,
            )
            whenever(platform.getTransactionLog(id = mockedTransactionId))
                .thenReturn(transaction)

            // When
            interactor.getTransactionDetails(transactionId = mockedTransactionId).runFlowTest {
                // Then
                val result = awaitItem()
                assertTrue(result is TransactionDetailsInteractorPartialState.Success)
                result as TransactionDetailsInteractorPartialState.Success

                val card = result.transactionDetailsUi.transactionDetailsCardUi
                assertEquals(mockedPresentationLabel, card.transactionTypeLabel)
                assertEquals(mockedTransactionPartyName, card.partyName)
                val body = result.transactionDetailsUi.body as TransactionDetailsBodyUi.Presentation
                assertEquals("DATA REQUESTED", body.requested.title)
                assertEquals("DATA SHARED", body.shared.title)
                assertEquals(
                    mockedTransactionCredential.identifier,
                    body.requested.groups.single().header.textValue()
                )
                assertEquals(
                    mockedTransactionCredential.identifier,
                    body.shared.groups.single().header.textValue()
                )
                assertEquals(
                    mockedTransactionClaimPath,
                    body.requested.groups.single().items.single().header.textValue()
                )
                assertEquals(
                    mockedTransactionClaimPath,
                    body.shared.groups.single().items.single().header.textValue()
                )
                assertTrue(body.requested.groups.single().header.itemId != body.shared.groups.single().header.itemId)
                assertEquals(
                    ListItemTrailingContentDataUi.Icon(AppIcons.KeyboardArrowDown),
                    body.requested.groups.single().header.trailingContentData,
                )
                assertEquals(
                    ListItemTrailingContentDataUi.Icon(AppIcons.KeyboardArrowDown),
                    body.shared.groups.single().header.trailingContentData,
                )
                assertTrue(card.metadata.isEmpty())
                assertEquals("ServiceProvider", card.providerType)
            }
        }
    }


    // Case 5:
    // 1. platform.getTransactionLog returns a SigningSealing.
    //
    // Case 5 Expected Result:
    // Success with the service name and a plain filename; technical metadata is omitted.
    @Test
    fun `Given Case 5, When getTransactionDetails is called, Then Case 5 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val transaction = mockedSigningLogDomain.copy(
                id = mockedTransactionId,
                result = TransactionResultDomain.Completed,
                time = mockedCreationLocalDateTime,
            )
            whenever(platform.getTransactionLog(id = mockedTransactionId))
                .thenReturn(transaction)

            // When
            interactor.getTransactionDetails(transactionId = mockedTransactionId).runFlowTest {
                // Then
                val result = awaitItem()
                assertTrue(result is TransactionDetailsInteractorPartialState.Success)
                result as TransactionDetailsInteractorPartialState.Success

                val card = result.transactionDetailsUi.transactionDetailsCardUi
                assertEquals(mockedSigningLabel, card.transactionTypeLabel)
                assertEquals(mockedSigningLogDomain.service.name?.text, card.partyName)
                val body = result.transactionDetailsUi.body as TransactionDetailsBodyUi.Signing
                assertEquals("ESigESealCreationProvider", card.providerType)
                assertTrue(card.metadata.isEmpty())
                val filename = body.document!!.items.single()
                assertEquals("signed.pdf", filename.item.textValue())
                assertNull(filename.url)
                assertNull(filename.item.trailingContentData)
                assertEquals(listOf(body.document), body.sections)
            }
        }
    }

    // Case 6:
    // 1. platform.getTransactionLog returns null (no transaction for that id).

    // Case 6 Expected Result:
    // Failure with the generic error message.
    @Test
    fun `Given Case 6, When getTransactionDetails is called, Then Case 6 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            whenever(platform.getTransactionLog(id = mockedTransactionId))
                .thenReturn(null)

            // When
            interactor.getTransactionDetails(transactionId = mockedTransactionId).runFlowTest {
                // Then
                assertEquals(
                    TransactionDetailsInteractorPartialState.Failure(
                        error = mockedGenericErrorMessage
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case 7:
    // 1. platform.getTransactionLog throws an exception with a message.

    // Case 7 Expected Result:
    // Failure with the thrown exception's localized message.
    @Test
    fun `Given Case 7, When getTransactionDetails is called, Then Case 7 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            whenever(platform.getTransactionLog(id = mockedTransactionId))
                .thenThrow(mockedExceptionWithMessage)

            // When
            interactor.getTransactionDetails(transactionId = mockedTransactionId).runFlowTest {
                // Then
                assertEquals(
                    TransactionDetailsInteractorPartialState.Failure(
                        error = mockedExceptionWithMessage.localizedMessage!!
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case 8:
    // 1. platform.getTransactionLog throws an exception with no message.

    // Case 8 Expected Result:
    // Failure with the generic error message.
    @Test
    fun `Given Case 8, When getTransactionDetails is called, Then Case 8 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            whenever(platform.getTransactionLog(id = mockedTransactionId))
                .thenThrow(mockedExceptionWithNoMessage)

            // When
            interactor.getTransactionDetails(transactionId = mockedTransactionId).runFlowTest {
                // Then
                assertEquals(
                    TransactionDetailsInteractorPartialState.Failure(
                        error = mockedGenericErrorMessage
                    ),
                    awaitItem()
                )
            }
        }
    }

    // Case 9:
    // 1. Each supported transaction is requested by its stored id.
    //
    // Case 9 Expected Result:
    // All types return a dated status card without a verified badge or claim values.
    @Test
    fun `Given every supported transaction type, When getTransactionDetails is called, Then each card renders`() {
        coroutineRule.runTest {
            // Given

            mockedTransactionLogDomains.forEachIndexed { index, transaction ->
                mockGetTransactionLogCall(response = transaction)

                // When
                interactor.getTransactionDetails(transactionId = transaction.id).runFlowTest {
                    // Then
                    val details =
                        (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                    val card = details.transactionDetailsCardUi
                    assertEquals(transaction.id, details.transactionId)
                    assertEquals(mockedTransactionTypeLabels[index], card.transactionTypeLabel)
                    assertEquals(mockedCompletedLabel, card.transactionStatusLabel)
                    assertTrue(card.transactionIsCompleted)
                    assertEquals(
                        transaction.time.formatLocalDateTime(pattern = FULL_DATETIME_PATTERN),
                        card.transactionDate,
                    )
                    assertEquals(mockedTransactionPartyNames[index], card.partyName)
                    assertNull(card.nonCompletionReason)
                    assertTrue(details.body.sections.isNotEmpty())
                    assertTrue(details.body.sections.all { section -> section.title.isNotBlank() })
                }
            }
        }
    }

    // Case 10:
    // 1. Requested and shared credentials differ, and requested claims use nested paths.
    //
    // Case 10 Expected Result:
    // Groups stay separate; typed paths and raw credential identifiers remain readable.
    @Test
    fun `Given Case 10, When getTransactionDetails is called, Then Case 10 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val transaction = mockedPresentationLogDomain.copy(
                claimsRequested = mockedNestedTransactionClaims,
                claimsPresented = mockedTransactionClaims,
            )
            mockGetTransactionLogCall(transaction)

            // When
            interactor.getTransactionDetails(transaction.id).runFlowTest {
                // Then
                val body = (awaitItem() as TransactionDetailsInteractorPartialState.Success)
                    .transactionDetailsUi.body as TransactionDetailsBodyUi.Presentation
                val requested = body.requested.groups.single()
                assertEquals(
                    mockedOtherTransactionCredential.identifier,
                    requested.header.textValue()
                )
                assertEquals(
                    listOf(
                        "[\"addresses\"][0][\"street.name\"]",
                        "[\"addresses\"][*]",
                        "Attribute identifier unavailable"
                    ),
                    requested.items.map { row -> row.header.textValue() },
                )
                assertEquals(
                    mockedTransactionClaimPath,
                    body.shared.groups.single().items.single().header.textValue()
                )
                assertTrue(requested.items.all { row -> row.header.overlineText == null && row.header.trailingContentData == null })
            }
        }
    }

    // Case 11:
    // 1. A presentation carries complete party, registration and authority metadata.
    //
    // Case 11 Expected Result:
    // Only approved metadata is in the card, in order.
    @Test
    fun `Given Case 11, When getTransactionDetails is called, Then Case 11 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockGetTransactionLogCall(mockedDetailedPresentationLogDomain)

            // When
            interactor.getTransactionDetails(mockedDetailedPresentationLogDomain.id).runFlowTest {
                // Then
                val details =
                    (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                val body = details.body as TransactionDetailsBodyUi.Presentation
                val groups = details.transactionDetailsCardUi.metadata
                assertEquals(
                    listOf(
                        listOf("purpose"),
                        listOf("privacy:0", "privacy:1"),
                        listOf("party:contact:0", "party:contact:1", "party:contact:2"),
                    ),
                    groups.map { group -> group.fields.map { field -> field.id } },
                )
                val fields =
                    groups.flatMap { group -> group.fields }.associateBy { field -> field.id }
                assertEquals(
                    mockedTransactionRegistration.purpose,
                    fields.getValue("purpose").value
                )
                assertNull(fields.getValue("purpose").url)
                assertNull(fields.getValue("party:contact:0").url)
                assertEquals(
                    mockedTransactionRegistration.privacyPolicyUrls,
                    groups[1].fields.map { field -> field.url })
                assertEquals(listOf(body.requested, body.shared), body.sections)
            }
        }
    }

    // Case 12:
    // 1. Registration URLs and contacts include unsupported schemes, malformed URLs and country text.
    //
    // Case 12 Expected Result:
    // Unusable links remain plain text and blank contact fields are omitted.
    @Test
    fun `Given Case 12, When getTransactionDetails is called, Then Case 12 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val invalidLinks = listOf(
                "javascript:alert(1)", "file:///data/private", "intent://launch",
                "https://", "/relative/path", "GR", "mailto:not-an-email",
            )
            val transaction = mockedDetailedPresentationLogDomain.copy(
                party = mockedTransactionPartyWithContacts.copy(contacts = invalidLinks + " "),
                registration = mockedTransactionRegistration.copy(
                    registrarUrl = invalidLinks.first(),
                    privacyPolicyUrls = invalidLinks,
                    dpa = DpaContactDomain(null, null, invalidLinks),
                ),
            )
            mockGetTransactionLogCall(transaction)

            // When
            interactor.getTransactionDetails(transaction.id).runFlowTest {
                // Then
                val details =
                    (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                val fields =
                    details.transactionDetailsCardUi.metadata.flatMap { group -> group.fields }
                assertTrue(fields.all { field -> field.url == null })
                assertEquals(
                    invalidLinks,
                    fields.filter { field -> field.id.startsWith("party:contact:") }
                        .map { field -> field.value })
                assertTrue(fields.none { field -> field.id.startsWith("authority:") || field.id == "registrar" })
            }
        }
    }

    // Case 13:
    // 1. Each transaction type has a missing or blank party name.
    //
    // Case 13 Expected Result:
    // Cards omit blank names; signing omits the document section when its filename is blank.
    @Test
    fun `Given Case 13, When getTransactionDetails is called, Then Case 13 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockedUnnamedTransactionLogDomains.forEach { transaction ->
                mockGetTransactionLogCall(transaction)

                // When
                interactor.getTransactionDetails(transaction.id).runFlowTest {
                    // Then
                    val details =
                        (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                    assertNull(details.transactionDetailsCardUi.partyName)
                    if (transaction is TransactionLogDomain.SigningSealing) {
                        assertTrue(details.body.sections.isEmpty())
                    }
                    details.body.sections.forEach { section ->
                        if (section.items.isEmpty() && section.groups.isEmpty()) {
                            assertTrue(section.emptyItem?.textValue()?.isNotBlank() == true)
                        } else {
                            assertNull(section.emptyItem)
                        }
                    }
                    assertTrue(details.body.sections.flatMap { section -> section.items }
                        .all { row -> row.item.textValue().isNotBlank() })
                }
            }
        }
    }

    // Case 14:
    // 1. Issuance and re-issuance have all three trigger values and a partial batch.
    //
    // Case 14 Expected Result:
    // Both counts precede the reissuance-only trigger; one issued list has no inferred per-item outcomes.
    @Test
    fun `Given Case 14, When getTransactionDetails is called, Then Case 14 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            listOf(true, false, null).forEach { trigger ->
                val details = mockedIssuanceDetails.copy(
                    requestedCount = 3, issuedCount = 1, isUserTriggered = trigger,
                )
                val transactions = listOf(
                    mockedIssuanceLogDomain.copy(details = details),
                    mockedReissuanceLogDomain.copy(details = details),
                )
                transactions.forEachIndexed { index, transaction ->
                    mockGetTransactionLogCall(transaction)

                    // When
                    interactor.getTransactionDetails(transaction.id).runFlowTest {
                        // Then
                        val result =
                            (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                        val fields =
                            result.transactionDetailsCardUi.metadata.flatMap { group -> group.fields }
                        val expectedTrigger = if (index == 0) null else when (trigger) {
                            true -> "Wallet holder"
                            false -> "The Wallet"
                            null -> null
                        }
                        assertEquals(
                            expectedTrigger,
                            fields.singleOrNull { field -> field.id == "trigger" }?.value,
                        )
                        assertEquals(
                            listOf(
                                TransactionDetailsFieldUi(
                                    "requested-count",
                                    "Credentials requested",
                                    "3",
                                    null
                                ),
                                TransactionDetailsFieldUi(
                                    "issued-count",
                                    "Credentials issued",
                                    "1",
                                    null
                                ),
                            ),
                            result.transactionDetailsCardUi.metadata.first().fields,
                        )
                        assertEquals("CREDENTIALS ISSUED", result.body.sections.single().title)
                        val credential = result.body.sections.single().items.single()
                        assertEquals(
                            mockedTransactionCredential.identifier,
                            credential.item.textValue()
                        )
                        assertNull(credential.item.supportingContentData)
                        assertNull(credential.item.trailingContentData)
                    }
                }
            }
        }
    }

    // Case 15:
    // 1. Every transaction type is not completed, with an optional reason.
    //
    // Case 15 Expected Result:
    // Recorded reasons are preserved; missing or blank reasons stay absent.
    @Test
    fun `Given Case 15, When getTransactionDetails is called, Then Case 15 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockedNotCompletedTransactionLogDomains.forEach { transaction ->
                mockGetTransactionLogCall(transaction)

                // When
                interactor.getTransactionDetails(transaction.id).runFlowTest {
                    // Then
                    val card = (awaitItem() as TransactionDetailsInteractorPartialState.Success)
                        .transactionDetailsUi.transactionDetailsCardUi
                    assertEquals(false, card.transactionIsCompleted)
                    assertEquals("Not completed", card.transactionStatusLabel)
                    val reason = (transaction.result as TransactionResultDomain.NotCompleted).reason
                    assertEquals(
                        reason?.takeIf { value -> value.isNotBlank() },
                        card.nonCompletionReason
                    )
                }
            }
        }
    }

    // Case 16:
    // 1. A deletion refers to a removed credential and still carries issuer identity.
    //
    // Case 16 Expected Result:
    // The original issuer name and raw credential stay visible; issuer identifiers and contacts are omitted.
    @Test
    fun `Given Case 16, When getTransactionDetails is called, Then Case 16 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val transaction = mockedDeletionLogDomain.copy(
                credential = mockedOtherTransactionCredential,
                issuer = mockedTransactionPartyWithContacts,
            )
            mockGetTransactionLogCall(transaction)

            // When
            interactor.getTransactionDetails(transaction.id).runFlowTest {
                // Then
                val details =
                    (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                val body = details.body as TransactionDetailsBodyUi.Deletion
                assertEquals("CREDENTIALS", body.credential!!.title)
                assertEquals(
                    mockedOtherTransactionCredential.identifier,
                    body.credential!!.items.single().item.textValue()
                )
                assertEquals(
                    transaction.issuer.name?.text,
                    details.transactionDetailsCardUi.partyName
                )
                assertNull(details.transactionDetailsCardUi.providerType)
                assertTrue(details.transactionDetailsCardUi.metadata.isEmpty())
                assertEquals(listOf(body.credential), body.sections)
            }
        }
    }

    // Case 17:
    // 1. Signing metadata has a digest and zero, missing, negative or large file sizes.
    //
    // Case 17 Expected Result:
    // Only the recorded filename is displayed, regardless of technical metadata.
    @Test
    fun `Given Case 17, When getTransactionDetails is called, Then Case 17 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            listOf(0L, null, -1L, Long.MAX_VALUE).forEach { size ->
                val transaction =
                    mockedSigningLogDomain.copy(fileSizeBytes = size, dtbsr = "c2lnbmVk")
                mockGetTransactionLogCall(transaction)

                // When
                interactor.getTransactionDetails(transaction.id).runFlowTest {
                    // Then
                    val details =
                        (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                    val body = details.body as TransactionDetailsBodyUi.Signing
                    assertEquals(listOf(body.document), body.sections)
                    val filename = body.document!!.items.single()
                    assertEquals("filename", filename.item.itemId)
                    assertEquals(transaction.fileName, filename.item.textValue())
                    assertNull(filename.url)
                    assertNull(filename.item.trailingContentData)
                    assertTrue(body.document!!.groups.isEmpty())
                    assertTrue(details.transactionDetailsCardUi.metadata.isEmpty())
                }
            }
        }
    }

    // Case 18:
    // 1. A data-deletion request contains multiple raw credential identifiers.
    //
    // Case 18 Expected Result:
    // Only that request's claims appear, with independent credential groups.
    @Test
    fun `Given Case 18, When getTransactionDetails is called, Then Case 18 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val transaction = mockedDataDeletionLogDomain.copy(
                claims = mockedTransactionClaims + mockedNestedTransactionClaims
            )
            mockGetTransactionLogCall(transaction)

            // When
            interactor.getTransactionDetails(transaction.id).runFlowTest {
                // Then
                val body = (awaitItem() as TransactionDetailsInteractorPartialState.Success)
                    .transactionDetailsUi.body as TransactionDetailsBodyUi.DataDeletionRequest
                assertEquals(
                    listOf(
                        mockedTransactionCredential.identifier,
                        mockedOtherTransactionCredential.identifier
                    ),
                    body.claims.groups.map { group -> group.header.textValue() },
                )
                assertEquals(
                    mockedTransactionClaimPath,
                    body.claims.groups.first().items.single().header.textValue()
                )
                assertEquals(
                    2,
                    body.claims.groups.map { group -> group.header.itemId }.distinct().size
                )
                body.claims.groups.forEach { group ->
                    assertEquals(
                        ListItemTrailingContentDataUi.Icon(AppIcons.KeyboardArrowDown),
                        group.header.trailingContentData,
                    )
                }
                assertEquals(mockedTransactionPartyName, body.party.items.first().item.textValue())
            }
        }
    }

    // Case 19:
    // 1. Authority reports contain full, partial or absent information.
    //
    // Case 19 Expected Result:
    // Only recorded fields are displayed, with an empty state when both fields are absent.
    @Test
    fun `Given Case 19, When getTransactionDetails is called, Then Case 19 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val transactions = listOf(
                mockedDpaReportLogDomain,
                mockedDpaReportLogDomain.copy(dpaName = null),
                mockedDpaReportLogDomain.copy(
                    dpaName = LocalizedTextDomain(
                        mockedTransactionLanguageTag,
                        " "
                    ), dpaCountry = null
                ),
            )
            transactions.forEachIndexed { index, transaction ->
                mockGetTransactionLogCall(transaction)

                // When
                interactor.getTransactionDetails(transaction.id).runFlowTest {
                    // Then
                    val body = (awaitItem() as TransactionDetailsInteractorPartialState.Success)
                        .transactionDetailsUi.body as TransactionDetailsBodyUi.DpaReport
                    assertEquals(2 - index, body.authority.items.size)
                    if (body.authority.items.isEmpty()) {
                        assertEquals(
                            "No information recorded",
                            body.authority.emptyItem?.textValue()
                        )
                    } else {
                        assertNull(body.authority.emptyItem)
                    }
                    assertEquals(
                        transaction.dpaCountry?.text,
                        body.authority.items.firstOrNull { row -> row.item.itemId == "authority:country" }?.item?.textValue()
                    )
                }
            }
        }
    }

    // Case 20:
    // 1. A presentation has empty requested data, shared data or both, and a blank non-completion reason.
    //
    // Case 20 Expected Result:
    // Both sections remain visible; only empty sections have a plain prepared message row.
    // There is no reason fallback or empty card metadata.
    @Test
    fun `Given Case 20, When getTransactionDetails is called, Then Case 20 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val emptyClaims = emptyList<CredentialClaimsDomain>()
            listOf(
                emptyClaims to emptyClaims,
                emptyClaims to mockedTransactionClaims,
                mockedTransactionClaims to emptyClaims,
            ).forEach { (requestedClaims, sharedClaims) ->
                val transaction = mockedPresentationLogDomain.copy(
                    result = TransactionResultDomain.NotCompleted(" "),
                    party = InteractingPartyDomain(null, null, emptyList()),
                    claimsRequested = requestedClaims,
                    claimsPresented = sharedClaims,
                )
                mockGetTransactionLogCall(transaction)

                // When
                interactor.getTransactionDetails(transaction.id).runFlowTest {
                    // Then
                    val details =
                        (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                    val body = details.body as TransactionDetailsBodyUi.Presentation
                    assertEquals(listOf(body.requested, body.shared), body.sections)
                    assertEquals(requestedClaims.isEmpty(), body.requested.groups.isEmpty())
                    assertEquals(
                        if (requestedClaims.isEmpty()) {
                            ListItemDataUi(
                                itemId = "requested:empty",
                                mainContentData = ListItemMainContentDataUi.Text("No data requested"),
                            )
                        } else {
                            null
                        },
                        body.requested.emptyItem,
                    )
                    assertEquals(sharedClaims.isEmpty(), body.shared.groups.isEmpty())
                    assertEquals(
                        if (sharedClaims.isEmpty()) {
                            ListItemDataUi(
                                itemId = "shared:empty",
                                mainContentData = ListItemMainContentDataUi.Text("No data shared"),
                            )
                        } else {
                            null
                        },
                        body.shared.emptyItem,
                    )
                    assertTrue(details.transactionDetailsCardUi.metadata.isEmpty())
                    assertNull(details.transactionDetailsCardUi.nonCompletionReason)
                }
            }
        }
    }

    // Case 21:
    // 1. Repeated credential types occur in both sections and a claim key contains quotes.
    //
    // Case 21 Expected Result:
    // All group/field IDs are unique; raw paths escape keys and empty claim lists stay explicit.
    @Test
    fun `Given Case 21, When getTransactionDetails is called, Then Case 21 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val claims = mockedTransactionClaims.single().copy(
                claims = listOf(
                    ClaimRefDomain(listOf(ClaimPathSegment.Key("quoted\"key")))
                )
            )
            val transaction = mockedPresentationLogDomain.copy(
                claimsRequested = listOf(claims, claims.copy(claims = emptyList())),
                claimsPresented = listOf(claims),
            )
            mockGetTransactionLogCall(transaction)

            // When
            interactor.getTransactionDetails(transaction.id).runFlowTest {
                // Then
                val body = (awaitItem() as TransactionDetailsInteractorPartialState.Success)
                    .transactionDetailsUi.body as TransactionDetailsBodyUi.Presentation
                assertNull(body.requested.emptyItem)
                assertNull(body.shared.emptyItem)
                val groups = body.requested.groups + body.shared.groups
                val ids =
                    groups.map { group -> group.header.itemId } + groups.flatMap { group -> group.items }
                        .map { row -> row.header.itemId }
                assertEquals(ids.size, ids.distinct().size)
                assertEquals(
                    "[\"quoted\\\"key\"]",
                    groups.first().items.single().header.textValue()
                )
                assertEquals(
                    "No attributes recorded",
                    body.requested.groups.last().items.single().header.textValue()
                )
            }
        }
    }


    // Case 22:
    // 1. A presentation contains a credential whose type differs from its namespace and a credential with nested paths.
    //
    // Case 22 Expected Result:
    // Both requested and shared groups show the raw credential identifiers and exact escaped paths.
    @Test
    fun `Given Case 22, When getTransactionDetails is called, Then Case 22 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val mdocIdentifier = "org.iso.18013.5.1.mDL"
            val namespace = "org.iso.18013.5.1"
            val vct = "urn:example:Identity:V1"
            val claims = listOf(
                CredentialClaimsDomain(
                    CredentialRefDomain(mdocIdentifier),
                    listOf(
                        ClaimRefDomain(
                            listOf(
                                ClaimPathSegment.Key(namespace),
                                ClaimPathSegment.Key("family_name")
                            )
                        )
                    ),
                ),
                CredentialClaimsDomain(
                    CredentialRefDomain(vct),
                    listOf(
                        ClaimRefDomain(
                            listOf(
                                ClaimPathSegment.Key("address"),
                                ClaimPathSegment.Key("street_address")
                            )
                        ),
                        ClaimRefDomain(
                            listOf(
                                ClaimPathSegment.Key("nationalities"),
                                ClaimPathSegment.Index(0)
                            )
                        ),
                        ClaimRefDomain(
                            listOf(
                                ClaimPathSegment.Key("nationalities"),
                                ClaimPathSegment.AllElements
                            )
                        ),
                        ClaimRefDomain(
                            listOf(
                                ClaimPathSegment.Key("0"),
                                ClaimPathSegment.Key("quoted\"\\key")
                            )
                        ),
                    ),
                ),
            )
            val transaction =
                mockedPresentationLogDomain.copy(claimsRequested = claims, claimsPresented = claims)
            mockGetTransactionLogCall(transaction)

            // When
            interactor.getTransactionDetails(transaction.id).runFlowTest {
                // Then
                val body = (awaitItem() as TransactionDetailsInteractorPartialState.Success)
                    .transactionDetailsUi.body as TransactionDetailsBodyUi.Presentation
                listOf(body.requested, body.shared).forEach { section ->
                    assertEquals(
                        listOf(mdocIdentifier, vct),
                        section.groups.map { group -> group.header.textValue() })
                    assertEquals(
                        listOf(
                            "[\"org.iso.18013.5.1\"][\"family_name\"]",
                            "[\"address\"][\"street_address\"]",
                            "[\"nationalities\"][0]",
                            "[\"nationalities\"][*]",
                            "[\"0\"][\"quoted\\\"\\\\key\"]",
                        ),
                        section.groups.flatMap { group -> group.items }
                            .map { row -> row.header.textValue() },
                    )
                }
            }
        }
    }

    // Case 23:
    // 1. A presentation has distinct relying-party and intermediary identity, plus authority data.
    // 2. Intermediary contacts include web, email, phone, an unsupported link and a blank value.
    //
    // Case 23 Expected Result:
    // The card ends with intermediary name/contact fields labeled with their owner.
    @Test
    fun `Given intermediary details, When getTransactionDetails is called, Then intermediary fields identify their owner`() {
        coroutineRule.runTest {
            // Given
            val nameLabel =
                mockedTransactionDetailsStrings.getValue(Res.string.transaction_details_intermediary_name_label)
            val contactLabel =
                mockedTransactionDetailsStrings.getValue(Res.string.transaction_details_intermediary_contact_label)
            val identifier = mockedTransactionQualifiedIdentifier.copy(value = "intermediary-456")
            val intermediary = mockedTransactionIntermediary.copy(
                identifier = identifier,
                contacts = listOf(
                    mockedActionWebUrl,
                    mockedActionMailUrl,
                    mockedActionPhoneUrl,
                    "javascript:alert(1)",
                    " "
                ),
            )
            val transaction = mockedDetailedPresentationLogDomain.copy(intermediary = intermediary)
            mockGetTransactionLogCall(transaction)

            // When
            interactor.getTransactionDetails(transaction.id).runFlowTest {
                // Then
                val details =
                    (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                val body = details.body as TransactionDetailsBodyUi.Presentation
                val group = details.transactionDetailsCardUi.metadata.last()
                assertEquals(
                    listOf(
                        TransactionDetailsFieldUi(
                            "intermediary:name",
                            nameLabel,
                            intermediary.name!!.text,
                            null
                        ),
                        TransactionDetailsFieldUi(
                            "intermediary:contact:0",
                            contactLabel,
                            mockedActionWebUrl,
                            mockedActionWebUrl
                        ),
                        TransactionDetailsFieldUi(
                            "intermediary:contact:1",
                            contactLabel,
                            mockedActionMailUrl,
                            mockedActionMailUrl
                        ),
                        TransactionDetailsFieldUi(
                            "intermediary:contact:2",
                            contactLabel,
                            mockedActionPhoneUrl,
                            mockedActionPhoneUrl
                        ),
                        TransactionDetailsFieldUi(
                            "intermediary:contact:3",
                            contactLabel,
                            "javascript:alert(1)",
                            null
                        ),
                    ),
                    group.fields,
                )
                assertEquals(listOf(body.requested, body.shared), body.sections)
                assertEquals(mockedTransactionPartyName, details.transactionDetailsCardUi.partyName)
            }
        }
    }

    // Case 24:
    // 1. Only the intermediary name, identifier or contact is recorded, without registration data.
    // 2. The relying party and authority have no action contacts.
    //
    // Case 24 Expected Result:
    // Names and contacts display independently with intermediary labels; identifier-only metadata stays hidden.
    @Test
    fun `Given partial intermediary details, When getTransactionDetails is called, Then available fields are shown independently`() {
        coroutineRule.runTest {
            // Given
            val nameLabel =
                mockedTransactionDetailsStrings.getValue(Res.string.transaction_details_intermediary_name_label)
            val contactLabel =
                mockedTransactionDetailsStrings.getValue(Res.string.transaction_details_intermediary_contact_label)
            val intermediaries = listOf(
                mockedTransactionIntermediary to listOf(
                    TransactionDetailsFieldUi(
                        "intermediary:name",
                        nameLabel,
                        mockedTransactionIntermediary.name!!.text,
                        null
                    ),
                ),
                mockedTransactionIntermediary.copy(
                    name = null,
                    identifier = mockedTransactionQualifiedIdentifier
                ) to emptyList(),
                mockedTransactionIntermediary.copy(
                    name = LocalizedTextDomain(
                        mockedTransactionLanguageTag,
                        " "
                    ), contacts = listOf(mockedActionMailUrl)
                ) to listOf(
                    TransactionDetailsFieldUi(
                        "intermediary:contact:0",
                        contactLabel,
                        mockedActionMailUrl,
                        mockedActionMailUrl
                    ),
                ),
            )
            intermediaries.forEach { (intermediary, expectedFields) ->
                val transaction = mockedPresentationLogDomain.copy(intermediary = intermediary)
                mockGetTransactionLogCall(transaction)

                // When
                interactor.getTransactionDetails(transaction.id).runFlowTest {
                    // Then
                    val details =
                        (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                    val body = details.body as TransactionDetailsBodyUi.Presentation
                    assertEquals(
                        expectedFields,
                        details.transactionDetailsCardUi.metadata.flatMap { group -> group.fields })
                    assertEquals(listOf(body.requested, body.shared), body.sections)
                }
            }
        }
    }

    // Case 25:
    // 1. Intermediary data is absent, empty or contains only blank values.
    //
    // Case 25 Expected Result:
    // No intermediary section or placeholder appears in the details list.
    @Test
    fun `Given no displayable intermediary details, When getTransactionDetails is called, Then the intermediary section is omitted`() {
        coroutineRule.runTest {
            // Given
            val intermediaries = listOf(
                null,
                InteractingPartyDomain(null, null, emptyList()),
                mockedTransactionIntermediary.copy(
                    name = LocalizedTextDomain(mockedTransactionLanguageTag, " \t"),
                    identifier = mockedTransactionQualifiedIdentifier.copy(
                        value = "",
                        schemeUri = " "
                    ),
                    contacts = listOf("", " \t"),
                ),
            )
            intermediaries.forEach { intermediary ->
                val transaction = mockedPresentationLogDomain.copy(intermediary = intermediary)
                mockGetTransactionLogCall(transaction)

                // When
                interactor.getTransactionDetails(transaction.id).runFlowTest {
                    // Then
                    val details =
                        (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                    val body = details.body as TransactionDetailsBodyUi.Presentation
                    assertTrue(details.transactionDetailsCardUi.metadata.isEmpty())
                    assertEquals(listOf(body.requested, body.shared), body.sections)
                }
            }
        }
    }


    // Case 26:
    // 1. Presentation and both issuance types have repeated contacts, including plain EU text.
    //
    // Case 26 Expected Result:
    // Display order and duplicates remain intact.
    @Test
    fun `Given duplicate contacts, When getTransactionDetails is called, Then display preserves all occurrences`() {
        coroutineRule.runTest {
            // Given
            val contacts = listOf(
                "EU",
                mockedActionWebUrl,
                mockedActionWebUrl,
                mockedActionMailUrl,
                mockedActionPhoneUrl
            )
            val issuer =
                mockedIssuanceDetails.copy(issuer = mockedTransactionParty.copy(contacts = contacts))
            val transactions = listOf(
                mockedPresentationLogDomain.copy(party = mockedTransactionParty.copy(contacts = contacts)),
                mockedIssuanceLogDomain.copy(details = issuer),
                mockedReissuanceLogDomain.copy(details = issuer),
            )
            transactions.forEach { transaction ->
                mockGetTransactionLogCall(transaction)

                // When
                interactor.getTransactionDetails(transaction.id).runFlowTest {
                    // Then
                    val details =
                        (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                    val fields =
                        details.transactionDetailsCardUi.metadata.flatMap { group -> group.fields }
                            .filter { field -> field.label == "Contact" }
                    assertEquals(contacts, fields.map { field -> field.value })
                    assertEquals(fields.size, fields.map { field -> field.id }.distinct().size)
                    assertNull(fields.first().url)
                    assertEquals(
                        listOf(
                            null,
                            mockedActionWebUrl,
                            mockedActionWebUrl,
                            mockedActionMailUrl,
                            mockedActionPhoneUrl
                        ), fields.map { field -> field.url })
                }
            }
        }
    }

    // Case 27:
    // 1. A nameless party has a recorded type and contact; registration contains only hidden metadata.
    //
    // Case 27 Expected Result:
    // Available type/contact fields remain visible independently and hidden fields create no groups.
    @Test
    fun `Given sparse metadata, When getTransactionDetails is called, Then only independent available fields are shown`() {
        coroutineRule.runTest {
            // Given
            val transaction = mockedDetailedPresentationLogDomain.copy(
                party = mockedTransactionPartyWithContacts.copy(
                    name = null,
                    contacts = listOf("EU")
                ),
                partyType = "ARecordedProviderType",
                registration = mockedTransactionRegistration.copy(
                    purpose = " ",
                    privacyPolicyUrls = emptyList()
                ),
            )
            mockGetTransactionLogCall(transaction)

            // When
            interactor.getTransactionDetails(transaction.id).runFlowTest {
                // Then
                val details =
                    (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                val card = details.transactionDetailsCardUi
                assertNull(card.partyName)
                assertEquals("ARecordedProviderType", card.providerType)
                assertEquals(
                    listOf(TransactionDetailsFieldUi("party:contact:0", "Contact", "EU", null)),
                    card.metadata.single().fields,
                )
            }
        }
    }

    // Case 28:
    // 1. Signing has a dedicated transaction identifier but no filename.
    //
    // Case 28 Expected Result:
    // The dedicated identifier is card metadata; missing filenames create no document section or file action.
    @Test
    fun `Given signing identifiers and absent filenames, When getTransactionDetails is called, Then only dedicated metadata is shown`() {
        coroutineRule.runTest {
            // Given
            listOf<String?>(null, "", " ").forEach { filename ->
                val transaction = mockedSigningLogDomain.copy(
                    signingTransactionId = "dedicated-signing-id",
                    serviceType = "ARecordedSigningType",
                    fileName = filename,
                    dtbsr = "digest",
                )
                mockGetTransactionLogCall(transaction)

                // When
                interactor.getTransactionDetails(transaction.id).runFlowTest {
                    // Then
                    val details =
                        (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                    assertEquals(
                        "ARecordedSigningType",
                        details.transactionDetailsCardUi.providerType
                    )
                    val field = details.transactionDetailsCardUi.metadata.single().fields.single()
                    assertEquals("dedicated-signing-id", field.value)
                    assertEquals("Signing transaction identifier", field.label)
                    assertNull(field.url)
                    assertTrue(details.body.sections.isEmpty())
                }
            }
        }
    }

    // Case 29:
    // 1. Optional presentation type/registration or signing identifier values are absent or blank.
    //
    // Case 29 Expected Result:
    // Hidden metadata and the generic transaction ID never create expandable card content.
    @Test
    fun `Given no permitted extra metadata, When getTransactionDetails is called, Then the card has no expansion content`() {
        coroutineRule.runTest {
            // Given
            val transactions = listOf(
                mockedPresentationLogDomain.copy(
                    partyType = " ",
                    party = mockedTransactionPartyWithContacts.copy(contacts = emptyList()),
                    registration = mockedTransactionRegistration.copy(
                        purpose = null,
                        privacyPolicyUrls = listOf(" ")
                    ),
                ),
                mockedSigningLogDomain.copy(serviceType = " ", signingTransactionId = " "),
            )
            transactions.forEach { transaction ->
                mockGetTransactionLogCall(transaction)

                // When
                interactor.getTransactionDetails(transaction.id).runFlowTest {
                    // Then
                    val card =
                        (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi.transactionDetailsCardUi
                    assertNull(card.providerType)
                    assertTrue(card.metadata.isEmpty())
                }
            }
        }
    }

    // Case 30:
    // 1. Not-completed issuance and reissuance have no credential identifiers and various recorded counts.
    //
    // Case 30 Expected Result:
    // Recorded counts, including zero, remain visible without inventing identifiers or deriving counts from the list.
    @Test
    fun `Given no recorded credentials, When getTransactionDetails is called, Then no credentials are inferred`() {
        coroutineRule.runTest {
            // Given
            listOf(0 to 0, 3 to 0, 3 to 1).forEach { (requestedCount, issuedCount) ->
                val issuanceDetails = mockedIssuanceDetails.copy(
                    credentials = emptyList(),
                    requestedCount = requestedCount,
                    issuedCount = issuedCount,
                    isUserTriggered = null,
                )
                val transactions = listOf(
                    mockedIssuanceLogDomain.copy(
                        details = issuanceDetails,
                        result = TransactionResultDomain.NotCompleted(reason = null),
                    ),
                    mockedReissuanceLogDomain.copy(
                        details = issuanceDetails,
                        result = TransactionResultDomain.NotCompleted(reason = null),
                    ),
                )
                transactions.forEach { transaction ->
                    mockGetTransactionLogCall(transaction)

                    // When
                    interactor.getTransactionDetails(transaction.id).runFlowTest {
                        // Then
                        val details =
                            (awaitItem() as TransactionDetailsInteractorPartialState.Success).transactionDetailsUi
                        assertTrue(details.body.sections.isEmpty())
                        assertEquals(false, details.transactionDetailsCardUi.transactionIsCompleted)
                        assertEquals(
                            listOf(
                                TransactionDetailsFieldUi(
                                    "requested-count",
                                    "Credentials requested",
                                    requestedCount.toString(),
                                    null,
                                ),
                                TransactionDetailsFieldUi(
                                    "issued-count",
                                    "Credentials issued",
                                    issuedCount.toString(),
                                    null,
                                ),
                            ),
                            details.transactionDetailsCardUi.metadata.single().fields,
                        )
                    }
                }
            }
        }
    }

    //endregion

    //region deleteTransaction

    // Case 1:
    // 1. Deletion succeeds for each supported completed and not-completed transaction.
    //
    // Case 1 Expected Result:
    // Success follows one delete call with the selected identifier, without loading its type.
    @Test
    fun `Given Case 1, When deleteTransaction is called, Then Case 1 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val transactions = mockedTransactionLogDomains + mockedNotCompletedTransactionLogDomains
            transactions.forEach { transaction ->
                mockDeleteTransactionLogCall(transactionId = transaction.id)

                // When
                interactor.deleteTransaction(transactionId = transaction.id).runFlowTest {
                    // Then
                    assertEquals(
                        TransactionDetailsInteractorDeleteTransactionPartialState.Success,
                        awaitItem()
                    )
                    awaitComplete()
                }
                verify(platform).deleteTransactionLog(id = transaction.id)
            }
            verifyNoMoreInteractions(platform)
        }
    }

    // Case 2:
    // 1. The storage deletion has started but has not completed.
    //
    // Case 2 Expected Result:
    // No success is emitted until storage confirms completion.
    @Test
    fun `Given Case 2, When deleteTransaction is called, Then Case 2 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            val started = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            whenever(platform.deleteTransactionLog(id = mockedTransactionId))
                .doSuspendableAnswer {
                    started.complete(Unit)
                    finished.await()
                }

            // When
            interactor.deleteTransaction(transactionId = mockedTransactionId).runFlowTest {
                started.await()

                // Then
                expectNoEvents()
                finished.complete(Unit)
                assertEquals(
                    TransactionDetailsInteractorDeleteTransactionPartialState.Success,
                    awaitItem()
                )
                awaitComplete()
            }
        }
    }

    // Case 3:
    // 1. Storage throws an exception with a message.
    //
    // Case 3 Expected Result:
    // Failure contains that message and no success is emitted.
    @Test
    fun `Given Case 3, When deleteTransaction is called, Then Case 3 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockDeleteTransactionLogFailure(exception = mockedExceptionWithMessage)

            // When
            interactor.deleteTransaction(transactionId = mockedTransactionId).runFlowTest {
                // Then
                assertEquals(
                    TransactionDetailsInteractorDeleteTransactionPartialState.Failure(
                        errorMessage = mockedExceptionWithMessage.localizedMessage!!,
                    ),
                    awaitItem(),
                )
                awaitComplete()
            }
        }
    }

    // Case 4:
    // 1. Storage throws an exception without a message.
    //
    // Case 4 Expected Result:
    // Failure uses the generic error message.
    @Test
    fun `Given Case 4, When deleteTransaction is called, Then Case 4 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockDeleteTransactionLogFailure(exception = mockedExceptionWithNoMessage)

            // When
            interactor.deleteTransaction(transactionId = mockedTransactionId).runFlowTest {
                // Then
                assertEquals(
                    TransactionDetailsInteractorDeleteTransactionPartialState.Failure(
                        errorMessage = mockedGenericErrorMessage,
                    ),
                    awaitItem(),
                )
                awaitComplete()
            }
        }
    }
    //endregion

    //region helper functions
    private fun ListItemDataUi.textValue(): String =
        (mainContentData as ListItemMainContentDataUi.Text).text

    private suspend fun mockDeleteTransactionLogCall(transactionId: String) {
        whenever(platform.deleteTransactionLog(id = transactionId)).thenReturn(
            Unit
        )
    }

    private suspend fun mockDeleteTransactionLogFailure(exception: Throwable) {
        whenever(platform.deleteTransactionLog(id = mockedTransactionId))
            .thenThrow(exception)
    }

    private suspend fun mockGetTransactionLogCall(response: TransactionLogDomain) {
        whenever(platform.getTransactionLog(id = response.id)).thenReturn(
            response
        )
    }

    private fun mockTransactionDetailsStrings() {
        // One catalog serves both: upstream's generic error comes from a separate
        // ResourceProvider.genericErrorMessage(), ours from this same `get`, so it is answered here.
        whenever(strings.get(any<StringResource>())).thenAnswer { invocation ->
            val resource = invocation.getArgument<StringResource>(0)
            if (resource == Res.string.generic_error_message) {
                mockedGenericErrorMessage
            } else {
                mockedTransactionDetailsStrings.getValue(resource)
            }
        }
    }
    //endregion

    //region mocked objects
    private val mockedActionWebUrl = "https://example.com/contact"
    private val mockedActionMailUrl = "mailto:privacy+wallet@example.com"
    private val mockedActionPhoneUrl = "tel:+302101234567"
    private val mockedTransactionId = "mockedTransactionId"
    private val mockedCreationLocalDateTime: LocalDateTime =
        LocalDateTime(2026, 3, 15, 14, 30, 0)
    private val mockedIssuanceLabel = "Issuance"
    private val mockedPresentationLabel = "Presentation"
    private val mockedSigningLabel = "Signing"
    private val mockedCompletedLabel = "Completed"
    private val mockedNotCompletedLabel = "Not completed"
    //endregion
}