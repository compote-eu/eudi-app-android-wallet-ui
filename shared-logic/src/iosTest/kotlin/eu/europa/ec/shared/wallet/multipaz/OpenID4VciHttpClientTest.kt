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

import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import eu.europa.ec.shared.wallet.trust.TrustVerdict
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.http.headersOf
import io.ktor.http.parametersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.test.runTest
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509Cert
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Metadata as an issuer really signs it: [chain] in the `x5c` header, and an ES256 signature by
 * [signingKey] — by default [signer]'s, so the JWS verifies with the chain's first certificate.
 */
@OptIn(ExperimentalEncodingApi::class)
internal suspend fun testSignedJwt(
    payload: String,
    signer: TestSigner,
    chain: List<X509Cert> = listOf(signer.certificate),
    signingKey: EcPrivateKey = signer.key,
): String {
    val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    val x5c = chain.joinToString(",") { "\"${Base64.Default.encode(it.encoded.toByteArray())}\"" }
    val signingInput = listOf("""{"typ":"JWT","alg":"ES256","x5c":[$x5c]}""", payload)
        .joinToString(".") { b64.encode(it.encodeToByteArray()) }
    val signature = Crypto.sign(signingKey, Algorithm.ES256, signingInput.encodeToByteArray())
    return "$signingInput.${b64.encode(signature.toCoseEncoded())}"
}

/**
 * The three compatibility rules the iOS OpenID4VCI client applies, against a `MockEngine` standing in
 * for the network.
 *
 * Each rule exists because of something observed against the real EU dev issuers, and they are the kind
 * of thing that is easy to get subtly wrong — the first version of the 502 path deadlocked every
 * subsequent request, because a synthetic response was built with the *request's* job as its call
 * context. Reading the body here (`readRawBytes`, which is what multipaz uses) is what catches that:
 * a test that only checked the status code would have passed.
 */
@OptIn(ExperimentalEncodingApi::class)
class OpenID4VciHttpClientTest {

    private val metadataUrl = "https://issuer.test/.well-known/openid-credential-issuer"

