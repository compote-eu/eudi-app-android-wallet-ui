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

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The `client_id` a request claims must be proven by the certificate that signed it — OpenID4VP 1.0 §5.9.3,
 * which multipaz does not check. Every rule is Android's (openid4vp-kt 0.15.1), and each is tested from both
 * sides: the request that passes, and the nearest one that must not.
 */
class ClientIdBindingTest {

    private val responseUri = "https://verifier.test/wallet/direct_post/1"

    private fun request(clientId: String?, responseUri: String? = this.responseUri): JsonObject =
        buildJsonObject {
            clientId?.let { put("client_id", it) }
            responseUri?.let { put("response_uri", it) }
            put("nonce", "n-1")
        }

    private suspend fun refusal(linkClientId: String?, request: JsonObject, signer: X509Cert?): String =
        assertFailsWith<ClientIdBindingException> { checkClientIdBinding(linkClientId, request, signer) }.reason

    // x509_san_dns

    @Test
    fun a_dns_name_the_certificate_holds_is_accepted() = runTest {
        val id = "x509_san_dns:verifier.test"
        checkClientIdBinding(id, request(id), testVerifierCertificate(dnsNames = listOf("other.test", "verifier.test")))
    }

    @Test
    fun a_dns_name_the_certificate_does_not_hold_is_refused() = runTest {
        val id = "x509_san_dns:verifier.test"
        val reason = refusal(id, request(id), testVerifierCertificate(dnsNames = listOf("other.test")))
        assertEquals("verifier.test is not a DNS name of the signing certificate", reason)
    }

    @Test
    fun only_a_dns_name_counts_not_a_uri_entry() = runTest {
        // The EU dev verifier's own certificate names its home page as a URI and carries no DNS name at all:
        // it can prove an x509_hash identifier, never an x509_san_dns one.
        val id = "x509_san_dns:dev.verifier.eudiw.dev"
        refusal(id, request(id, responseUri = "https://dev.verifier.eudiw.dev/wallet/direct_post/1"), euDevVerifier())

        // And an entry of another type is not a DNS name even when it spells the same text.
        val ours = "x509_san_dns:verifier.test"
        refusal(ours, request(ours), testVerifierCertificate(dnsNames = emptyList(), uri = "verifier.test"))
    }

    @Test
    fun a_response_uri_on_another_host_is_refused() = runTest {
        val id = "x509_san_dns:verifier.test"
        val reason = refusal(
            id,
            request(id, responseUri = "https://elsewhere.test/wallet/direct_post/1"),
            testVerifierCertificate(dnsNames = listOf("verifier.test")),
        )
        assertEquals("the response_uri is on elsewhere.test, not on verifier.test", reason)
    }

    // x509_hash

    @Test
    fun the_eu_dev_verifiers_certificate_hash_is_accepted() = runTest {
        // The very client_id this verifier sent on 2026-09-29, for this certificate.
        checkClientIdBinding(EU_DEV_VERIFIER_CLIENT_ID, request(EU_DEV_VERIFIER_CLIENT_ID), euDevVerifier())
    }

    @Test
    fun the_hash_of_another_certificate_is_refused() = runTest {
        val reason = refusal(
            EU_DEV_VERIFIER_CLIENT_ID,
            request(EU_DEV_VERIFIER_CLIENT_ID),
            testVerifierCertificate(dnsNames = listOf("verifier.test")),
        )
        assertEquals("${EU_DEV_VERIFIER_CLIENT_ID.substringAfter(':')} is not the hash of the signing certificate", reason)
    }

    // the link, the prefix, the signature

    @Test
    fun a_link_naming_another_verifier_is_refused() = runTest {
        val signer = testVerifierCertificate(dnsNames = listOf("other.test"))
        val reason = refusal(
            "x509_san_dns:verifier.test",
            request("x509_san_dns:other.test", responseUri = "https://other.test/wallet/direct_post/1"),
            signer,
        )
        assertEquals("the link names x509_san_dns:verifier.test, the request object x509_san_dns:other.test", reason)
    }

    @Test
    fun the_prefix_is_part_of_the_comparison() = runTest {
        // Android compares the whole value; the official iOS library compares only what follows the prefix.
        val id = "x509_san_dns:verifier.test"
        refusal("verifier.test", request(id), testVerifierCertificate(dnsNames = listOf("verifier.test")))
    }

