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

import eu.europa.ec.shared.wallet.WalletDocumentIssuanceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The KMP counterpart of `TestWalletEngineImpl`'s mapping cases: the same document shapes, asserted
 * against the same expectations, so a divergence between the two platforms' list projections shows
 * up as a failing test rather than as a different-looking Documents screen. Runs on both Android
 * (JVM) and iOS (Kotlin/Native) from the same source.
 */
class WalletDocumentProjectionTest {

    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val issuedAt = Instant.parse("2026-08-01T09:30:00Z")
    private val managerId = "eudi-wallet"

    private val issuerMetadata = IssuerMetadata(
        documentConfigurationIdentifier = "eu.europa.ec.eudi.pid_mdoc",
        credentialIssuerIdentifier = "https://issuer.example",
        issuerDisplay = listOf(
            IssuerMetadata.IssuerDisplay(
                name = "Test Issuer",
                locale = "en",
                logo = IssuerMetadata.Logo(uri = "https://issuer.example/en.png"),
            ),
            IssuerMetadata.IssuerDisplay(
                name = "Testovaci vydavatel",
                locale = "sk",
                logo = IssuerMetadata.Logo(uri = "https://issuer.example/sk.png"),
            ),
        ),
    )

    private fun credential(
        alias: String,
        usageCount: Int = 0,
        validUntil: Instant = now + 30.days,
        domain: String = managerId,
        isInvalidated: Boolean = false,
    ) = StoredCredential(
        alias = alias,
        domain = domain,
        usageCount = usageCount,
        validFrom = issuedAt,
        validUntil = validUntil,
        isInvalidated = isInvalidated,
    )

