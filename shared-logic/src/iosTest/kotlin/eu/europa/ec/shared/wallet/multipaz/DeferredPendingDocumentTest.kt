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

import eu.europa.ec.shared.wallet.WalletDocumentIssuanceState
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.certifyWithFixtureIssuer
import kotlinx.coroutines.test.runTest
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Can this wallet **park a document that the issuer has not issued yet, and finish it later?**
 *
 * That is the one open question behind deferred issuance (OpenID4VCI's `transaction_id` flow, multipaz
 * #1948). Collecting the credential is HTTP work at a seam we already own; what was never established
 * is whether a document can sit in the store with **no credential at all**, survive the app being
 * killed, still carry the handle the issuer wants back, and then become an ordinary issued document.
 *
 * Nothing here pretends to issue anything. The issuer's half is a fixture; the subject is the store.
 */
class DeferredPendingDocumentTest {

    private val docType = "eu.europa.ec.eudi.pid.1"
    private val policy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 1)

    private suspend fun storeOver(storage: Storage): MultipazWalletStore =
        MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )

    /**
     * Parks a document carrying only the issuer's deferred handle — the exact state a `202 Accepted`
     * with a `transaction_id` should leave behind.
     */
    private suspend fun MultipazWalletStore.parkDeferred(transactionId: String): String =
        documentStore.createDocument(
            displayName = "PID (deferred)",
            metadata = EudiDocumentMetadata.create(
                documentManagerId = documentManagerId,
                format = StoredDocumentFormat.MsoMdoc(docType),
                credentialPolicy = policy,
                deferredTransactionId = transactionId,
            ),
        ).identifier

    @Test
    fun a_parked_document_reads_as_pending_and_keeps_its_transaction_id_across_a_restart() = runTest {
        // One storage, two stores: the second stands in for the next app launch, so nothing can be
        // answered out of the first store's in-memory cache.
        val storage = EphemeralStorage()
        val parkedId = storeOver(storage).parkDeferred(transactionId = "txn-abc-123")

        val afterRestart = storeOver(storage)

        val read = MultipazWalletEngine(afterRestart)
            .getAllDocumentsWithDetails(locale = "en")
            .single()
        assertEquals(parkedId, read.id)
        assertEquals(
            WalletDocumentIssuanceState.Pending,
            read.issuanceState,
            "a document with no credential must survive a restart as Pending",
        )
        assertNull(read.issuedAt)
        assertEquals(0, read.credentialsCount)

        // The handle is the whole point: without it a later poll has nothing to ask the issuer for.
        val metadata = assertNotNull(afterRestart.documentStore.lookupDocument(parkedId)?.eudiMetadata)
        assertEquals("txn-abc-123", metadata.deferredTransactionId)
    }

    @Test
    fun a_parked_document_becomes_an_ordinary_issued_document_when_the_credential_arrives() = runTest {
        val storage = EphemeralStorage()
        val parkedId = storeOver(storage).parkDeferred(transactionId = "txn-abc-123")

        // The next launch collects the deferred credential and completes the document.
        val afterRestart = storeOver(storage)
        val document = assertNotNull(afterRestart.documentStore.lookupDocument(parkedId))

        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = afterRestart.documentManagerId,
            secureArea = afterRestart.keySecureArea,
            docType = docType,
            createKeySettings = SoftwareCreateKeySettings.Builder().build(),
        )
        credential.certifyWithFixtureIssuer(docType = docType)

        val metadata = assertNotNull(document.eudiMetadata)
        metadata.issue()
        metadata.completeDeferred()
        document.edit { this.metadata = metadata }

        val read = MultipazWalletEngine(afterRestart)
            .getAllDocumentsWithDetails(locale = "en")
            .single()
        assertEquals(
            WalletDocumentIssuanceState.Issued,
            read.issuanceState,
            "a completed deferred document must be indistinguishable from a normal one",
        )
        assertEquals(1, read.credentialsCount)
        assertNotNull(read.issuedAt)

        // And it must leave the deferred queue, or the wallet polls a transaction that is done.
        val reloaded = assertNotNull(
            storeOver(storage).documentStore.lookupDocument(parkedId)?.eudiMetadata,
        )
        assertNull(reloaded.deferredTransactionId)
    }
    /**
     * The seam that makes deferred issuance reachable at all.
     *
     * multipaz deletes a document whose initial provisioning threw, and a `202 Accepted` reaches that
     * path — because multipaz treats it as an error. Deleting would take the **pending credentials**
     * with it, and those carry the keys the issuer minted the credential against, so the flow would end
     * there. The handler keeps the document instead and stamps the handle.
     */
    @Test
    fun a_deferred_issuance_parks_the_document_instead_of_deleting_it() = runTest {
        val store = storeOver(EphemeralStorage())
        val notice = DeferredIssuanceNotice().apply { transactionId = "txn-from-the-issuer" }
        val handler = IosDocumentProvisioningHandler(store, deferred = notice)
        val document = store.documentStore.createDocument(
            displayName = "PID (about to be deferred)",
            metadata = EudiDocumentMetadata.create(
                documentManagerId = store.documentManagerId,
                format = StoredDocumentFormat.MsoMdoc(docType),
                credentialPolicy = policy,
            ),
        )

        handler.cleanupDocumentOnError(document, IllegalStateException("202 Accepted"))

        val kept = assertNotNull(
            store.documentStore.lookupDocument(document.identifier),
            "a deferred document must survive the failure path that would delete a real one",
        )
        assertEquals("txn-from-the-issuer", assertNotNull(kept.eudiMetadata).deferredTransactionId)
        // The caller needs to know which document to report, and only the handler saw it.
        assertEquals(document.identifier, notice.parkedDocumentId)
    }

    /** The control: an ordinary failure must still delete, or every failed issuance leaves a husk. */
    @Test
    fun an_ordinary_failure_still_deletes_the_document() = runTest {
        val store = storeOver(EphemeralStorage())
        val notice = DeferredIssuanceNotice()
        val handler = IosDocumentProvisioningHandler(store, deferred = notice)
        val document = store.documentStore.createDocument(
            displayName = "PID (failed)",
            metadata = EudiDocumentMetadata.create(
                documentManagerId = store.documentManagerId,
                format = StoredDocumentFormat.MsoMdoc(docType),
                credentialPolicy = policy,
            ),
        )

        handler.cleanupDocumentOnError(document, IllegalStateException("the issuer refused"))

        assertNull(store.documentStore.lookupDocument(document.identifier))
        assertNull(notice.parkedDocumentId)
    }
}