    @Test
    fun a_link_without_a_client_id_is_refused() = runTest {
        val id = "x509_san_dns:verifier.test"
        val reason = refusal(null, request(id), testVerifierCertificate(dnsNames = listOf("verifier.test")))
        assertEquals("the link names no client_id", reason)
    }

    @Test
    fun a_request_object_without_a_client_id_is_refused() = runTest {
        refusal("x509_san_dns:verifier.test", request(null), testVerifierCertificate(dnsNames = listOf("verifier.test")))
    }

    @Test
    fun a_prefix_android_does_not_accept_is_refused() = runTest {
        val id = "decentralized_identifier:did:example:123"
        val reason = refusal(id, request(id), testVerifierCertificate(dnsNames = listOf("verifier.test")))
        assertEquals("the client_id prefix 'decentralized_identifier' is not accepted", reason)
    }

    // The client_ids the official iOS library let through to a verifier it never authenticated (wallet kit #471,
    // #474, #475; fixed in 0.54.8), each signed by a certificate that does prove `verifier.test`.

    @Test
    fun an_unprefixed_client_id_is_refused_even_when_link_and_request_agree() = runTest {
        for (id in listOf("verifier.test", "registered-verifier")) {
            val reason = refusal(id, request(id), testVerifierCertificate(dnsNames = listOf("verifier.test")))
            assertEquals("the client_id prefix '' is not accepted", reason)
        }
    }

    @Test
    fun a_prefix_with_no_authentication_here_is_refused() = runTest {
        for (prefix in listOf("unknown", "redirect_uri", "openid_federation", "verifier_attestation", "pre-registered")) {
            val id = "$prefix:verifier.test"
            val reason = refusal(id, request(id), testVerifierCertificate(dnsNames = listOf("verifier.test")))
            assertEquals("the client_id prefix '$prefix' is not accepted", reason)
        }
    }

    @Test
    fun a_malformed_prefix_is_refused() = runTest {
        for (id in listOf(":", ":x509_san_dns:verifier.test", "::x509_san_dns:verifier.test")) {
            val reason = refusal(id, request(id), testVerifierCertificate(dnsNames = listOf("verifier.test")))
            assertEquals("the client_id prefix '' is not accepted", reason)
        }
    }

    @Test
    fun a_prefix_without_an_identifier_is_refused() = runTest {
        refusal("x509_san_dns:", request("x509_san_dns:"), testVerifierCertificate(dnsNames = listOf("verifier.test")))
        refusal("x509_hash:", request("x509_hash:"), euDevVerifier())
    }

    @Test
    fun a_request_object_without_a_signing_certificate_is_refused() = runTest {
        val id = "x509_san_dns:verifier.test"
        assertEquals("the request object carries no signing certificate", refusal(id, request(id), null))
    }

    @Test
    fun the_refusal_tells_the_user_in_a_sentence_and_keeps_the_reason_for_the_log() = runTest {
        val id = "x509_san_dns:verifier.test"
        val refused = assertFailsWith<ClientIdBindingException> {
            checkClientIdBinding(id, request(id), testVerifierCertificate(dnsNames = listOf("other.test")))
        }
        assertEquals(
            "This request could not be verified: the verifier it names is not proven by the certificate that signed it.",
            refused.message,
        )
    }

    // the link's client_id

    @Test
    fun the_links_client_id_is_read_from_its_query() {
        assertEquals(
            "x509_san_dns:verifier.test",
            linkClientIdOf("openid4vp://?client_id=x509_san_dns%3Averifier.test&request_uri=https%3A%2F%2Fv.test%2Fr"),
        )
        assertNull(linkClientIdOf("haip-vp://?request_uri=https%3A%2F%2Fv.test%2Fr"))
        assertNull(linkClientIdOf("mdoc://engagement"))
    }
}

