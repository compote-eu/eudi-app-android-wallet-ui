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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a document keeps of its issuance for the History: who issued it, and whether the user asked. */
class EudiDocumentIssuerPartyTest {

    private val party = IssuerPartyRecord(
        name = "Fixture Issuer s.r.o.",
        identifier = QualifiedIdentifierRecord("http://data.europa.eu/eudi/id/LEI", "123456789"),
        type = "PIDProvider",
        contacts = listOf("SK", "https://fixture.example/support"),
    )

    private fun metadata(issuerParty: IssuerPartyRecord? = party, userTriggered: Boolean? = true) =
        EudiDocumentMetadata.create(
            documentManagerId = "manager",
            format = StoredDocumentFormat.MsoMdoc("eu.europa.ec.eudi.pid.1"),
            credentialPolicy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 1),
            issuerParty = issuerParty,
            userTriggered = userTriggered,
        )

    @Test
    fun the_issuer_and_the_user_triggered_flag_survive_storage() {
        val restored = EudiDocumentMetadata.restore("id", metadata().serialize())

        assertEquals(party, restored.issuerParty)
        assertEquals(true, restored.userTriggered)
    }

    @Test
    fun a_document_stored_before_they_were_kept_reads_without_them() {
        val restored = EudiDocumentMetadata.restore("id", metadata(issuerParty = null, userTriggered = null).serialize())

        assertNull(restored.issuerParty)
        assertNull(restored.userTriggered)
    }

    @Test
    fun issuing_and_parking_the_document_keep_them() {
        val metadata = metadata(userTriggered = false)

        metadata.park("txn-1")
        metadata.issue()
        metadata.completeDeferred()
        val restored = EudiDocumentMetadata.restore("id", metadata.serialize())

        assertEquals(party, restored.issuerParty)
        assertEquals(false, restored.userTriggered)
    }
}
