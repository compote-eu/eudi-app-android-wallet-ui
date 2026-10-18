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

package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.CommunicationMethodDomain
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.PresentationTransactionDataDomain
import eu.europa.ec.corelogic.model.QualifiedIdentifierDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import kotlinx.coroutines.test.runTest
import org.multipaz.presentment.CredentialQueryResult
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * One presentation's History row, by wallet-core 0.31.0's `PresentationLogListener` rules: a row once a
 * request the wallet can answer arrives, ended exactly once, with wallet-core's reasons.
 */
class IosPresentationLogTest {

    private suspend fun store(): MultipazWalletStore {
        val storage = EphemeralStorage()
        return MultipazWalletStore.build(storage = storage, secureAreas = listOf(SoftwareSecureArea.create(storage)))
    }

    /** A request whose matches do not matter to the rules under test. */
    private val request = CredentialQueryResult(credentialSets = emptyList())

    private suspend fun MultipazWalletStore.presentation() =
        assertIs<TransactionLogDomain.Presentation>(transactionLogs().single())

    @Test
    fun an_exchange_that_never_received_a_request_leaves_no_row() = runTest {
        val store = store()
        val log = IosPresentationLog { store }

        log.failed(IllegalStateException("The link carried no request."))
        log.stopped()

        assertTrue(store.transactionLogs().isEmpty())
    }

    @Test
    fun a_received_request_is_written_ahead_as_not_completed() = runTest {
        val store = store()

        IosPresentationLog { store }.requestReceived("Verifier Signer dev", party = null, request = request)

        val presentation = store.presentation()
        assertEquals(TransactionResultDomain.NotCompleted(null), presentation.result)
        assertEquals(LocalizedTextDomain("und", "Verifier Signer dev"), presentation.party.name)
    }

    @Test
    fun a_completed_exchange_stays_completed_through_the_teardown_that_follows() = runTest {
        val store = store()
        val log = IosPresentationLog { store }

        log.requestReceived("Verifier", party = null, request = request)
        log.completed()
        log.stopped()

        assertEquals(TransactionResultDomain.Completed, store.presentation().result)
    }

    @Test
    fun each_ending_carries_wallet_cores_reason() = runTest {
        val endings = listOf(
            "stopped" to REASON_STOPPED,
            "rejected" to REASON_VERIFIER_REJECTED,
            "failed without a message" to REASON_TRANSFER_ERROR,
            "failed" to "Connection reset",
        )
        for ((ending, reason) in endings) {
            val store = store()
            val log = IosPresentationLog { store }
            log.requestReceived("Verifier", party = null, request = request)

            when (ending) {
                "stopped" -> log.stopped()
                "rejected" -> log.rejected()
                "failed without a message" -> log.failed(IllegalStateException())
                else -> log.failed(IllegalStateException("Connection reset\nat …"))
            }

            assertEquals(TransactionResultDomain.NotCompleted(reason), store.presentation().result)
        }
    }

    @Test
    fun nothing_to_share_keeps_its_reason_through_the_stop() = runTest {
        val store = store()
        val log = IosPresentationLog { store }

        log.nothingToShare(requesterName = "Verifier", party = null)
        log.stopped()

        assertEquals(TransactionResultDomain.NotCompleted(REASON_REQUEST_NOT_SATISFIABLE), store.presentation().result)
    }

    @Test
    fun a_second_request_in_the_same_exchange_updates_the_same_row() = runTest {
        val store = store()
        val log = IosPresentationLog { store }

        log.requestReceived("Verifier", party = null, request = request)
        log.requestReceived("Verifier again", party = null, request = request)

        assertEquals("Verifier again", store.presentation().party.name?.text)
    }