/** The EU dev verifier's request-signing certificate, as served on 2026-09-29; public reference infrastructure. */
private const val EU_DEV_VERIFIER_PEM = """-----BEGIN CERTIFICATE-----
MIIC9zCCAp6gAwIBAgIUCh6Gs6LCrC8zL3Ru5bzCbFdAYo4wCgYIKoZIzj0EAwIwVzEZMBcGA1UEAwwQUElEIElzc3VlciBDQSAw
MjEtMCsGA1UECgwkRVVESSBXYWxsZXQgUmVmZXJlbmNlIEltcGxlbWVudGF0aW9uMQswCQYDVQQGEwJFVTAeFw0yNjA4MTMxMjM4
NTdaFw0yODA4MTIxMjM4NTZaMFUxHDAaBgNVBAMME1ZlcmlmaWVyIFNpZ25lciBkZXYxCzAJBgNVBAYTAkVVMQ4wDAYDVQQKDAVO
aXNjeTEYMBYGA1UEYQwPTEVJWEctMTIzNDU2Nzg5MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELN2GH7gYLmAyETNcakK4HGCd
xwnQV2UKAQ+tTS+YEKP8najuXVp0+xxOctYF2SO6G4OZ0+1GHY/QIGZ3i9C1eaOCAUgwggFEMAwGA1UdEwEB/wQCMAAwHwYDVR0j
BBgwFoAUQlBQvhC4EPCdRFyNv6sQCO4n3EkwWQYIKwYBBQUHAQEETTBLMEkGCCsGAQUFBzAChj1odHRwczovL3ByZXByb2QucGtp
LmV1ZGl3LmRldi9haWEvUElESXNzdWVyQ0EwMi1FVS5jYWNlcnQucGVtMC4GA1UdEQQnMCWGI2h0dHBzOi8vZGV2LnZlcmlmaWVy
LmV1ZGl3LmRldi9ob21lMBQGA1UdIAQNMAswCQYHBACL7EYBAjBDBgNVHR8EPDA6MDigNqA0hjJodHRwczovL3ByZXByb2QucGtp
LmV1ZGl3LmRldi9jcmwvcGlkX0NBX0VVXzAyLmNybDAdBgNVHQ4EFgQUWPZP2nePAALLiFCqh87WwbPXImcwDgYDVR0PAQH/BAQD
AgeAMAoGCCqGSM49BAMCA0cAMEQCIAljpp4ZY9CMXAOQJ/wzvx7KRRcN2VpVgqN1/5UlHSReAiAaCxpg4iHzKV1jpQ8XlY1QyYnK
DG+o0qft+CgdTEtftQ==
-----END CERTIFICATE-----"""

/** The client_id the EU dev verifier sent with that certificate. */
private const val EU_DEV_VERIFIER_CLIENT_ID = "x509_hash:6_2l_DyVdJDT1a4OGqxSsgTsS_UkW-hZOZT5ADd58J4"

private fun euDevVerifier(): X509Cert = X509Cert.fromPem(EU_DEV_VERIFIER_PEM)

/** A self-signed verifier certificate naming [dnsNames] (and [uri], if given) as subject alternative names. */
internal suspend fun testVerifierCertificate(dnsNames: List<String>, uri: String? = null): X509Cert {
    val key = Crypto.createEcPrivateKey(EcCurve.P256)
    val name = X500Name.fromName("CN=Test Verifier,C=EU")
    fun generalName(tag: Int, value: String) =
        ASN1TaggedObject(ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.PRIMITIVE, tag, value.encodeToByteArray())
    val names = dnsNames.map { generalName(tag = 2, it) } + listOfNotNull(uri?.let { generalName(tag = 6, it) })
    return X509Cert.Builder(
        publicKey = key.publicKey,
        signingKey = AsymmetricKey.AnonymousExplicit(privateKey = key),
        serialNumber = ASN1Integer(1L),
        subject = name,
        issuer = name,
        validFrom = Clock.System.now() - 1.days,
        validUntil = Clock.System.now() + 30.days,
    ).apply {
        if (names.isNotEmpty()) addExtension("2.5.29.17", critical = false, value = ASN1.encode(ASN1Sequence(names)))
    }.build()
}

/**
 * A request object as it arrives: compact JWS, [signer] in `x5c`. The signature is a placeholder — the binding
 * check reads the certificate, and verifying the signature stays multipaz's job.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun signedRequestObject(claims: JsonObject, signer: X509Cert?): String {
    val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    val x5c = signer?.let { ""","x5c":["${Base64.encode(it.encoded.toByteArray())}"]""" }.orEmpty()
    return listOf(
        b64.encode("""{"typ":"oauth-authz-req+jwt","alg":"ES256"$x5c}""".encodeToByteArray()),
        b64.encode(claims.toString().encodeToByteArray()),
        b64.encode("signature".encodeToByteArray()),
    ).joinToString(".")
}
