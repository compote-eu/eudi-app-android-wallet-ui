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

import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import kotlinx.coroutines.test.runTest
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * The documents signed through the RQES flow, as the signing SDK reports them from Swift and the History
 * tab reads them back — field for field what Android's wallet-core records.
 */
class IosSigningRecordsTest {

    private suspend fun store(): MultipazWalletStore {
        val storage = EphemeralStorage()
        return MultipazWalletStore.build(storage = storage, secureAreas = listOf(SoftwareSecureArea.create(storage)))
    }

    private val now = Clock.System.now()

    private fun record(
        id: String = "run-1:0",
        completed: Boolean = true,
        reason: String? = null,
        timeEpochMillis: Long = now.toEpochMilliseconds(),
    ) = IosSigningRecord(
        id = id,
        signingTransactionId = "run-1",
        timeEpochMillis = timeEpochMillis,
        completed = completed,
        reason = reason,
        certificateIdentifier = "certificate-7",
        dtbsr = "q1w2e3",
        fileName = "contract.pdf",
        fileSize = "20480",
        serviceName = "Wallet-Centric",
        serviceNameLanguage = "en",
    )

    @Test
    fun a_signed_document_appears_in_the_history_as_a_signing() = runTest {
        val store = store()

        assertTrue(store.recordSigning(record()))

        val signing = assertIs<TransactionLogDomain.SigningSealing>(store.transactionLogs().single())
        assertEquals("run-1:0", signing.id)
        assertEquals(TransactionResultDomain.Completed, signing.result)
        assertEquals(LocalizedTextDomain("en", "Wallet-Centric"), signing.service.name)
        assertEquals("run-1", signing.signingTransactionId)
        assertEquals("certificate-7", signing.certificateSerialNumber)
        assertEquals("contract.pdf", signing.fileName)
        assertEquals(20480L, signing.fileSizeBytes)
        assertEquals("q1w2e3", signing.dtbsr)
        // As Android records it: a signing service has no type shown, and no identifier or contact.
        assertNull(signing.serviceType)
        assertNull(signing.service.identifier)
    }

    @Test
    fun a_failed_signing_is_kept_with_its_reason() = runTest {
        val store = store()

        store.recordSigning(record(completed = false, reason = "The QTSP refused the credential."))

        val signing = assertIs<TransactionLogDomain.SigningSealing>(store.transactionLog("run-1:0"))
        assertEquals(TransactionResultDomain.NotCompleted("The QTSP refused the credential."), signing.result)
    }

    @Test
    fun the_same_id_again_replaces_the_entry() = runTest {
        // The SDK's rule: "repeated calls for the same identifier replace the previous snapshot".
        val store = store()

        store.recordSigning(record(completed = false, reason = "pending"))
        store.recordSigning(record(completed = true))

        val signing = assertIs<TransactionLogDomain.SigningSealing>(store.transactionLogs().single())
        assertEquals(TransactionResultDomain.Completed, signing.result)
    }

    @Test
    fun signings_are_listed_newest_first() = runTest {
        val store = store()

        store.recordSigning(record(id = "older", timeEpochMillis = (now - 5.minutes).toEpochMilliseconds()))
        store.recordSigning(record(id = "newer"))

        assertEquals(listOf("newer", "older"), store.transactionLogs().map { it.id })
    }

    @Test
    fun deleting_a_signing_from_the_history_removes_it() = runTest {
        val store = store()
        store.recordSigning(record())

        store.deleteTransactionLog("run-1:0")

        assertTrue(store.transactionLogs().isEmpty())
    }

    @Test
    fun a_record_with_no_id_is_not_stored() = runTest {
        val store = store()

        assertFalse(store.recordSigning(record(id = " ")))
        assertTrue(store.transactionLogs().isEmpty())
    }
}