    private fun jwtOf(payload: String, x5c: List<String>? = listOf("fake")): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        // `x5c` entries are standard-alphabet base64 of DER, unlike the JWS segments around them.
        val header = when (x5c) {
            null -> """{"typ":"JWT","alg":"ES256"}"""
            else -> """{"typ":"JWT","alg":"ES256","x5c":[${x5c.joinToString(",") { "\"$it\"" }}]}"""
        }
        return listOf(
            b64.encode(header.encodeToByteArray()),
            b64.encode(payload.encodeToByteArray()),
            b64.encode("signature".encodeToByteArray()),
        ).joinToString(".")
    }


    @Test
    fun signed_metadata_is_unwrapped_to_the_jwt_payload() = runTest {
        val metadata = """{"credential_issuer":"https://issuer.test","credential_endpoint":"x"}"""
        val client = openID4VciHttpClient(
            MockEngine {
                respond(
                    content = jwtOf(metadata),
                    headers = headersOf("Content-Type", "application/jwt"),
                )
            },
            // No trust check: this test is about unwrapping, and a real one would download four EU
            // trust lists to judge a signer whose `x5c` is the string "fake".
            issuerTrust = null,
        )

        val body = client.get(metadataUrl).readRawBytes().decodeToString()

        // multipaz parses this with `Json.parseToJsonElement(...).jsonObject`, so it must be the object.
        assertEquals(metadata, body)
    }

    @Test
    fun signed_metadata_from_an_untrusted_signer_is_refused() = runTest {
        val metadata = """{"credential_issuer":"https://issuer.test"}"""
        val jwt = testSignedJwt(metadata, testSigner())
        val client = openID4VciHttpClient(
            MockEngine {
                respond(
                    content = jwt,
                    headers = headersOf("Content-Type", "application/jwt"),
                )
            },
            issuerTrust = { _, _ -> TrustVerdict.NOT_TRUSTED },
        )

        // Typed, so the screen shows the "issuer not trusted" sheet Android shows for it.
        val failure = assertFailsWith<IssuerNotTrustedException> { client.get(metadataUrl) }
        assertTrue(
            "not a recognised access certificate" in failure.message.orEmpty(),
            "unexpected: ${failure.message}",
        )
    }

    @Test
    fun signed_metadata_is_allowed_through_when_trust_cannot_be_established() = runTest {
        // The deliberate divergence from Android, and the reason it is a test rather than a comment:
        // "the trust list was unreachable" must not read as "this issuer is hostile", or the wallet
        // could not add a document offline. If this ever starts throwing, that decision was reversed
        // by accident.
        val metadata = """{"credential_issuer":"https://issuer.test","credential_endpoint":"x"}"""
        val jwt = testSignedJwt(metadata, testSigner())
        val client = openID4VciHttpClient(
            MockEngine {
                respond(
                    content = jwt,
                    headers = headersOf("Content-Type", "application/jwt"),
                )
            },
            issuerTrust = { _, _ -> TrustVerdict.UNDETERMINED },
        )

        assertEquals(metadata, client.get(metadataUrl).readRawBytes().decodeToString())
    }

    @Test
    fun a_trusted_signer_is_asked_about_with_the_whole_x5c_chain() = runTest {
        // Two entries, because a chain is what PKIX validates: handing the checker only the leaf
        // would make any chain-building failure invisible. `PID` is the context Android's
        // `configureIssuerTrust` enforces for a credential issuer.
        var seenChainSize: Int? = null
        var seenContext: VerificationContext? = null
        val metadata = """{"credential_issuer":"https://issuer.test","credential_endpoint":"x"}"""
        val leaf = testSigner("CN=Metadata Signer")
        val jwt = testSignedJwt(metadata, leaf, chain = listOf(leaf.certificate, testSignerCertificate("CN=Root")))
        val client = openID4VciHttpClient(
            MockEngine {
                respond(
                    content = jwt,
                    headers = headersOf("Content-Type", "application/jwt"),
                )
            },
            issuerTrust = { chain, context ->
                seenChainSize = chain.size
                seenContext = context
                TrustVerdict.TRUSTED
            },
        )

        assertEquals(metadata, client.get(metadataUrl).readRawBytes().decodeToString())
        assertEquals(2, seenChainSize)
        // ⛔ WRPAC, not PID. Metadata signing certificates are access certificates; the PID list is for
        // the certificates that sign PID *credentials*. wallet-core's `EtsiCertificateChainTrust` uses
        // the same context and calls it "the correct context for metadata signing certificates per the
        // EUDI specification". Getting this wrong applies `pidSigningCertificateProfile()`, whose
        // `mandatoryQcType` rejects every EU dev metadata signer for carrying no `qcStatements`.
        assertEquals(VerificationContext.WalletRelyingPartyAccessCertificate, seenContext)
    }

    @Test
    fun signed_metadata_carrying_a_recognised_chain_but_signed_by_another_key_is_refused_without_asking() = runTest {
        // An issuer's chain is public; pasted into metadata someone else signed, it must not lend them
        // the issuer's standing. The lists would vouch for the chain, so they are not asked.
        var asked = false
        val metadata = """{"credential_issuer":"https://issuer.test","credential_endpoint":"x"}"""
        val jwt = testSignedJwt(metadata, testSigner(), signingKey = Crypto.createEcPrivateKey(EcCurve.P256))
        val client = openID4VciHttpClient(
            MockEngine {
                respond(
                    content = jwt,
                    headers = headersOf("Content-Type", "application/jwt"),
                )
            },
            issuerTrust = { _, _ -> asked = true; TrustVerdict.TRUSTED },
        )

        val failure = assertFailsWith<IssuerNotTrustedException> { client.get(metadataUrl) }
        assertTrue("not signed by the certificate chain" in failure.message.orEmpty(), "unexpected: ${failure.message}")
        assertFalse(asked, "a chain the metadata was not signed with says nothing about who signed it")
    }

    @Test
    fun signed_metadata_without_an_x5c_is_not_refused_for_having_no_signer_to_check() = runTest {
        // Nothing to check is not the same as a failed check. multipaz would still have to parse the
        // payload, and the issuer-identity check above still applies.
        var asked = false
        val metadata = """{"credential_issuer":"https://issuer.test","credential_endpoint":"x"}"""
        val client = openID4VciHttpClient(
            MockEngine {
                respond(
                    content = jwtOf(metadata, x5c = null),
                    headers = headersOf("Content-Type", "application/jwt"),
                )
            },
            issuerTrust = { _, _ -> asked = true; TrustVerdict.NOT_TRUSTED },
        )

        assertEquals(metadata, client.get(metadataUrl).readRawBytes().decodeToString())
        assertFalse(asked, "a JWT with no x5c has no signer to ask about")
    }

    @Test
    fun plain_json_metadata_is_left_alone() = runTest {
        val metadata = """{"credential_issuer":"https://issuer.test"}"""
        val client = openID4VciHttpClient(
            MockEngine {
                respond(
                    content = metadata,
                    headers = headersOf("Content-Type", "application/json"),
                )
            }
        )

        assertEquals(metadata, client.get(metadataUrl).readRawBytes().decodeToString())
    }

    @Test
    fun signed_metadata_for_a_different_issuer_is_rejected() = runTest {
        // Independent of who signed it: a document describing someone else was substituted, and this
        // check fires before the signer is ever consulted — which is why `issuerTrust` being absent
        // does not weaken the test.
        val client = openID4VciHttpClient(
            MockEngine {
                respond(
                    content = jwtOf("""{"credential_issuer":"https://attacker.test"}"""),
                    headers = headersOf("Content-Type", "application/jwt"),
                )
            },
            issuerTrust = null,
        )

        val failure = assertFailsWith<IllegalStateException> { client.get(metadataUrl) }
        assertTrue("attacker.test" in failure.message.orEmpty(), "unexpected: ${failure.message}")
    }

    @Test
    fun a_failing_logo_fetch_becomes_a_502_that_can_still_be_read() = runTest {
        val client = openID4VciHttpClient(
            MockEngine { throw IllegalStateException("dns is down") }
        )

        val response = client.get("https://examplestate.com/public/cor.png")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        // Reading the body must complete rather than hang — this is the deadlock regression test.
        assertEquals(0, response.readRawBytes().size)
    }

    @Test
    fun several_failing_logo_fetches_in_a_row_all_complete() = runTest {
        // The real issuer's metadata has 26 configurations, each with a dead logo URL; the deadlock
        // showed up as "the first one is reported and nothing happens after that".
        val client = openID4VciHttpClient(
            MockEngine { throw IllegalStateException("dns is down") }
        )

        repeat(3) { index ->
            val response = client.get("https://examplestate.com/public/logo$index.png")
            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertEquals(0, response.readRawBytes().size)
        }
    }

    @Test
    fun a_failing_metadata_fetch_still_throws() = runTest {
        // Softening this would turn "the issuer is unreachable" into a confusing parse error.
        val client = openID4VciHttpClient(
            MockEngine { throw IllegalStateException("dns is down") }
        )

        assertFailsWith<IllegalStateException> { client.get(metadataUrl) }
    }

    // ---- the attestation-challenge workaround ------------------------------------------------

    private val asMetadataUrl = "https://as.test/.well-known/oauth-authorization-server"
    private val parEndpoint = "https://as.test/realms/r/protocol/openid-connect/ext/par/request"
    private val challengeEndpoint = "https://as.test/realms/r/challenge"
    private val tokenEndpoint = "https://as.test/realms/r/protocol/openid-connect/token"

    private val asMetadata = """
        {"issuer":"https://as.test/realms/r",
         "challenge_endpoint":"$challengeEndpoint",
         "token_endpoint":"$tokenEndpoint",
         "pushed_authorization_request_endpoint":"$parEndpoint"}
    """.trimIndent()

    @Test
    fun a_par_response_is_given_a_freshly_fetched_attestation_challenge() = runTest {
        var challengeRequests = 0
        val client = openID4VciHttpClient(
            MockEngine { request ->
                when (request.url.toString()) {
                    asMetadataUrl -> respond(
                        asMetadata,
                        headers = headersOf("Content-Type", "application/json"),
                    )

                    challengeEndpoint -> {
                        challengeRequests++
                        respond(
                            """{"attestation_challenge":"fresh-$challengeRequests"}""",
                            headers = headersOf("Content-Type", "application/json"),
                        )
                    }

                    else -> respond("", HttpStatusCode.Created)
                }
            }
        )

        // The endpoints are learned from the metadata rather than guessed from URL shapes, so the
        // metadata has to be read first — exactly the order multipaz uses.
        client.get(asMetadataUrl).readRawBytes()
        val par = client.post(parEndpoint)

        assertEquals(
            "fresh-1",
            par.headers["OAuth-Client-Attestation-Challenge"],
            "multipaz reads the fresh challenge off this header; without it the token request replays",
        )
        assertEquals(1, challengeRequests)
    }

    @Test
    fun a_challenge_the_server_offers_itself_is_left_alone() = runTest {
        var challengeRequests = 0
        val client = openID4VciHttpClient(
            MockEngine { request ->
                when (request.url.toString()) {
                    asMetadataUrl -> respond(
                        asMetadata,
                        headers = headersOf("Content-Type", "application/json"),
                    )

                    challengeEndpoint -> {
                        challengeRequests++
                        respond("""{"attestation_challenge":"ours"}""")
                    }

                    else -> respond(
                        "",
                        HttpStatusCode.Created,
                        headersOf("OAuth-Client-Attestation-Challenge", "the-servers-own"),
                    )
                }
            }
        )

        client.get(asMetadataUrl).readRawBytes()
        val par = client.post(parEndpoint)

        // The server knows better; nothing is fetched or overwritten.
        assertEquals("the-servers-own", par.headers["OAuth-Client-Attestation-Challenge"])
        assertEquals(0, challengeRequests)
    }

    @Test
    fun a_post_that_is_not_the_par_endpoint_is_untouched() = runTest {
        val client = openID4VciHttpClient(
            MockEngine { request ->
                if (request.url.toString() == asMetadataUrl) {
                    respond(asMetadata, headers = headersOf("Content-Type", "application/json"))
                } else {
                    respond("", HttpStatusCode.OK)
                }
            }
        )
        client.get(asMetadataUrl).readRawBytes()

        val token = client.post(tokenEndpoint)

        assertNull(token.headers["OAuth-Client-Attestation-Challenge"])
    }

    @Test
    fun authorization_server_metadata_still_reads_normally_while_being_inspected() = runTest {
        // The endpoints are learned by reading the body, so the body must survive being read.
        val client = openID4VciHttpClient(
            MockEngine { respond(asMetadata, headers = headersOf("Content-Type", "application/json")) }
        )

        assertEquals(asMetadata, client.get(asMetadataUrl).readRawBytes().decodeToString())
    }

    // ---- arming multipaz's retry for a refused refresh -------------------------------------------

    /** A `MockEngine` that serves the AS metadata and the challenge endpoint, and lets the caller
     * decide what the token endpoint answers. */
    private fun engineAnsweringTokenWith(
        challengeCount: () -> Unit = {},
        tokenResponses: MutableList<HttpStatusCode>,
    ) = MockEngine { request ->
        when (request.url.toString()) {
            asMetadataUrl -> respond(asMetadata, headers = headersOf("Content-Type", "application/json"))
            challengeEndpoint -> {
                challengeCount()
                respond(
                    """{"attestation_challenge":"fresh"}""",
                    headers = headersOf("Content-Type", "application/json"),
                )
            }

            tokenEndpoint -> respond(
                """{"error":"invalid_client"}""",
                tokenResponses.removeFirstOrNull() ?: HttpStatusCode.OK,
            )

            else -> respond("", HttpStatusCode.OK)
        }
    }

    private suspend fun HttpClient.refreshTokenRequest() = submitForm(
        url = tokenEndpoint,
        formParameters = parametersOf("grant_type", "refresh_token"),
    )

    @Test
    fun a_refused_refresh_is_given_the_challenge_and_the_nonce_multipaz_needs_to_retry() = runTest {
        var challengeRequests = 0
        val client = openID4VciHttpClient(
            engineAnsweringTokenWith(
                challengeCount = { challengeRequests++ },
                tokenResponses = mutableListOf(HttpStatusCode.Unauthorized),
            )
        )
        client.get(asMetadataUrl).readRawBytes()

        val refused = client.refreshTokenRequest()

        // Without this, `createWalletAttestationPoP` mints a PoP with no `challenge` claim at all and
        // the issuer answers 401 invalid_client — measured against dev.issuer-backend.eudiw.dev.
        assertEquals("fresh", refused.headers["OAuth-Client-Attestation-Challenge"])
        assertEquals(1, challengeRequests)
        // And the challenge alone is not enough: multipaz's retry is gated on knowing a DPoP nonce,
        // which this authorization server never sends, so one has to be answered too.
        assertNotNull(refused.headers["DPoP-Nonce"])
    }

    @Test
    fun a_refused_refresh_still_reports_the_refusal_body_multipaz_logs() = runTest {
        val client = openID4VciHttpClient(
            engineAnsweringTokenWith(tokenResponses = mutableListOf(HttpStatusCode.Unauthorized))
        )
        client.get(asMetadataUrl).readRawBytes()

        val refused = client.refreshTokenRequest()

        // multipaz reads this body before it decides to retry, so arming the retry must not eat it.
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals("""{"error":"invalid_client"}""", refused.bodyAsText())
    }

    @Test
    fun a_refresh_the_issuer_accepts_is_left_alone() = runTest {
        var challengeRequests = 0
        val client = openID4VciHttpClient(
            engineAnsweringTokenWith(
                challengeCount = { challengeRequests++ },
                tokenResponses = mutableListOf(HttpStatusCode.OK),
            )
        )
        client.get(asMetadataUrl).readRawBytes()

        val accepted = client.refreshTokenRequest()

        // Nothing to work around when it worked: no challenge fetched, no headers invented.
        assertEquals(0, challengeRequests)
        assertNull(accepted.headers["DPoP-Nonce"])
        assertNull(accepted.headers["OAuth-Client-Attestation-Challenge"])
    }

    @Test
    fun only_the_first_refusal_is_armed_so_a_hopeless_refresh_still_ends() = runTest {
        var challengeRequests = 0
        val client = openID4VciHttpClient(
            engineAnsweringTokenWith(
                challengeCount = { challengeRequests++ },
                tokenResponses = mutableListOf(
                    HttpStatusCode.Unauthorized,
                    HttpStatusCode.Unauthorized,
                ),
            )
        )
        client.get(asMetadataUrl).readRawBytes()

        client.refreshTokenRequest()
        val second = client.refreshTokenRequest()

        // multipaz only retries once, so arming again would fetch a challenge nothing can use.
        assertEquals(1, challengeRequests)
        assertNull(second.headers["OAuth-Client-Attestation-Challenge"])
    }

    @Test
    fun the_reason_the_issuer_gave_for_refusing_a_refresh_is_recorded() = runTest {
        val refusal = TokenRefusalNotice()
        val client = openID4VciHttpClient(
            engine = MockEngine { request ->
                when (request.url.toString()) {
                    asMetadataUrl ->
                        respond(asMetadata, headers = headersOf("Content-Type", "application/json"))

                    else -> respond(
                        """{"error":"invalid_grant","error_description":"Token is not active"}""",
                        HttpStatusCode.BadRequest,
                    )
                }
            },
            refusalNotice = refusal,
        )
        client.get(asMetadataUrl).readRawBytes()

        val refused = client.refreshTokenRequest()

        // multipaz turns every one of these into the same sentence, so this is the only place the
        // difference between "add it again" and "try later" survives.
        assertEquals("invalid_grant", refusal.error)
        // And the body still reaches multipaz, which logs it.
        assertEquals(true, refused.bodyAsText().contains("Token is not active"))
    }

    @Test
    fun an_expired_refresh_token_is_not_retried() = runTest {
        var challengeRequests = 0
        val client = openID4VciHttpClient(
            engineAnsweringTokenWith(
                challengeCount = { challengeRequests++ },
                tokenResponses = mutableListOf(HttpStatusCode.BadRequest),
            )
        )
        client.get(asMetadataUrl).readRawBytes()

        client.refreshTokenRequest()

        // A better client credential cannot revive a spent refresh token, so retrying only delays the
        // message the user needs.
        assertEquals(0, challengeRequests)
    }

    @Test
    fun a_refused_authorization_code_exchange_is_not_armed() = runTest {
        var challengeRequests = 0
        val client = openID4VciHttpClient(
            engineAnsweringTokenWith(
                challengeCount = { challengeRequests++ },
                tokenResponses = mutableListOf(HttpStatusCode.Unauthorized),
            )
        )
        client.get(asMetadataUrl).readRawBytes()

        val refused = client.submitForm(
            url = tokenEndpoint,
            formParameters = parametersOf("grant_type", "authorization_code"),
        )

        // That path gets its fresh challenge from the PAR response, and a 401 there is a real refusal
        // to report rather than something to retry.
        assertEquals(0, challengeRequests)
        assertNull(refused.headers["OAuth-Client-Attestation-Challenge"])
    }

    @Test
    fun a_refusal_with_no_challenge_endpoint_known_is_passed_straight_through() = runTest {
        // No metadata read first, so the engine never learned where challenges come from.
        val client = openID4VciHttpClient(
            MockEngine { respond("""{"error":"invalid_client"}""", HttpStatusCode.Unauthorized) }
        )

        val refused = client.submitForm(
            url = tokenEndpoint,
            formParameters = parametersOf("grant_type", "refresh_token"),
        )

        assertNull(refused.headers["OAuth-Client-Attestation-Challenge"])
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
    }

    // ---- the session's access token, for a deferral in multipaz's own flow ----------------------

    private fun engineIssuingToken(body: String, status: HttpStatusCode = HttpStatusCode.OK) = MockEngine { request ->
        when (request.url.toString()) {
            asMetadataUrl -> respond(asMetadata, headers = headersOf("Content-Type", "application/json"))
            tokenEndpoint -> respond(body, status, headersOf("Content-Type", "application/json"))
            else -> respond("", HttpStatusCode.OK)
        }
    }

    @Test
    fun the_sessions_access_token_is_noted_and_still_reaches_multipaz() = runTest {
        // multipaz's client keeps its token to itself; without this, a deferral there could be collected
        // only by refreshing, and a refused refresh lost a document whose token was still valid.
        val notice = DeferredIssuanceNotice()
        val body = """{"access_token":"at-session","token_type":"DPoP","expires_in":300,"refresh_token":"rt"}"""
        val client = openID4VciHttpClient(engineIssuingToken(body), deferredNotice = notice)
        client.get(asMetadataUrl).readRawBytes()
        val before = Clock.System.now()

        val response = client.submitForm(
            url = tokenEndpoint,
            formParameters = parametersOf("grant_type", "authorization_code"),
        )

        assertEquals(body, response.bodyAsText())
        assertEquals("at-session", notice.sessionAccessToken)
        val expiresAt = assertNotNull(notice.sessionAccessTokenExpiresAt)
        assertTrue(expiresAt >= before + 300.seconds && expiresAt <= Clock.System.now() + 300.seconds)
    }

    @Test
    fun a_refused_token_exchange_notes_no_access_token() = runTest {
        val notice = DeferredIssuanceNotice()
        val client = openID4VciHttpClient(
            engineIssuingToken("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest),
            deferredNotice = notice,
        )
        client.get(asMetadataUrl).readRawBytes()

        client.submitForm(url = tokenEndpoint, formParameters = parametersOf("grant_type", "authorization_code"))

        assertNull(notice.sessionAccessToken)
    }

    // ---- display locales reduced to their language, for multipaz's exact matching ----------------

    @Test
    fun metadata_display_locales_are_reduced_to_their_language() = runTest {
        val metadata = """{"credential_issuer":"https://issuer.test","display":[{"name":"Issuer","locale":"en-US"},{"name":"Vydavateľ","locale":"sk-SK"}]}"""
        val client = openID4VciHttpClient(
            MockEngine { respond(metadata, headers = headersOf("Content-Type", "application/json")) }
        )

        val body = client.get(metadataUrl).readRawBytes().decodeToString()

        assertEquals("""{"credential_issuer":"https://issuer.test","display":[{"name":"Issuer","locale":"en"},{"name":"Vydavateľ","locale":"sk"}]}""", body)
    }

    @Test
    fun metadata_with_bare_locales_passes_through_byte_for_byte() = runTest {
        // Spacing and all: nothing to reduce, so nothing is re-serialised.
        val metadata = """{ "credential_issuer": "https://issuer.test", "display": [ { "name": "Issuer", "locale": "en" } ] }"""
        val client = openID4VciHttpClient(
            MockEngine { respond(metadata, headers = headersOf("Content-Type", "application/json")) }
        )

        assertEquals(metadata, client.get(metadataUrl).readRawBytes().decodeToString())
    }

    // ---- keeping the issuer's per-claim display names --------------------------------------------

    @Test
    fun claim_display_names_are_kept_from_the_metadata_multipaz_discards() = runTest {
        val notice = IssuerDisplayNotice()
        // The shape dev.issuer-backend.eudiw.dev actually publishes: names live under
        // `credential_metadata.claims`, and the join key is the configuration's doctype, not its id.
        val metadata = """
            {"credential_issuer":"https://issuer.test",
             "credential_configurations_supported":{
               "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","scope":"pid",
                 "credential_metadata":{"claims":[
                   {"path":["eu.europa.ec.eudi.pid.1","family_name"],"mandatory":true,
                    "display":[{"name":"Family Name(s)","locale":"en"}]},
                   {"path":["eu.europa.ec.eudi.pid.1","given_name"],
                    "display":[{"name":"Given Name(s)","locale":"en"}]}]}}}}
        """.trimIndent()
        val client = openID4VciHttpClient(
            engine = MockEngine { respond(metadata, headers = headersOf("Content-Type", "application/json")) },
            displayNotice = notice,
        )

        client.get(metadataUrl).readRawBytes()

        val claims = notice.claimsByDocumentType.getValue("eu.europa.ec.eudi.pid.1")
        assertEquals(2, claims.size)
        assertEquals("Family Name(s)", claims.first().displayNameFor("en"))
        // Keyed by doctype, because `CredentialMetadata.format.formatId` is only "mso_mdoc" and would
        // not tell two documents apart.
        assertEquals(setOf("eu.europa.ec.eudi.pid.1"), notice.claimsByDocumentType.keys)
    }

    @Test
    fun metadata_with_no_claims_leaves_the_notice_empty_rather_than_failing() = runTest {
        val notice = IssuerDisplayNotice()
        // Exactly the older-issuer case; naming claims is cosmetic and must never fail an issuance.
        val client = openID4VciHttpClient(
            engine = MockEngine {
                respond(
                    """{"credential_issuer":"https://issuer.test",
                        "credential_configurations_supported":{
                          "pid_mdoc":{"format":"mso_mdoc","doctype":"d","scope":"s"}}}""",
                    headers = headersOf("Content-Type", "application/json"),
                )
            },
            displayNotice = notice,
        )

        client.get(metadataUrl).readRawBytes()

        assertTrue(notice.claimsByDocumentType.isEmpty())
    }

    @Test
    fun an_unexpected_member_inside_a_claim_does_not_lose_the_whole_set() = runTest {
        val notice = IssuerDisplayNotice()
        // Issuers publish more per claim than this reads; a new member must not cost the names.
        val client = openID4VciHttpClient(
            engine = MockEngine {
                respond(
                    """{"credential_issuer":"https://issuer.test",
                        "credential_configurations_supported":{
                          "pid":{"doctype":"d","credential_metadata":{"claims":[
                            {"path":["ns","family_name"],"something_new":42,
                             "display":[{"name":"Family Name(s)","locale":"en"}]}]}}}}""",
                    headers = headersOf("Content-Type", "application/json"),
                )
            },
            displayNotice = notice,
        )

        client.get(metadataUrl).readRawBytes()

        assertEquals("Family Name(s)", notice.claimsByDocumentType.getValue("d").single().displayNameFor("en"))
    }

    // ---- keeping the document's and the issuer's names in every language ---------------------------

    private suspend fun displaysKept(metadata: String): IssuerDisplayNotice {
        val notice = IssuerDisplayNotice()
        val client = openID4VciHttpClient(
            engine = MockEngine { respond(metadata, headers = headersOf("Content-Type", "application/json")) },
            displayNotice = notice,
        )
        client.get(metadataUrl).readRawBytes()
        return notice
    }

    @Test
    fun every_language_of_the_document_and_issuer_names_is_kept() = runTest {
        // multipaz keeps one entry per side; these are what a name can follow the user's language from.
        val notice = displaysKept(
            """
            {"credential_issuer":"https://issuer.test",
             "display":[{"name":"Digital Credentials Issuer","locale":"en",
                         "logo":{"uri":"https://issuer.test/logo.svg","alt_text":"Logo"}},
                        {"name":"Εκδότης Ψηφιακών Διαπιστευτηρίων","locale":"el"}],
             "credential_configurations_supported":{
               "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1",
                 "credential_metadata":{"display":[
                   {"name":"PID","locale":"en","description":"Person identification",
                    "background_color":"#12107c","text_color":"#FFFFFF",
                    "logo":{"uri":"https://issuer.test/pid.png"},
                    "background_image":{"uri":"https://issuer.test/card.png"}},
                   {"name":"Občiansky preukaz","locale":"sk-SK"}]}}}}
            """.trimIndent()
        )

        val document = notice.documentDisplaysByDocumentType.getValue("eu.europa.ec.eudi.pid.1").single()
        assertEquals(listOf("PID" to "en", "Občiansky preukaz" to "sk"), document.map { it.name to it.locale })
        with(document.first()) {
            assertEquals("Person identification", description)
            assertEquals("#12107c", backgroundColor)
            assertEquals("#FFFFFF", textColor)
            assertEquals("https://issuer.test/pid.png", logo?.uri)
            assertEquals("https://issuer.test/card.png", backgroundImageUri)
        }
        assertEquals(
            listOf("Digital Credentials Issuer" to "en", "Εκδότης Ψηφιακών Διαπιστευτηρίων" to "el"),
            notice.issuerDisplays?.map { it.name to it.locale },
        )
        assertEquals("https://issuer.test/logo.svg", notice.issuerDisplays?.first()?.logo?.uri)
        assertEquals("Logo", notice.issuerDisplays?.first()?.logo?.alternativeText)
    }

    @Test
    fun configurations_sharing_a_doctype_are_told_apart_by_the_name_multipaz_chose() = runTest {
        // The EU issuer's `_deferred` twins: one doctype, two names. Last-one-wins would label a PID
        // "(deferred)", or the other way round.
        val notice = displaysKept(
            """
            {"credential_issuer":"https://issuer.test",
             "credential_configurations_supported":{
               "pid":{"doctype":"eu.europa.ec.eudi.pid.1",
                 "credential_metadata":{"display":[{"name":"PID (MSO MDoc)","locale":"en"}]}},
               "pid_deferred":{"doctype":"eu.europa.ec.eudi.pid.1",
                 "credential_metadata":{"display":[{"name":"PID (MSO MDoc) (deferred)","locale":"en"}]}}}}
            """.trimIndent()
        )

        assertEquals(
            "PID (MSO MDoc) (deferred)",
            notice.documentDisplayFor("eu.europa.ec.eudi.pid.1", chosenName = "PID (MSO MDoc) (deferred)")?.single()?.name,
        )
        assertEquals(
            "PID (MSO MDoc)",
            notice.documentDisplayFor("eu.europa.ec.eudi.pid.1", chosenName = "PID (MSO MDoc)")?.single()?.name,
        )
        assertNull(notice.documentDisplayFor("eu.europa.ec.eudi.pid.1", chosenName = "Untitled"))
    }

    @Test
    fun a_display_published_on_the_configuration_itself_is_kept_too() = runTest {
        // Where multipaz reads it when there is no `credential_metadata` — an older draft's shape.
        val notice = displaysKept(
            """
            {"credential_issuer":"https://issuer.test",
             "credential_configurations_supported":{
               "pid":{"doctype":"d","display":[{"name":"PID","locale":"en"},{"name":"PID","locale":"de"}]}}}
            """.trimIndent()
        )

        assertEquals(listOf("en", "de"), notice.documentDisplaysByDocumentType.getValue("d").single().map { it.locale })
    }

    @Test
    fun an_unnamed_or_malformed_display_entry_is_dropped_rather_than_failing() = runTest {
        // Names are cosmetic: a broken entry must never cost the issuance, nor the entries beside it.
        val notice = displaysKept(
            """
            {"credential_issuer":"https://issuer.test",
             "display":"not a list",
             "credential_configurations_supported":{
               "a":{"doctype":"a","credential_metadata":{"display":[{"locale":"en"},42,{"name":"A","locale":"en"}]}},
               "b":{"doctype":"b","credential_metadata":{"display":{"name":"B"}}}}}
            """.trimIndent()
        )

        assertEquals(listOf("A"), notice.documentDisplaysByDocumentType.getValue("a").single().map { it.name })
        assertTrue(notice.documentDisplaysByDocumentType.getValue("b").single().isEmpty())
        // Read and unusable: no names, as distinct from never read (null).
        assertEquals(emptyList(), notice.issuerDisplays)
    }

    // ---- noticing a deferred issuance -----------------------------------------------------------

    @Test
    fun a_deferred_credential_response_is_noted_and_passed_through_untouched() = runTest {
        val notice = DeferredIssuanceNotice()
        val body = """{"transaction_id":"edef16a9-2af5","interval":60}"""
        val client = openID4VciHttpClient(
            engine = MockEngine {
                respond(
                    body,
                    HttpStatusCode.Accepted,
                    headersOf("Content-Type", "application/json"),
                )
            },
            deferredNotice = notice,
        )

        val response = client.post("https://issuer.test/credential")

        // multipaz reads this body itself, so the engine must not consume it.
        assertEquals(body, response.bodyAsText())
        assertEquals(HttpStatusCode.Accepted, response.status)
        // What the note buys: the app can say "this is issued later" instead of quoting a status line.
        assertTrue(notice.wasDeferred)
        assertEquals("edef16a9-2af5", notice.transactionId)
        assertEquals(60, notice.retryAfterSeconds)
    }

    @Test
    fun a_credential_request_after_a_deferral_is_answered_with_it_and_never_reaches_the_issuer() = runTest {
        // multipaz retries a non-200 answer that carries a DPoP-Nonce, a deferral included. Sent again,
        // the request would make the issuer open a second deferred transaction and orphan the first.
        var sent = 0
        val notice = DeferredIssuanceNotice()
        val client = openID4VciHttpClient(
            engine = MockEngine {
                sent++
                respond(
                    """{"transaction_id":"tx-$sent","interval":60}""",
                    HttpStatusCode.Accepted,
                    headersOf("Content-Type" to listOf("application/json"), "DPoP-Nonce" to listOf("nonce-$sent")),
                )
            },
            deferredNotice = notice,
        )

        val first = client.postCredentialRequest("https://issuer.test/credential", "pid_mdoc")
        val retried = client.postCredentialRequest("https://issuer.test/credential", "pid_mdoc")

        assertEquals(1, sent, "the issuer is asked once")
        assertEquals(HttpStatusCode.Accepted, retried.status)
        assertEquals(first.bodyAsText(), retried.bodyAsText())
        // The transaction the handler parks is the one the issuer opened.
        assertEquals("tx-1", notice.transactionId)
    }

    @Test
    fun credential_requests_go_to_the_issuer_until_one_is_deferred() = runTest {
        var sent = 0
        val client = openID4VciHttpClient(
            engine = MockEngine {
                sent++
                respond("""{"credentials":[]}""", HttpStatusCode.OK, jsonContent)
            },
            deferredNotice = DeferredIssuanceNotice(),
        )

        client.postCredentialRequest("https://issuer.test/credential", "pid_mdoc")
        client.postCredentialRequest("https://issuer.test/credential", "pid_mdoc")

        assertEquals(2, sent)
    }

    @Test
    fun an_ordinary_credential_response_leaves_the_notice_empty() = runTest {
        val notice = DeferredIssuanceNotice()
        val client = openID4VciHttpClient(
            engine = MockEngine { respond("""{"credentials":[]}""", HttpStatusCode.OK) },
            deferredNotice = notice,
        )

        client.post("https://issuer.test/credential").bodyAsText()

        assertFalse(notice.wasDeferred)
    }

    @Test
    fun an_accepted_response_without_a_transaction_id_is_not_called_deferred() = runTest {
        val notice = DeferredIssuanceNotice()
        val client = openID4VciHttpClient(
            engine = MockEngine { respond("""{"queued":true}""", HttpStatusCode.Accepted) },
            deferredNotice = notice,
        )

        // 202 alone means nothing here; the transaction handle is what makes it a deferred issuance.
        assertEquals("""{"queued":true}""", client.post("https://issuer.test/credential").bodyAsText())
        assertFalse(notice.wasDeferred)
    }

    // ---- refusing a PID whose signer is not a recognised PID provider ----------------------------

    private val jsonContent = headersOf("Content-Type", "application/json")

    private suspend fun HttpClient.postCredentialRequest(url: String, configurationId: String) = post(url) {
        contentType(ContentType.Application.Json)
        setBody("""{"credential_configuration_id":"$configurationId"}""")
    }

    @Test
    fun a_credential_response_carrying_a_pid_from_an_unrecognised_signer_is_refused() = runTest {
        val credential = testMdocCredential("eu.europa.ec.eudi.pid.1", testSigner())
        val client = openID4VciHttpClient(
            engine = MockEngine { respond("""{"credentials":[{"credential":"$credential"}]}""", HttpStatusCode.OK, jsonContent) },
            issuerTrust = { _, _ -> TrustVerdict.NOT_TRUSTED },
        )

        // Thrown here, before multipaz certifies anything — so the document it was for is cleaned up.
        assertFailsWith<IssuerNotTrustedException> {
            client.postCredentialRequest("https://issuer.test/credential", "pid_mdoc")
        }
    }

    @Test
    fun a_credential_response_from_a_recognised_pid_signer_reaches_multipaz_unchanged() = runTest {
        val credential = testMdocCredential("eu.europa.ec.eudi.pid.1", testSigner())
        val body = """{"credentials":[{"credential":"$credential"}]}"""
        val client = openID4VciHttpClient(
            engine = MockEngine { respond(body, HttpStatusCode.OK, jsonContent) },
            issuerTrust = { _, _ -> TrustVerdict.TRUSTED },
        )

        val response = client.postCredentialRequest("https://issuer.test/credential", "pid_mdoc")

        // The body was read to check it, so it must have been handed on whole.
        assertEquals(body, response.bodyAsText())
    }

    @Test
    fun a_configuration_the_issuer_publishes_as_a_pid_is_checked_whatever_its_credential_claims() = runTest {
        val endpoint = "https://pid-config.test/credential"
        val metadata = """
            {"credential_issuer":"https://pid-config.test","credential_endpoint":"$endpoint",
             "credential_configurations_supported":{
               "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","scope":"pid"}}}
        """.trimIndent()
        // Typed as a driving licence, though the configuration it answers is a PID.
        val credential = testMdocCredential("org.iso.18013.5.1.mDL", testSigner())
        val client = openID4VciHttpClient(
            engine = MockEngine { request ->
                if (request.url.encodedPath.contains(".well-known")) {
                    respond(metadata, HttpStatusCode.OK, jsonContent)
                } else {
                    respond("""{"credentials":[{"credential":"$credential"}]}""", HttpStatusCode.OK, jsonContent)
                }
            },
            issuerTrust = { _, _ -> TrustVerdict.NOT_TRUSTED },
        )
        client.get("https://pid-config.test/.well-known/openid-credential-issuer").readRawBytes()

        assertFailsWith<IssuerNotTrustedException> { client.postCredentialRequest(endpoint, "pid_mdoc") }
    }

    // ---- the scope-instead-of-authorization_details workaround ---------------------------------

    private val issuerMetadataUrl = "https://issuer.test/.well-known/openid-credential-issuer"

    private val issuerMetadata = """
        {"credential_issuer":"https://issuer.test",
         "credential_configurations_supported":{
           "pid_mdoc":{"scope":"pid_scope","format":"mso_mdoc"},
           "pid_mdoc_deferred":{"scope":"pid_scope","format":"mso_mdoc"}}}
    """.trimIndent()

    private fun engineLearningBothMetadata(
        onPar: (String) -> Unit,
    ) = MockEngine { request ->
        when (request.url.toString()) {
            issuerMetadataUrl -> respond(
                issuerMetadata,
                headers = headersOf("Content-Type", "application/json"),
            )

            asMetadataUrl -> respond(asMetadata, headers = headersOf("Content-Type", "application/json"))
            challengeEndpoint -> respond("""{"attestation_challenge":"c"}""")
            parEndpoint -> {
                onPar(request.body.toByteArray().decodeToString())
                respond("", HttpStatusCode.Created)
            }

            else -> respond("", HttpStatusCode.OK)
        }
    }

    @Test
    fun authorization_details_are_replaced_by_the_configurations_scope() = runTest {
        var parBody = ""
        val client = openID4VciHttpClient(engineLearningBothMetadata { parBody = it })
        client.get(issuerMetadataUrl).readRawBytes()
        client.get(asMetadataUrl).readRawBytes()

        client.submitForm(
            url = parEndpoint,
            formParameters = parametersOf(
                "authorization_details" to listOf(
                    """[{"type":"openid_credential","credential_configuration_id":"pid_mdoc"}]"""
                ),
                "client_id" to listOf("eudiw-abca"),
            ),
        )

        val sent = parBody.parseUrlEncodedParameters()
        // The server understands this and not the RAR form multipaz prefers.
        assertEquals("pid_scope", sent["scope"])
        assertNull(sent["authorization_details"])
        // Everything else must survive the rewrite untouched.
        assertEquals("eudiw-abca", sent["client_id"])
    }

    @Test
    fun configurations_that_share_a_scope_send_it_once() = runTest {
        var parBody = ""
        val client = openID4VciHttpClient(engineLearningBothMetadata { parBody = it })
        client.get(issuerMetadataUrl).readRawBytes()
        client.get(asMetadataUrl).readRawBytes()

        // A document and its `_deferred` twin, which the dev and Plaut issuers both publish under one
        // scope — exactly what a multi-document request asks for.
        client.submitForm(
            url = parEndpoint,
            formParameters = parametersOf(
                "authorization_details" to listOf(
                    """[{"type":"openid_credential","credential_configuration_id":"pid_mdoc"},""" +
                        """{"type":"openid_credential","credential_configuration_id":"pid_mdoc_deferred"}]"""
                ),
            ),
        )

        // `scope` is a set (RFC 6749 §3.3), so a repeated value asks for nothing more.
        assertEquals("pid_scope", parBody.parseUrlEncodedParameters()["scope"])
    }

    @Test
    fun a_request_that_already_has_a_scope_is_not_rewritten() = runTest {
        var parBody = ""
        val client = openID4VciHttpClient(engineLearningBothMetadata { parBody = it })
        client.get(issuerMetadataUrl).readRawBytes()
        client.get(asMetadataUrl).readRawBytes()

        client.submitForm(
            url = parEndpoint,
            formParameters = parametersOf("scope" to listOf("already_here")),
        )

        assertEquals("already_here", parBody.parseUrlEncodedParameters()["scope"])
    }

    @Test
    fun an_unknown_configuration_id_leaves_the_request_as_multipaz_built_it() = runTest {
        var parBody = ""
        val client = openID4VciHttpClient(engineLearningBothMetadata { parBody = it })
        client.get(issuerMetadataUrl).readRawBytes()
        client.get(asMetadataUrl).readRawBytes()

        val details =
            """[{"type":"openid_credential","credential_configuration_id":"something_else"}]"""
        client.submitForm(
            url = parEndpoint,
            formParameters = parametersOf("authorization_details" to listOf(details)),
        )

        // Guessing a scope would be worse than letting the server reject a request we understand.
        val sent = parBody.parseUrlEncodedParameters()
        assertEquals(details, sent["authorization_details"])
        assertNull(sent["scope"])
    }

    @Test
    fun a_non_ok_logo_response_passes_through_untouched() = runTest {
        // multipaz already checks the status itself, so nothing needs doing here.
        val client = openID4VciHttpClient(
            MockEngine { respondError(HttpStatusCode.NotFound) }
        )

        assertEquals(
            HttpStatusCode.NotFound,
            client.get("https://examplestate.com/public/cor.png").status,
        )
    }

    /** Serves both metadata documents, and answers the PAR endpoint `200` with [body]. */
    private fun parAnswering(body: String) = MockEngine { request ->
        val json = headersOf("Content-Type", "application/json")
        when (request.url.toString()) {
            issuerMetadataUrl -> respond(issuerMetadata, headers = json)
            asMetadataUrl -> respond(asMetadata, headers = json)
            challengeEndpoint -> respond("""{"attestation_challenge":"c"}""", headers = json)
            else -> respond(body, HttpStatusCode.OK, json)
        }
    }

    // ---- a PAR answered 200 instead of 201 -----------------------------------------------------

    @Test
    fun a_successful_par_answered_200_is_reported_as_201() = runTest {
        // 🩹 multipaz's PAR loop breaks only on 201 and throws
        // "Error establishing authenticated channel with issuer" on anything else. A live EU
        // authorization server answers 200 with a valid `request_uri`, which is a success.
        val client = openID4VciHttpClient(parAnswering("""{"expires_in":3600,"request_uri":"urn:req:1"}"""))
        client.get(issuerMetadataUrl).readRawBytes()
        client.get(asMetadataUrl).readRawBytes()

        val response = client.submitForm(
            url = parEndpoint,
            formParameters = parametersOf("scope" to listOf("pid_scope")),
        )

        assertEquals(HttpStatusCode.Created, response.status)
        // The body must survive the rewrite: multipaz reads `request_uri` out of it next.
        assertTrue("urn:req" in response.bodyAsText())
    }

    @Test
    fun a_200_without_a_request_uri_is_left_alone() = runTest {
        // ⛔ Not every 200 is a successful PAR. Rewriting one that carries no `request_uri` would turn a
        // real failure into a confusing one further along, which is the opposite of the point.
        val client = openID4VciHttpClient(parAnswering("""{"error":"invalid_request"}"""))
        client.get(issuerMetadataUrl).readRawBytes()
        client.get(asMetadataUrl).readRawBytes()

        val response = client.submitForm(
            url = parEndpoint,
            formParameters = parametersOf("scope" to listOf("pid_scope")),
        )

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
