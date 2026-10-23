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

import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.MDOC_PID_DOC_TYPE
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.sampleIssuerMetadata
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * What a re-issuance does around the document it replaces, by upstream's rules: wallet-core deletes the
 * original once the new one is issued, and #729 (`20dca6db`) carries the bookmark over, deletes the
 * original's records and checks the new one's status when the original was found revoked meanwhile.
 */
class DocumentReplacementTest {

    private suspend fun store(storage: Storage = EphemeralStorage()): MultipazWalletStore =
        MultipazWalletStore.build(storage = storage, secureAreas = listOf(SoftwareSecureArea.create(storage)))

    /** An issued once-only PID of two unused credentials: at the threshold of two, so due. */
    private suspend fun MultipazWalletStore.pid(name: String = "PID"): String = seedMdocDocument(
        docType = MDOC_PID_DOC_TYPE,
        displayName = name,
        namespace = MDOC_PID_DOC_TYPE,
        elements = samplePidElements(),
        policy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 2, reissueTriggerUnused = 2),
        issuerMetadata = sampleIssuerMetadata(MDOC_PID_DOC_TYPE),
    )

    /** A document a deferred re-issuance parked to replace [originalId]. */
    private suspend fun MultipazWalletStore.parkedReplacementOf(originalId: String): String =
        documentStore.createDocument(
            displayName = "PID (deferred)",
            metadata = EudiDocumentMetadata.create(
                documentManagerId = documentManagerId,
                format = StoredDocumentFormat.MsoMdoc(MDOC_PID_DOC_TYPE),
                credentialPolicy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 3, reissueTriggerUnused = 2),
                deferredTransactionId = "tx",
                replacesDocumentId = originalId,
            ),
        ).identifier

    @Test
    fun the_new_document_takes_the_originals_bookmark() = runTest {
        val store = store()
        val engine = MultipazWalletEngine(store)
        val original = store.pid()
        val replacement = store.pid()
        engine.storeBookmark(original)

        engine.carryOverToReplacement(original, listOf(replacement))

        assertTrue(engine.isDocumentBookmarked(replacement))
    }

    @Test
    fun an_original_without_a_bookmark_gives_none() = runTest {
        val store = store()
        val engine = MultipazWalletEngine(store)
        val original = store.pid()
        val replacement = store.pid()

        engine.carryOverToReplacement(original, listOf(replacement))

        assertFalse(engine.isDocumentBookmarked(replacement))
    }

    @Test
    fun retiring_the_original_deletes_it_with_its_records_and_records_the_deletion() = runTest {
        val store = store()
        val engine = MultipazWalletEngine(store, userLanguage = { "en" })
        val original = store.pid()
        engine.storeBookmark(original)
        var revocationChecks = 0

        engine.retireReplaced(original) { revocationChecks++ }

        assertNull(store.documentStore.lookupDocument(original))
        assertFalse(engine.isDocumentBookmarked(original))
        // The History row Android's deletion logger writes when wallet-core deletes the original.
        assertIs<TransactionLogDomain.CredentialDeletion>(store.transactionLogs().single())
        assertEquals(0, revocationChecks, "nothing was revoked, so there is nothing to check sooner")
    }

    @Test
    fun an_original_revoked_while_it_was_re_issued_asks_for_a_status_check_and_takes_its_record_along() = runTest {
        val store = store()
        val engine = MultipazWalletEngine(store)
        val original = store.pid()
        store.revokedDocumentsTable().insert(key = original, data = ByteString())
        var revocationChecks = 0

        engine.retireReplaced(original) { revocationChecks++ }

        assertEquals(1, revocationChecks)
        assertFalse(engine.isDocumentRevoked(original), "a deleted document's revocation record goes with it")
    }

    @Test
    fun a_parked_re_issuance_names_the_document_it_replaces_across_a_restart() = runTest {
        val storage = EphemeralStorage()
        val parked = store(storage).parkedReplacementOf(originalId = "the-original")

        val reopened = store(storage)

        assertEquals("the-original", reopened.documentStore.lookupDocument(parked)?.eudiMetadata?.replacesDocumentId)
        assertEquals(setOf("the-original"), reopened.documentsAwaitingReplacement())
    }

    @Test
    fun an_original_with_a_replacement_on_the_way_is_not_re_issued_again() = runTest {
        val store = store()
        val original = store.pid()
        suspend fun documents() = store.documentStore.listDocuments()
        val now = Clock.System.now()
        assertTrue(dueForReIssuance(store, documents(), now).any { it.identifier == original })

        store.parkedReplacementOf(original)

        // It stays due until the replacement arrives; another re-issuance would only park another one.
        assertFalse(dueForReIssuance(store, documents(), now).any { it.identifier == original })
    }
}
