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

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.io.bytestring.ByteString
import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.revocation.RevocationStatus
import org.multipaz.revocation.StatusList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The iOS revocation check against **real** status-list tokens: built with multipaz's own
 * `StatusList`, zlib-compressed, signed as a `statuslist+jwt`, served over a ktor `MockEngine`, and
 * read back through the checker. Only the transport is faked.
 *
 * That matters most for the rejection cases. It would be easy to write a checker that *looks* like it
 * verifies signatures and silently does not, and a test that stubbed the parse step could not tell.
 * Here the wrong-key case is signed by a real key the credential does not name, so it fails for the
 * reason a MITM'd status list would fail — which is the whole security property of this feature.
 */
class MultipazRevocationCheckerTest {

    // ---- the credential's status entry, and the token that answers it -------------------------

    private suspend fun signerKey(): EcPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)

    /**
     * A self-signed certificate for [key], as an issuer would put in the mdoc `status_list`
     * `certificate` field. The checker pins the token's signature to this key.
     */
    private suspend fun certificateFor(key: EcPrivateKey): X509Cert {
        val name = X500Name.fromName("CN=Status List Signer")
        return X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(privateKey = key),
            serialNumber = ASN1Integer(1L),
            subject = name,
            issuer = name,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
        ).build()
    }

    /** A one-bit status list where [revokedIndices] are 0x01 and everything else is 0x00. */
    private suspend fun statusListToken(
        key: EcPrivateKey,
        revokedIndices: List<Int> = emptyList(),
        bitsPerItem: Int = 1,
        statuses: Map<Int, Int> = revokedIndices.associateWith { 1 },
    ): String {
        val builder = StatusList.Builder(bitsPerItem)
        statuses.forEach { (index, status) -> builder.addStatus(index, status) }
        return builder.build()
            .compress()
            .serializeAsJwt(
                key = AsymmetricKey.AnonymousExplicit(privateKey = key),
                subject = "https://issuer.test/statuslists/1",
            )
    }

    private fun checkerServing(token: String): MultipazRevocationChecker =
        MultipazRevocationChecker(
            HttpClient(MockEngine { respond(token) })
        )

    private suspend fun statusEntry(
        key: EcPrivateKey,
        idx: Int,
    ) = RevocationStatus.StatusList(
        idx = idx,
        uri = "https://issuer.test/statuslists/1",
        certificate = certificateFor(key),
    )

    // ---- the outcomes ------------------------------------------------------------------------

    @Test
    fun an_index_that_is_not_listed_is_valid() = runTest {
        val key = signerKey()
        val checker = checkerServing(statusListToken(key, revokedIndices = listOf(7)))

        val outcome = checker.check(statusEntry(key, idx = 0))

        assertEquals(RevocationOutcome.Valid, outcome)
        assertTrue(!outcome.isRevoked)
    }

    @Test
    fun a_listed_index_is_invalid_and_counts_as_revoked() = runTest {
        val key = signerKey()
        val checker = checkerServing(statusListToken(key, revokedIndices = listOf(7)))

        val outcome = checker.check(statusEntry(key, idx = 7))

        assertEquals(RevocationOutcome.Invalid, outcome)
        // The bit that makes the document show as revoked in the UI.
        assertTrue(outcome.isRevoked)
    }

    @Test
    fun a_suspended_index_is_reported_as_suspended_and_also_counts_as_revoked() = runTest {
        val key = signerKey()
        // Status 0x02 needs at least two bits per entry; one-bit lists can only say valid/invalid.
        val checker = checkerServing(
            statusListToken(key, bitsPerItem = 2, statuses = mapOf(3 to 2))
        )

        val outcome = checker.check(statusEntry(key, idx = 3))

        assertEquals(RevocationOutcome.Suspended, outcome)
        assertTrue(outcome.isRevoked)
    }

    @Test
    fun a_token_signed_by_a_key_the_credential_does_not_name_is_rejected() = runTest {
        val credentialKey = signerKey()
        val attackerKey = signerKey()
        // The list says index 7 is *valid* — the lie a MITM would tell to un-revoke a credential.
        val checker = checkerServing(statusListToken(attackerKey, revokedIndices = emptyList()))

        val outcome = checker.check(statusEntry(credentialKey, idx = 7))

        val unknown = assertIs<RevocationOutcome.Unknown>(outcome)
        assertTrue("rejected" in unknown.reason, "unexpected reason: ${unknown.reason}")
        // Crucially NOT reported as valid: an unverifiable answer leaves the document as it was.
        assertTrue(!outcome.isRevoked)
    }

    @Test
    fun a_token_with_neither_a_pinned_key_nor_a_certificate_chain_is_rejected() = runTest {
        val key = signerKey()
        val checker = checkerServing(statusListToken(key, revokedIndices = listOf(7)))

        // No `certificate` in the credential's entry, and the token carries no `x5c` either, so there
        // is nothing to verify against. multipaz refuses to parse it rather than trusting it.
        val outcome = checker.check(
            RevocationStatus.StatusList(
                idx = 7,
                uri = "https://issuer.test/statuslists/1",
                certificate = null,
            )
        )

        assertIs<RevocationOutcome.Unknown>(outcome)
    }

    @Test
    fun an_unreachable_status_list_is_unknown_rather_than_valid() = runTest {
        val key = signerKey()
        val checker = MultipazRevocationChecker(
            HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        )

        val outcome = checker.check(statusEntry(key, idx = 7))

        val unknown = assertIs<RevocationOutcome.Unknown>(outcome)
        assertTrue("could not be fetched" in unknown.reason, "unexpected reason: ${unknown.reason}")
    }

    @Test
    fun a_transport_failure_is_unknown_rather_than_a_crash() = runTest {
        val key = signerKey()
        val checker = MultipazRevocationChecker(
            HttpClient(MockEngine { throw IllegalStateException("no network") })
        )

        // A refresh loops over every document; one unreachable list must not abort the rest.
        assertIs<RevocationOutcome.Unknown>(checker.check(statusEntry(key, idx = 7)))
    }

    @Test
    fun a_credential_with_no_status_entry_is_unknown() = runTest {
        val checker = checkerServing("unused")

        val unknown = assertIs<RevocationOutcome.Unknown>(checker.check(null))

        assertTrue("no revocation status" in unknown.reason, "unexpected reason: ${unknown.reason}")
    }

    @Test
    fun an_identifier_list_is_unknown_because_it_is_not_implemented() = runTest {
        val checker = checkerServing("unused")

        val outcome = checker.check(
            RevocationStatus.IdentifierList(
                id = ByteString(1, 2, 3),
                uri = "https://issuer.test/identifiers/1",
                certificate = null,
            )
        )

        val unknown = assertIs<RevocationOutcome.Unknown>(outcome)
        assertTrue("identifier lists" in unknown.reason, "unexpected reason: ${unknown.reason}")
    }

    @Test
    fun an_unparseable_body_is_unknown() = runTest {
        val key = signerKey()
        val checker = checkerServing("this is not a jwt")

        assertIs<RevocationOutcome.Unknown>(checker.check(statusEntry(key, idx = 7)))
    }

    @Test
    fun a_status_list_with_no_signer_key_says_why_rather_than_failed_requirement() = runTest {
        // What both EU dev issuers actually publish: an `x5c`-signed status list, and a credential whose
        // status claim names no `certificate`. multipaz refuses it — correctly, an unverifiable list must
        // not be believed — but with a bare "Failed requirement." that says nothing. The reason a reader
        // needs is that trust anchors are missing, and that the fix is not in this class.
        val checker = MultipazRevocationChecker(
            httpClient = HttpClient(MockEngine { respond("not.a.validjwt") }),
        )

        val outcome = checker.check(
            RevocationStatus.StatusList(idx = 1791, uri = "https://issuer.test/sl", certificate = null)
        )

        val unknown = assertIs<RevocationOutcome.Unknown>(outcome)
        assertTrue(unknown.reason.contains("cannot validate"), unknown.reason)
        assertTrue(unknown.reason.contains("trust anchors"), unknown.reason)
    }
}
