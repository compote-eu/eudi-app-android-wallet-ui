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
import eu.europa.ec.shared.wallet.multipaz.harness.certifyWithFixtureIssuer
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.document.Document
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Which documents a sweep re-issues: those due by their own policy, and never a revoked or suspended one. */
class DueForReIssuanceTest {

    private val docType = "eu.europa.ec.eudi.pid.1"

    private suspend fun store(): MultipazWalletStore {
        val storage = EphemeralStorage()
        return MultipazWalletStore.build(storage = storage, secureAreas = listOf(SoftwareSecureArea.create(storage)))
    }

    /** A once-only PID of three holding one credential: below the re-issue threshold of two, so it is due. */
    private suspend fun MultipazWalletStore.pid(): Document {
        val metadata = EudiDocumentMetadata.create(
            documentManagerId = documentManagerId,
            format = StoredDocumentFormat.MsoMdoc(docType),
            credentialPolicy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 3, reissueTriggerUnused = 2),
        )
        metadata.issue()
        val document = documentStore.createDocument(displayName = "PID", metadata = metadata)
        MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = documentManagerId,
            secureArea = keySecureArea,
            docType = docType,
            createKeySettings = SoftwareCreateKeySettings.Builder().build(),
        ).certifyWithFixtureIssuer(docType = docType)
        return document
    }

    @Test
    fun a_revoked_document_is_not_re_issued() = runTest {
        val store = store()
        val pid = store.pid()
        val now = Clock.System.now()
        assertEquals(listOf(pid.identifier), dueForReIssuance(store, listOf(pid), now).map { it.identifier })

        // What the status check writes for a revoked or suspended document.
        store.revokedDocumentsTable().insert(key = pid.identifier, data = ByteString())

        // Upstream #729: the re-issued document would carry no revocation record, so the block would be lost.
        assertTrue(dueForReIssuance(store, listOf(pid), now).isEmpty())
    }
}
