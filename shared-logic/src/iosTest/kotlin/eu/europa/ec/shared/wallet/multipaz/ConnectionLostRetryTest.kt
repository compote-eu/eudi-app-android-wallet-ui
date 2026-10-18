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
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSError
import platform.Foundation.NSPOSIXErrorDomain
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorTimedOut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one retry for a connection iOS reclaimed while the wallet was suspended — and the failures that must NOT
 * be retried. The exceptions are the real ones Ktor's Darwin engine throws, carrying a real `NSError`.
 */
class ConnectionLostRetryTest {

    private fun darwinError(code: Long, domain: String = NSURLErrorDomain!!) =
        DarwinHttpRequestException(NSError.errorWithDomain(domain, code, null))

    private val connectionLost get() = darwinError(NSURLErrorNetworkConnectionLost)

    /** A client over an engine that fails the first [failures] requests with [failure], then answers 200. */
    private fun clientFailing(failures: Int, failure: () -> Throwable, bodies: MutableList<String>): HttpClient {
        var calls = 0
        return HttpClient(
            MockEngine { request ->
                bodies += request.body.toByteArray().decodeToString()
                if (calls++ < failures) throw failure()
                respond("ok", HttpStatusCode.OK)
            }
        ) { retryOnceWhenConnectionLost() }
    }

    @Test
    fun a_request_whose_connection_was_lost_is_sent_once_more_with_the_same_body() = runTest {
        val bodies = mutableListOf<String>()
        val client = clientFailing(failures = 1, failure = { connectionLost }, bodies = bodies)

        val response = client.submitForm(
            "https://issuer.test/token",
            parametersOf("grant_type" to listOf("authorization_code"), "code" to listOf("the-code")),
        )

        assertEquals("ok", response.bodyAsText())
        assertEquals(2, bodies.size, "exactly one retry")
        assertEquals(bodies[0], bodies[1], "the retry sends the same form, not an empty one")
        assertTrue("code=the-code" in bodies[1])
    }

    @Test
    fun a_connection_lost_twice_is_given_up_after_one_retry() = runTest {
        val bodies = mutableListOf<String>()
        val client = clientFailing(failures = Int.MAX_VALUE, failure = { connectionLost }, bodies = bodies)

        val thrown = assertFailsWith<DarwinHttpRequestException> { client.post("https://issuer.test/credential") }

        assertTrue(thrown.isConnectionLost())
        assertEquals(2, bodies.size, "one retry, then the failure is the caller's to report")
    }

    @Test
    fun a_timeout_is_not_retried() = runTest {
        val bodies = mutableListOf<String>()
        val client = clientFailing(failures = 1, failure = { darwinError(NSURLErrorTimedOut) }, bodies = bodies)

        assertFailsWith<DarwinHttpRequestException> { client.post("https://issuer.test/credential") }

        assertEquals(1, bodies.size, "a slow server is not a dead socket")
    }

    @Test
    fun a_server_error_is_not_retried() = runTest {
        var calls = 0
        val client = HttpClient(
            MockEngine { calls++; respond("broken", HttpStatusCode.InternalServerError) }
        ) { retryOnceWhenConnectionLost() }

        val response = client.post("https://issuer.test/credential")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(1, calls, "an issuer's answer, even an error, is its answer")
    }

    @Test
    fun only_the_url_loading_systems_connection_lost_counts() {
        assertTrue(connectionLost.isConnectionLost())
        assertTrue(RuntimeException("wrapped", connectionLost).isConnectionLost())
        // The same number in another domain means something else entirely.
        assertFalse(darwinError(NSURLErrorNetworkConnectionLost, NSPOSIXErrorDomain!!).isConnectionLost())
        assertFalse(darwinError(NSURLErrorTimedOut).isConnectionLost())
        assertFalse(IllegalStateException("nope").isConnectionLost())
    }

    @Test
    fun the_openid4vci_client_retries_a_lost_connection_too() = runTest {
        // Every issuance flow — multipaz's and the batch client — posts through this client.
        var calls = 0
        val client = openID4VciHttpClient(
            MockEngine {
                if (calls++ == 0) throw connectionLost
                respond("""{"attestation_challenge":"c"}""", HttpStatusCode.OK)
            },
            issuerTrust = null,
        )

        val response = client.post("https://as.test/challenge")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, calls)
    }
}
