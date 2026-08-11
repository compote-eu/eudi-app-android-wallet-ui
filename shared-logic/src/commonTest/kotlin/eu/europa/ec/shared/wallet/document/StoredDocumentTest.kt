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

package eu.europa.ec.shared.wallet.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The credential-selection logic ported from `IssuedDocument.getCredentials()` — the ~120 lines that
 * are the *actual* substance of the Android document manager's read path. Runs on both Android (JVM)
 * and iOS (Kotlin/Native) from the same source.
 */
class StoredDocumentTest {

    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val managerId = "eudi-wallet"

    private fun credential(
        alias: String,
        domain: String = managerId,
        usageCount: Int = 0,
        validUntil: Instant = now + 30.days,
        isInvalidated: Boolean = false,
    ) = StoredCredential(
        alias = alias,
        domain = domain,
        usageCount = usageCount,
        validFrom = now - 1.days,
        validUntil = validUntil,
        isInvalidated = isInvalidated,
    )

    private fun document(
        policy: WalletCredentialPolicy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 5),
        credentials: List<StoredCredential> = emptyList(),
    ) = StoredDocument(
        id = "doc-1",
        name = "PID",
        formatType = "eu.europa.ec.eudi.pid.1",
        documentManagerId = managerId,
        policy = policy,
        issuedAt = now - 1.days,
        certifiedCredentials = credentials,
    )

    //region usableCredentials

    @Test
    fun an_invalidated_credential_is_excluded() {
        val doc = document(
            credentials = listOf(
                credential("a"),
                credential("b", isInvalidated = true),
            ),
        )

        assertEquals(listOf("a"), doc.usableCredentials().map { it.alias })
    }

    @Test
    fun a_credential_belonging_to_another_document_manager_is_excluded() {
        val doc = document(
            credentials = listOf(
                credential("mine"),
                credential("theirs", domain = "some-other-manager"),
            ),
        )

        assertEquals(listOf("mine"), doc.usableCredentials().map { it.alias })
    }

    @Test
    fun a_rotating_batch_keeps_credentials_that_have_already_been_presented() {
        val doc = document(
            policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 3),
            credentials = listOf(credential("a", usageCount = 0), credential("b", usageCount = 7)),
        )

        assertEquals(listOf("a", "b"), doc.usableCredentials().map { it.alias })
    }

    @Test
    fun a_once_only_batch_drops_credentials_that_have_been_presented() {
        val doc = document(
            policy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 3),
            credentials = listOf(credential("fresh"), credential("used", usageCount = 1)),
        )

        assertEquals(listOf("fresh"), doc.usableCredentials().map { it.alias })
    }

    @Test
    fun a_limited_time_policy_keeps_a_presented_credential_like_a_rotating_batch() {
        val doc = document(
            policy = WalletCredentialPolicy.LimitedTime(),
            credentials = listOf(credential("only", usageCount = 4)),
        )

        assertEquals(listOf("only"), doc.usableCredentials().map { it.alias })
    }

    @Test
    fun an_expired_credential_is_still_counted_as_usable() {
        // Faithful to the original: getCredentials() does NOT filter on temporal validity. This is
        // what lets an expired document report an expiry date at all instead of looking exhausted.
        val doc = document(credentials = listOf(credential("old", validUntil = now - 10.days)))

        assertEquals(listOf("old"), doc.usableCredentials().map { it.alias })
    }

    //endregion

    //region expiry

    @Test
    fun expiry_is_the_latest_validUntil_across_credentials() {
        val credentials = listOf(
            credential("a", validUntil = now + 5.days),
            credential("c", validUntil = now + 40.days),
            credential("b", validUntil = now + 12.days),
        )

        assertEquals(now + 40.days, credentials.walletExpiresAt())
    }

    @Test
    fun expiry_survives_into_the_past_once_every_credential_has_expired() {
        val credentials = listOf(
            credential("a", validUntil = now - 30.days),
            credential("b", validUntil = now - 3.days),
        )

        assertEquals(now - 3.days, credentials.walletExpiresAt())
    }

    @Test
    fun a_document_with_no_credentials_has_no_expiry() {
        assertNull(emptyList<StoredCredential>().walletExpiresAt())
    }

    //endregion

    //region low on credentials

    @Test
    fun only_a_once_only_policy_can_be_low_on_credentials() {
        assertTrue(isLowOnCredentials(WalletCredentialPolicy.OnceOnly(numberOfCredentials = 5), 1))
        assertFalse(
            isLowOnCredentials(WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 5), 1),
        )
        assertFalse(isLowOnCredentials(WalletCredentialPolicy.LimitedTime(), 1))
    }

    @Test
    fun a_once_only_document_is_low_at_one_or_zero_remaining_but_not_at_two() {
        val policy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 5)

        assertTrue(isLowOnCredentials(policy, 0))
        assertTrue(isLowOnCredentials(policy, 1))
        assertFalse(isLowOnCredentials(policy, 2))
    }

    //endregion

    //region initial credential count

    @Test
    fun the_initial_credential_count_comes_from_the_policy_and_limited_time_is_always_one() {
        assertEquals(7, WalletCredentialPolicy.OnceOnly(numberOfCredentials = 7).numberOfCredentials)
        assertEquals(
            4,
            WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 4).numberOfCredentials,
        )
        assertEquals(1, WalletCredentialPolicy.LimitedTime().numberOfCredentials)
    }

    //endregion
}
