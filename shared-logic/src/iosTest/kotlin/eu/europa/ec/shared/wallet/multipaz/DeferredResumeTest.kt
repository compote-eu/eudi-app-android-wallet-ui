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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The access token a parked document keeps when its issuer granted no refresh token: that it survives the
 * store, that older documents without it still decode, and that it is dropped with the handle.
 */
class DeferredResumeTest {

    private fun metadata() = EudiDocumentMetadata.create(
        documentManagerId = "manager",
        format = StoredDocumentFormat.MsoMdoc("eu.europa.ec.eudi.pid.1"),
        credentialPolicy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 1),
    )

    private val resume = DeferredResume(
        accessToken = "at-1",
        dpopKeyAlias = "vci-dpop-1",
        expiresAt = Instant.fromEpochSeconds(1_790_000_000),
    )

    @Test
    fun a_parked_documents_access_token_survives_the_store() {
        val parked = metadata().apply { park("txn-1", resume) }

        val restored = EudiDocumentMetadata.restore("doc", parked.serialize())

        assertEquals("txn-1", restored.deferredTransactionId)
        assertEquals(resume, restored.deferredResume)
    }

    @Test
    fun a_document_parked_before_this_existed_decodes_with_no_access_token() {
        val older = metadata().apply { park("txn-1", resume = null) }

        val restored = EudiDocumentMetadata.restore("doc", older.serialize())

        assertEquals("txn-1", restored.deferredTransactionId)
        assertNull(restored.deferredResume)
    }

    @Test
    fun a_rotated_handle_keeps_the_access_token_and_completion_drops_it() {
        val parked = metadata().apply { park("txn-1", resume) }

        parked.park("txn-2")
        assertEquals(resume, parked.deferredResume, "the issuer's new handle does not change the token")

        parked.completeDeferred()
        assertNull(parked.deferredTransactionId)
        assertNull(parked.deferredResume)
    }

    @Test
    fun expiry_is_the_token_responses_own() {
        val now = Clock.System.now()
        assertTrue(resume.copy(expiresAt = now - 1.minutes).isExpired(now))
        assertFalse(resume.copy(expiresAt = now + 1.minutes).isExpired(now))
        // An issuer that states no lifetime is taken at its word until the deferred endpoint refuses.
        assertFalse(resume.copy(expiresAt = null).isExpired(now))
    }

    @Test
    fun the_token_never_appears_in_a_log_line() {
        assertFalse("at-1" in resume.toString())
    }
}
