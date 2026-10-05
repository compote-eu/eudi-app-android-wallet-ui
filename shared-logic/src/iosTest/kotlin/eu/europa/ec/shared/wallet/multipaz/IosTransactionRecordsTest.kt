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

import eu.europa.ec.corelogic.model.CredentialRefDomain
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.QualifiedIdentifierDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.MDOC_PID_DOC_TYPE
import eu.europa.ec.shared.wallet.multipaz.harness.sampleIssuerMetadata
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * The transactions the wallet records itself, read back by the History tab as wallet-core records them
 * on Android — here, the deletion of a credential (`CredentialDeletionLogger`).
 */
class IosTransactionRecordsTest {

    private suspend fun store(): MultipazWalletStore {
        val storage = EphemeralStorage()
        return MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
            documentManagerId = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
        )
    }

    private suspend fun MultipazWalletStore.seedPid(
        issued: Boolean = true,
        withIssuer: Boolean = true,
        issuerParty: IssuerPartyRecord? = null,
    ): String =
        seedMdocDocument(
            docType = MDOC_PID_DOC_TYPE,
            displayName = "PID MSO MDoc",
            namespace = MDOC_PID_DOC_TYPE,
            elements = samplePidElements(),
            policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
            issuerMetadata = sampleIssuerMetadata(MDOC_PID_DOC_TYPE).takeIf { withIssuer },
            markIssued = issued,
            issuerParty = issuerParty,
        )

    private val now = Clock.System.now()

    private fun deletion(id: String = "deletion-1", completed: Boolean = true, reason: String? = null) =
        IosTransactionRecord.Deletion(
            id = id,
            timeEpochMillis = now.toEpochMilliseconds(),
            completed = completed,
            reason = reason,
            credentialIdentifier = MDOC_PID_DOC_TYPE,
            issuerName = "Fixture Issuer",
            issuerNameLanguage = "en",
        )

    //region deletion

    @Test
    fun deleting_an_issued_document_records_its_deletion() = runTest {
        val store = store()
        val documentId = store.seedPid()

        assertTrue(MultipazWalletEngine(store).deleteDocument(documentId).isSuccess)

        val deletion = assertIs<TransactionLogDomain.CredentialDeletion>(store.transactionLogs().single())
        assertEquals(TransactionResultDomain.Completed, deletion.result)
        assertEquals(CredentialRefDomain(MDOC_PID_DOC_TYPE), deletion.credential)
        // wallet-core names the issuer by the first name its metadata gives, with that name's language.
        assertEquals(LocalizedTextDomain("en", "Fixture Issuer"), deletion.issuer.name)
        assertNull(deletion.issuer.identifier)
        assertTrue(deletion.issuer.contacts.isEmpty())
    }

    @Test
    fun deleting_a_document_that_was_never_issued_records_nothing() = runTest {
        // wallet-core logs only an IssuedDocument's deletion; a pending or deferred one leaves no row.
        val store = store()
        val documentId = store.seedPid(issued = false)

        assertTrue(MultipazWalletEngine(store).deleteDocument(documentId).isSuccess)

        assertTrue(store.transactionLogs().isEmpty())
    }

    @Test
    fun deleting_an_unknown_document_records_nothing() = runTest {
        val store = store()
        store.seedPid()

        assertTrue(MultipazWalletEngine(store).deleteDocument("no-such-document").isSuccess)

        assertTrue(store.transactionLogs().isEmpty())
    }

    @Test
    fun an_issuer_without_metadata_leaves_the_deletion_unnamed() = runTest {
        val store = store()
        val documentId = store.seedPid(withIssuer = false)

        MultipazWalletEngine(store).deleteDocument(documentId)

        val deletion = assertIs<TransactionLogDomain.CredentialDeletion>(store.transactionLogs().single())
        assertNull(deletion.issuer.name)
        assertEquals(CredentialRefDomain(MDOC_PID_DOC_TYPE), deletion.credential)
    }

    @Test
    fun an_issuer_whose_registration_was_verified_is_named_by_it_on_deletion() = runTest {
        // wallet-core keeps the registered issuer from the issuance (`IssuanceMetadata`) for this row.
        val store = store()
        val documentId = store.seedPid(
            issuerParty = IssuerPartyRecord(
                name = "Fixture Issuer s.r.o.",
                identifier = QualifiedIdentifierRecord("http://data.europa.eu/eudi/id/LEI", "123456789"),
                type = "PIDProvider",
            ),
        )

        MultipazWalletEngine(store).deleteDocument(documentId)

        val deletion = assertIs<TransactionLogDomain.CredentialDeletion>(store.transactionLogs().single())
        assertEquals(LocalizedTextDomain("und", "Fixture Issuer s.r.o."), deletion.issuer.name)
        assertEquals(
            QualifiedIdentifierDomain("http://data.europa.eu/eudi/id/LEI", "123456789"),
            deletion.issuer.identifier,
        )
    }

    @Test
    fun a_failed_deletion_is_kept_as_not_completed_with_its_reason() {
        val failed = deletion().after(Result.failure<Unit>(IllegalStateException("The store is read-only.")))

        assertEquals(
            TransactionResultDomain.NotCompleted("The store is read-only."),
            failed.toDomain().result,
        )
        assertEquals(deletion(), deletion().after(Result.success(Unit)))
    }

    //endregion

    //region the reason, as wallet-core shortens it

    @Test
    fun a_reason_is_the_first_line_of_the_message() {
        assertEquals("Disk full", IllegalStateException("Disk full\n  at storage").noncompletionReason("default"))
    }

    @Test
    fun a_reason_falls_back_to_the_cause_and_then_to_the_default() {
        val withCause = IllegalStateException(" ", IllegalArgumentException("Key not found"))
        assertEquals("Key not found", withCause.noncompletionReason("default"))

        val tooLong = IllegalStateException("x".repeat(121))
        assertEquals("default", tooLong.noncompletionReason("default"))
        assertEquals("default", IllegalStateException().noncompletionReason("default"))
    }

    //endregion

    //region the table

    @Test
    fun the_same_id_again_replaces_the_row() = runTest {
        val store = store()

        store.recordTransaction(deletion(completed = false, reason = "pending"))
        store.recordTransaction(deletion(completed = true))

        val deletion = assertIs<TransactionLogDomain.CredentialDeletion>(store.transactionLogs().single())
        assertEquals(TransactionResultDomain.Completed, deletion.result)
    }

    @Test
    fun recorded_transactions_are_listed_newest_first_among_the_rest() = runTest {
        val store = store()
        store.recordSigning(
            IosSigningRecord(
                id = "signing", signingTransactionId = null, timeEpochMillis = (now - 5.minutes).toEpochMilliseconds(),
                completed = true, reason = null, certificateIdentifier = null, dtbsr = null, fileName = null,
                fileSize = null, serviceName = null, serviceNameLanguage = null,
            ),
        )
        store.recordTransaction(deletion(id = "deletion"))

        assertEquals(listOf("deletion", "signing"), store.transactionLogs().map { it.id })
    }

    @Test
    fun deleting_the_row_from_the_history_removes_it() = runTest {
        val store = store()
        store.recordTransaction(deletion())

        store.deleteTransactionLog("deletion-1")

        assertTrue(store.transactionLogs().isEmpty())
    }

    @Test
    fun a_row_this_version_cannot_read_is_skipped() = runTest {
        val store = store()
        store.transactionRecordsTable().insert(key = "garbage", data = "{not json".encodeToByteString())
        store.recordTransaction(deletion())

        assertEquals(listOf("deletion-1"), store.transactionLogs().map { it.id })
    }

    //endregion
}