    @Test
    fun a_registered_relying_party_is_recorded_as_wallet_core_records_it() = runTest {
        val store = store()
        val party = PresentationPartyRecord(
            name = "Verifier s.r.o.",
            identifier = QualifiedIdentifierRecord("http://data.europa.eu/eudi/id/LEI", "123456789"),
            contacts = listOf("SK", "https://verifier.example/support"),
            intermediaryName = "Intermediary",
            intermediaryIdentifier = QualifiedIdentifierRecord("http://data.europa.eu/eudi/id/EUID", "SK-1"),
            registrarUrl = "https://registrar.example",
            purpose = listOf(LocalizedTextRecord("en", "Age verification")),
            privacyPolicyUrls = listOf("https://verifier.example/privacy"),
            authorityName = "Data Protection Office",
            authorityContacts = listOf("dpo@example.sk"),
        )

        IosPresentationLog { store }.requestReceived("Certificate name", party, request)

        val presentation = store.presentation()
        // The registered name over what the consent screen showed.
        assertEquals("Verifier s.r.o.", presentation.party.name?.text)
        assertEquals(QualifiedIdentifierDomain("http://data.europa.eu/eudi/id/LEI", "123456789"), presentation.party.identifier)
        assertEquals("Intermediary", presentation.intermediary?.name?.text)
        assertEquals("Age verification", presentation.registration?.purpose)
        assertEquals("Data Protection Office", presentation.registration?.dpa?.name?.text)
    }

    @Test
    fun the_transaction_data_reads_back_as_the_consent_screen_read_it() {
        val record = IosTransactionRecord.Presentation(
            id = "p",
            timeEpochMillis = Clock.System.now().toEpochMilliseconds(),
            completed = true,
            transactionData = listOf(
                TransactionDataRecord(
                    type = QES_APPROVAL_TYPE,
                    json = """{"type":"$QES_APPROVAL_TYPE","credential_ids":["pid"],"credentialID":"c",""" +
                        """"numSignatures":1,"documentDigests":[{"hash":"AQID"}],"hashAlgorithmOID":"2.16.840.1.101.3.4.2.1"}""",
                    displayName = "QES approval",
                ),
                TransactionDataRecord(type = "urn:unknown", json = "{}"),
            ),
        )

        val presentation = assertIs<TransactionLogDomain.Presentation>(record.toDomain(languageCode = "en"))

        val approval = assertIs<PresentationTransactionDataDomain.QesApproval>(presentation.transactionData.first())
        assertEquals("QES approval", approval.displayName)
        assertEquals(1, approval.numSignatures)
        assertEquals(PresentationTransactionDataDomain.Unavailable, presentation.transactionData.last())
    }

    @Test
    fun a_deletion_request_can_be_filed_against_a_recorded_presentation() = runTest {
        // Android's cascade, and the parent check, apply to the wallet's own rows as to multipaz's events.
        val store = store()
        store.recordTransaction(
            IosTransactionRecord.Presentation(
                id = "presentation-1",
                timeEpochMillis = Clock.System.now().toEpochMilliseconds(),
                completed = true,
                requesterName = "Verifier",
                claimsPresented = listOf(
                    CredentialClaimsRecord(
                        credential = "eu.europa.ec.eudi.pid.1",
                        claims = listOf(listOf(ClaimSegmentRecord(key = "eu.europa.ec.eudi.pid.1"), ClaimSegmentRecord(key = "family_name"))),
                    ),
                ),
            )
        )
        val parent = assertIs<TransactionLogDomain.Presentation>(store.transactionLog("presentation-1"))
        val request = parent.toDataDeletionRequestRecord(
            id = "request-1",
            time = Clock.System.now(),
            communicationMethod = CommunicationMethodDomain.Email,
        )

        assertTrue(store.recordPresentationAction(request))
        val filed = assertIs<TransactionLogDomain.DataDeletionRequest>(store.presentationActions("presentation-1").single())
        assertEquals(
            listOf<ClaimPathSegment>(ClaimPathSegment.Key("eu.europa.ec.eudi.pid.1"), ClaimPathSegment.Key("family_name")),
            filed.claims.single().claims.single().segments,
        )

        store.deleteTransactionLog("presentation-1")

        assertTrue(store.presentationActions("presentation-1").isEmpty())
        assertNull(store.transactionLog("presentation-1"))
    }

    @Test
    fun multipaz_writes_no_presentation_events_of_its_own() = runTest {
        // Each presenter writes the row; an event logger here would add a second row for every exchange the
        // verifier accepted. multipaz writes that event asynchronously, so no end-to-end read would catch it.
        val source = walletPresentmentSource(
            store = store(),
            credentialDomain = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
            readerTrust = null,
            offersSdJwt = true,
            showConsent = { _, _, _ -> null },
        )

        assertNull(source.eventLogger)
    }
}
