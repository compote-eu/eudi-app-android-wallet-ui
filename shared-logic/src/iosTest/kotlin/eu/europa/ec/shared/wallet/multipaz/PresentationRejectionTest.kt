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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Telling a verifier that the user declined — the one step of OpenID4VP the wallet walks itself.
 *
 * multipaz has no rejection mechanism at all (`access_denied` appears nowhere in its sources; declined
 * consent simply throws), so before this the verifier's transaction waited until it timed out. Measured
 * against the dev verifier on 2026-09-16: a hand-sent `access_denied` was accepted with HTTP 200 and
 * logged as a wallet response, while cancelling in the wallet logged nothing.
 *
 * The address for that answer is in the signed request object, which is why it is read **in passing**
 * as multipaz fetches it rather than by fetching it again — a `request_uri` may be single-use.
 */
class PresentationRejectionTest {

    private val responseUri = "https://verifier.test/wallet/direct_post/abc"

    /** The verifier the fixtures speak for; its certificate names this host, as the binding requires. */
    private val clientId = "x509_san_dns:verifier.test"

    private suspend fun requestObjectJwt(
        responseUri: String? = this.responseUri,
        state: String? = "the-state",
        clientId: String = this.clientId,
        signerDnsNames: List<String> = listOf("verifier.test"),
    ): String = signedRequestObject(
        claims = buildJsonObject {
            put("client_id", clientId)
            responseUri?.let { put("response_uri", it) }
            state?.let { put("state", it) }
            put("nonce", "n-123")
        },
        signer = testVerifierCertificate(dnsNames = signerDnsNames),
    )

    /** Drives a fetch through the observing engine, as multipaz's request-object fetch would. */
    private suspend fun noticeAfterFetching(
        body: String,
        isJwt: Boolean = true,
        method: HttpMethod = HttpMethod.Get,
    ): PresentationRequestNotice {
        val notice = PresentationRequestNotice(linkClientId = clientId)
        val engine = MockEngine {
            respond(
                body,
                HttpStatusCode.OK,
                io.ktor.http.headersOf(
                    "Content-Type",
                    listOf(if (isJwt) "application/oauth-authz-req+jwt" else "application/json"),
                ),
            )
        }
        val observed = PresentationObservingEngineFactory(notice, isVerifierTrusted = { true }) { engine }
        // The body must still be readable downstream: multipaz parses the very response this observes.
        val client = HttpClient(observed.create {})
        assertEquals(body, client.request("https://verifier.test/request.jwt") { this.method = method }.bodyAsText())
        return notice
    }

    @Test
    fun the_address_to_answer_is_learned_from_the_request_object() = runTest {
        val notice = noticeAfterFetching(requestObjectJwt())

        assertTrue(notice.canReject)
        assertEquals(responseUri, notice.responseUri)
        assertEquals("the-state", notice.state)
    }

    @Test
    fun a_request_object_without_a_response_uri_leaves_nothing_to_answer() = runTest {
        // `response_mode=fragment` requests have no `response_uri`; there is nobody to POST to, and
        // inventing a destination would be worse than staying silent.
        val notice = noticeAfterFetching(requestObjectJwt(responseUri = null))

        assertFalse(notice.canReject)
        assertNull(notice.responseUri)
    }

    @Test
    fun a_response_that_is_not_a_jwt_is_passed_through_untouched() = runTest {
        val notice = noticeAfterFetching("""{"not":"a jwt"}""", isJwt = false)

        assertFalse(notice.canReject)
    }

    @Test
    fun a_request_object_fetched_by_post_is_observed_too() = runTest {
        // With `request_uri_method=post` the request object is the answer to a POST.
        val notice = noticeAfterFetching(requestObjectJwt(), method = HttpMethod.Post)

        assertTrue(notice.canReject)
        assertEquals(responseUri, notice.responseUri)
    }

