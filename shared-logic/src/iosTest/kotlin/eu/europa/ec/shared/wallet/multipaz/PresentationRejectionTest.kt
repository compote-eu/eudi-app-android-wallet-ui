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
        val observed = PresentationObservingEngineFactory(notice) { engine }
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
            val client = HttpClient(PresentationObservingEngineFactory(notice) { engine }.create {})

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