    private fun issuedDocument(
        policy: WalletCredentialPolicy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 7),
        credentials: List<StoredCredential> = listOf(credential("a"), credential("b")),
        claims: Map<String, String> = emptyMap(),
        metadata: IssuerMetadata? = issuerMetadata,
    ) = StoredDocument(
        id = "doc-1",
        name = "PID MSO MDoc",
        formatType = "eu.europa.ec.eudi.pid.1",
        documentManagerId = managerId,
        policy = policy,
        issuedAt = issuedAt,
        claims = claims,
        certifiedCredentials = credentials,
        issuerMetadata = metadata,
    )

    @Test
    fun an_issued_document_maps_every_list_field() {
        val mapped = issuedDocument().toWalletDocument(locale = "en", now = now)

        assertEquals("doc-1", mapped.id)
        assertEquals("PID MSO MDoc", mapped.name)
        assertEquals("eu.europa.ec.eudi.pid.1", mapped.formatType)
        assertEquals(WalletDocumentIssuanceState.Issued, mapped.issuanceState)
        assertEquals(issuedAt, mapped.issuedAt)
        assertEquals(now + 30.days, mapped.expiresAt)
        assertFalse(mapped.isExpired)
        assertFalse(mapped.isRevoked)
        assertEquals(2, mapped.credentialsCount)
        assertEquals(7, mapped.initialCredentialsCount)
        assertFalse(mapped.isLowOnCredentials)
        assertEquals("Test Issuer", mapped.issuerName)
        assertEquals("https://issuer.example/en.png", mapped.issuerLogoUri)
    }

    @Test
    fun the_requested_locale_picks_the_issuer_display() {
        val mapped = issuedDocument().toWalletDocument(locale = "sk-SK", now = now)

        assertEquals("Testovaci vydavatel", mapped.issuerName)
        assertEquals("https://issuer.example/sk.png", mapped.issuerLogoUri)
    }

    @Test
    fun a_document_with_no_issuer_metadata_leaves_the_issuer_fields_null() {
        val mapped = issuedDocument(metadata = null).toWalletDocument(locale = "en", now = now)

        assertNull(mapped.issuerName)
        assertNull(mapped.issuerLogoUri)
    }

    @Test
    fun a_document_whose_credentials_have_all_expired_is_expired_and_keeps_its_expiry() {
        val mapped = issuedDocument(
            credentials = listOf(
                credential("a", validUntil = now - 20.days),
                credential("b", validUntil = now - 2.days),
            ),
        ).toWalletDocument(locale = "en", now = now)

        assertTrue(mapped.isExpired)
        assertEquals(now - 2.days, mapped.expiresAt)
        assertEquals(2, mapped.credentialsCount)
    }

    @Test
    fun a_document_with_no_credentials_left_has_no_expiry_and_is_not_expired() {
        // An exhausted once-only batch: nothing left to present, but that is not an expiry.
        val mapped = issuedDocument(
            policy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 5),
            credentials = listOf(credential("used", usageCount = 1)),
        ).toWalletDocument(locale = "en", now = now)

        assertNull(mapped.expiresAt)
        assertFalse(mapped.isExpired)
        assertEquals(0, mapped.credentialsCount)
        assertEquals(5, mapped.initialCredentialsCount)
        assertTrue(mapped.isLowOnCredentials)
    }

    @Test
    fun a_credential_expiring_exactly_now_is_not_yet_expired() {
        val mapped = issuedDocument(credentials = listOf(credential("a", validUntil = now)))
            .toWalletDocument(locale = "en", now = now)

        assertFalse(mapped.isExpired)
    }

    @Test
    fun the_credential_count_reflects_the_usable_set_not_the_stored_set() {
        // The "5/7" counter on the Documents screen: 7 issued, 5 presentable.
        val mapped = issuedDocument(
            policy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 7),
            credentials = List(5) { credential("fresh-$it") } +
                    List(2) { credential("used-$it", usageCount = 1) },
        ).toWalletDocument(locale = "en", now = now)

        assertEquals(5, mapped.credentialsCount)
        assertEquals(7, mapped.initialCredentialsCount)
        assertFalse(mapped.isLowOnCredentials)
    }

    @Test
    fun revocation_is_supplied_by_the_caller_because_it_lives_outside_the_document_store() {
        val mapped = issuedDocument().toWalletDocument(locale = "en", isRevoked = true, now = now)

        assertTrue(mapped.isRevoked)
        // Revocation does not change anything else about the document.
        assertEquals(WalletDocumentIssuanceState.Issued, mapped.issuanceState)
        assertEquals(2, mapped.credentialsCount)
    }

    @Test
    fun a_pending_document_keeps_the_absent_defaults_for_credential_and_validity_fields() {
        val mapped = issuedDocument().copy(
            issuedAt = null,
            // Even if credentials somehow exist, nothing is knowable until the issuer answers.
            certifiedCredentials = listOf(credential("a")),
        ).toWalletDocument(locale = "en", now = now)

        assertEquals(WalletDocumentIssuanceState.Pending, mapped.issuanceState)
        assertNull(mapped.issuedAt)
        assertNull(mapped.expiresAt)
        assertFalse(mapped.isExpired)
        assertEquals(0, mapped.credentialsCount)
        assertEquals(0, mapped.initialCredentialsCount)
        assertFalse(mapped.isLowOnCredentials)
    }

    @Test
    fun a_pending_document_still_resolves_its_issuer_display() {
        // The issuer published this up front, so it renders while the document is still waiting.
        val mapped = issuedDocument().copy(issuedAt = null)
            .toWalletDocument(locale = "en", now = now)

        assertEquals("Test Issuer", mapped.issuerName)
        assertEquals("https://issuer.example/en.png", mapped.issuerLogoUri)
        assertEquals("PID MSO MDoc", mapped.name)
        assertEquals("eu.europa.ec.eudi.pid.1", mapped.formatType)
    }

    @Test
    fun claims_are_carried_through_only_when_the_reader_parsed_them() {
        // Faithful to Android: the list projection does not read claims (only getMainPidDocument
        // does), so the caller controls the cost by choosing whether to populate them at all.
        assertTrue(issuedDocument().toWalletDocument(locale = "en", now = now).claims.isEmpty())

        val withClaims = issuedDocument(claims = mapOf("given_name" to "Tester"))
            .toWalletDocument(locale = "en", now = now)
        assertEquals("Tester", withClaims.claims["given_name"])
    }

    @Test
    fun a_credential_from_another_document_manager_does_not_inflate_the_count_or_the_expiry() {
        val mapped = issuedDocument(
            credentials = listOf(
                credential("mine", validUntil = now + 10.days),
                credential("theirs", validUntil = now + 900.days, domain = "other-manager"),
                credential("invalidated", validUntil = now + 900.days, isInvalidated = true),
            ),
        ).toWalletDocument(locale = "en", now = now)

        assertEquals(1, mapped.credentialsCount)
        assertEquals(now + 10.days, mapped.expiresAt)
    }
}