    @Test
    fun a_request_object_that_does_not_prove_its_verifier_fails_the_fetch_and_is_never_answered() = runTest {
        for (method in listOf(HttpMethod.Get, HttpMethod.Post)) {
            val notice = PresentationRequestNotice(linkClientId = clientId)
            val body = requestObjectJwt(signerDnsNames = listOf("impostor.test"))
            val engine = MockEngine {
                respond(body, HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/oauth-authz-req+jwt"))
            }
            val client = HttpClient(PresentationObservingEngineFactory(notice, isVerifierTrusted = { true }) { engine }.create {})

            // Refused before multipaz could read it, so the consent screen is never reached...
            assertFailsWith<ClientIdBindingException> {
                client.request("https://verifier.test/request.jwt") { this.method = method }
            }
            // ...and nothing was learned, so the request's response_uri is never written to either.
            assertFalse(notice.canReject, "$method: a refused request must leave nobody to answer")
            assertNull(notice.requestObject)
        }
    }

    @Test
    fun a_request_object_from_an_untrusted_verifier_is_refused_and_never_answered() = runTest {
        for (method in listOf(HttpMethod.Get, HttpMethod.Post)) {
            val notice = PresentationRequestNotice(linkClientId = clientId)
            val body = requestObjectJwt()
            val engine = MockEngine {
                respond(body, HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/oauth-authz-req+jwt"))
            }
            val client = HttpClient(PresentationObservingEngineFactory(notice, isVerifierTrusted = { false }) { engine }.create {})

            // Android's "Untrusted x5c": refused before multipaz matches anything or asks anyone...
            assertFailsWith<UntrustedVerifierException> {
                client.request("https://verifier.test/request.jwt") { this.method = method }
            }
            // ...and before the notice learned where to answer, so a Close on the blocked screen sends nothing.
            assertFalse(notice.canReject, "$method: a blocked request must leave nobody to answer")
            assertNull(notice.requestObject)
        }
    }

    @Test
    fun the_trust_check_is_asked_about_the_certificate_that_signed_the_request_object() = runTest {
        val notice = PresentationRequestNotice(linkClientId = clientId)
        val body = requestObjectJwt()
        val asked = mutableListOf<org.multipaz.crypto.X509CertChain>()
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/oauth-authz-req+jwt"))
        }
        val client = HttpClient(
            PresentationObservingEngineFactory(notice, isVerifierTrusted = { asked += it; true }) { engine }.create {}
        )

        client.request("https://verifier.test/request.jwt")

        val chain = asked.single()
        assertEquals(jwsCertificateChain(body)?.certificates, chain.certificates, "the request object's own x5c")
        assertTrue(notice.canReject, "a trusted verifier is answered as before")
    }

    @Test
    fun a_request_that_does_not_prove_its_verifier_is_refused_as_such_before_trust_is_asked() = runTest {
        val notice = PresentationRequestNotice(linkClientId = clientId)
        val body = requestObjectJwt(signerDnsNames = listOf("impostor.test"))
        var asked = false
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/oauth-authz-req+jwt"))
        }
        val client = HttpClient(
            PresentationObservingEngineFactory(notice, isVerifierTrusted = { asked = true; false }) { engine }.create {}
        )

        // A malformed request is a failure, not a block: it is the binding that refuses it.
        assertFailsWith<ClientIdBindingException> { client.request("https://verifier.test/request.jwt") }
        assertFalse(asked, "trust is a question about a certificate that already proved the client_id")
    }

    /** Fetches the request object, then posts a response the verifier answers with [status] and [answer]. */
    private suspend fun noticeAfterResponding(status: HttpStatusCode, answer: String): PresentationRequestNotice {
        val notice = PresentationRequestNotice(linkClientId = clientId)
        val requestObject = requestObjectJwt()
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                respond(requestObject, HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/oauth-authz-req+jwt"))
            } else {
                respond(answer, status, io.ktor.http.headersOf("Content-Type", "application/json"))
            }
        }
        val client = HttpClient(PresentationObservingEngineFactory(notice, isVerifierTrusted = { true }) { engine }.create {})
        client.request("https://verifier.test/request.jwt")
        // multipaz reads the very answer this observes, so its body must survive being read here.
        assertEquals(answer, client.request(responseUri) { method = HttpMethod.Post }.bodyAsText())
        return notice
    }

    @Test
    fun a_verifiers_refusal_is_kept_for_the_log() = runTest {
        // The EUDI verifier's answer to an unreadable response, measured on both dev verifiers 2026-09-30.
        val notice = noticeAfterResponding(
            HttpStatusCode.BadRequest,
            """{"error":"InvalidEncryptedResponse","description":"Invalid serialized unsecured/JWS/JWE object: Missing part delimiters","cause":null}""",
        )

        assertEquals(
            "400 Bad Request InvalidEncryptedResponse: Invalid serialized unsecured/JWS/JWE object: Missing part delimiters",
            notice.verifierRefusal,
        )
    }

    @Test
    fun an_accepted_response_leaves_nothing_to_log() = runTest {
        assertNull(noticeAfterResponding(HttpStatusCode.OK, "{}").verifierRefusal)
    }

    @Test
    fun a_refusal_that_is_not_the_verifiers_json_is_kept_as_it_came() = runTest {
        assertEquals(
            "502 Bad Gateway upstream timed out",
            noticeAfterResponding(HttpStatusCode.BadGateway, "upstream timed out").verifierRefusal,
        )
    }

    @Test
    fun the_rejection_is_posted_as_access_denied_with_the_requests_state() = runTest {
        val notice = noticeAfterFetching(requestObjectJwt())
        var posted: String? = null
        var postedTo: String? = null
        val client = HttpClient(
            MockEngine { request ->
                postedTo = request.url.toString()
                posted = request.body.toByteArray().decodeToString()
                respond("{}", HttpStatusCode.OK)
            }
        )

        assertTrue(sendPresentationRejection(notice, client))

        assertEquals(responseUri, postedTo)
        val form = posted!!.parseUrlEncodedParameters()
        assertEquals("access_denied", form["error"])
        // Without the state the verifier cannot tell which transaction was declined.
        assertEquals("the-state", form["state"])
    }

    @Test
    fun declining_before_the_request_object_arrived_sends_nothing() = runTest {
        var called = false
        val client = HttpClient(MockEngine { called = true; respond("{}", HttpStatusCode.OK) })

        assertFalse(sendPresentationRejection(PresentationRequestNotice(), client))

        assertFalse(called, "there is no verifier to tell yet, so nothing may be sent")
    }

    @Test
    fun a_verifier_that_refuses_the_rejection_is_reported_not_thrown() = runTest {
        val notice = noticeAfterFetching(requestObjectJwt())
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.BadRequest) })

        // A user who declined has finished either way: this must never become an error they cannot act
        // on, and it must never take down the teardown that follows it.
        assertFalse(sendPresentationRejection(notice, client))
    }

    @Test
    fun a_transport_failure_is_swallowed_rather_than_thrown() = runTest {
        val notice = noticeAfterFetching(requestObjectJwt())
        val client = HttpClient(MockEngine { throw IllegalStateException("the network blinked") })

        assertFalse(sendPresentationRejection(notice, client))
    }
}
