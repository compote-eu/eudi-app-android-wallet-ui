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

import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.TransactionLogDataDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import eu.europa.ec.eudi.wallet.transactionLogging.TransactionLog
import eu.europa.ec.eudi.wallet.transactionLogging.presentation.PresentedClaim
import eu.europa.ec.eudi.wallet.transactionLogging.presentation.PresentedDocument
import eu.europa.ec.testfeature.util.mockedMdocPidDocType
import eu.europa.ec.testfeature.util.mockedMdocPidFormat
import eu.europa.ec.testfeature.util.mockedSdJwtPidFormat
import eu.europa.ec.testfeature.util.mockedSdJwtPidVct
import eu.europa.ec.testlogic.extension.runTest
import eu.europa.ec.testlogic.rule.CoroutineTestRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The adapter from wallet-core 0.30.2's presentation-only log to the shared domain. It goes with the
 * wallet-core 0.31.0 bump, and so does this test.
 */
class TestAndroidTransactionsPlatformBridge {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    @Mock
    private lateinit var walletCoreDocumentsController: WalletCoreDocumentsController

    private lateinit var bridge: AndroidTransactionsPlatformBridge

    private lateinit var closeable: AutoCloseable

    @Before
    fun before() {
        closeable = MockitoAnnotations.openMocks(this)
        bridge = AndroidTransactionsPlatformBridge(walletCoreDocumentsController)
    }

    @After
    fun after() {
        closeable.close()
    }

    private fun presentationLog(status: TransactionLog.Status, partyName: String) =
        TransactionLogDataDomain.PresentationLog(
            id = "presentation",
            name = partyName,
            status = status,
            creationLocalDateTime = LocalDateTime.of(2026, 3, 15, 14, 30),
            creationLocalDate = LocalDateTime.of(2026, 3, 15, 14, 30).toLocalDate(),
            relyingParty = TransactionLog.RelyingParty(
                name = partyName,
                isVerified = true,
                certificateChain = emptyList(),
                readerAuth = null,
            ),
            documents = listOf(
                PresentedDocument(
                    format = mockedMdocPidFormat,
                    metadata = null,
                    claims = listOf(
                        PresentedClaim(
                            path = listOf("eu.europa.ec.eudi.pid.1", "family_name"),
                            value = "ANDERSSON",
                            rawValue = "ANDERSSON",
                            metadata = null,
                        ),
                    ),
                ),
                PresentedDocument(
                    format = mockedSdJwtPidFormat,
                    metadata = null,
                    claims = listOf(
                        PresentedClaim(
                            path = listOf("address", "street_address"),
                            value = "Main 1",
                            rawValue = "Main 1",
                            metadata = null,
                        ),
                    ),
                ),
            ),
        )

    @Test
    fun `Given a presentation log, When read, Then it becomes a presentation with its claim paths and types`() {
        coroutineRule.runTest {
            whenever(walletCoreDocumentsController.getTransactionLogs()).thenReturn(
                listOf(presentationLog(TransactionLog.Status.Completed, "Verifier"))
            )

            val presentation = bridge.getTransactionLogs().single() as TransactionLogDomain.Presentation

            assertEquals(TransactionResultDomain.Completed, presentation.result)
            assertEquals("Verifier", presentation.party.name?.text)
            assertEquals(
                listOf(mockedMdocPidDocType, mockedSdJwtPidVct),
                presentation.claimsPresented.map { it.credential.identifier },
            )
            assertEquals(
                listOf(ClaimPathSegment.Key("eu.europa.ec.eudi.pid.1"), ClaimPathSegment.Key("family_name")),
                presentation.claimsPresented.first().claims.single().segments,
            )
            assertEquals(presentation.claimsPresented, presentation.claimsRequested)
        }
    }

    @Test
    fun `Given an incomplete or failed presentation and a blank name, When read, Then it is not completed and unnamed`() {
        coroutineRule.runTest {
            whenever(walletCoreDocumentsController.getTransactionLogs()).thenReturn(
                listOf(
                    presentationLog(TransactionLog.Status.Incomplete, " "),
                    presentationLog(TransactionLog.Status.Error, ""),
                )
            )

            val transactions = bridge.getTransactionLogs()

            assertTrue(transactions.all { it.result == TransactionResultDomain.NotCompleted(reason = null) })
            assertTrue(transactions.all { (it as TransactionLogDomain.Presentation).party.name == null })
        }
    }

    @Test
    fun `Given issuance and signing logs, When read, Then they are dropped`() {
        coroutineRule.runTest {
            val time = LocalDateTime.of(2026, 3, 15, 14, 30)
            whenever(walletCoreDocumentsController.getTransactionLogs()).thenReturn(
                listOf(
                    TransactionLogDataDomain.IssuanceLog(
                        "i", "Issuance", TransactionLog.Status.Completed, time, time.toLocalDate()
                    ),
                    TransactionLogDataDomain.SigningLog(
                        "s", "Signing", TransactionLog.Status.Completed, time, time.toLocalDate()
                    ),
                )
            )

            assertNull(bridge.getTransactionLogs().firstOrNull())
        }
    }
}
