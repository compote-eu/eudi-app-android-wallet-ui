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

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.MDOC_PID_DOC_TYPE
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import kotlinx.coroutines.test.runTest
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A wallet deletes only its own documents, even when another document manager shares its storage (as wallet-core #453). */
class DocumentDeletionScopeTest {

    private suspend fun MultipazWalletStore.seedPid(): String = seedMdocDocument(
        docType = MDOC_PID_DOC_TYPE,
        displayName = "PID MSO MDoc",
        namespace = MDOC_PID_DOC_TYPE,
        elements = samplePidElements(),
        policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
    )

    @Test
    fun another_document_managers_document_is_left_alone_as_a_missing_one_is() = runTest {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val theirs = MultipazWalletStore.build(storage = storage, secureAreas = listOf(secureArea), documentManagerId = "theirs")
        val theirDocument = theirs.seedPid()
        // Built after theirs wrote, so its document store reads both managers' documents from the shared storage.
        val ours = MultipazWalletStore.build(storage = storage, secureAreas = listOf(secureArea), documentManagerId = "ours")
        val ourDocument = ours.seedPid()
        assertNotNull(ours.documentStore.lookupDocument(theirDocument), "the shared storage shows their document here too")

        assertTrue(MultipazWalletEngine(ours).deleteDocument(theirDocument).isSuccess)
        assertNotNull(ours.documentStore.lookupDocument(theirDocument))

        assertTrue(MultipazWalletEngine(ours).deleteDocument(ourDocument).isSuccess)
        assertNull(ours.documentStore.lookupDocument(ourDocument))
    }
}
