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
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** What a refused token request logs — its reason, and nothing else from the body. */
class OAuthErrorTest {

    private suspend fun refusal(status: HttpStatusCode, body: String): String =
        HttpClient(MockEngine { respond(body, status, headersOf("Content-Type", "application/json")) })
            .post("https://as.test/token")
            .oauthError()

    @Test
    fun the_error_and_its_description_are_named() = runTest {
        assertEquals(
            "400 Bad Request invalid_grant: Token is not active",
            refusal(HttpStatusCode.BadRequest, """{"error":"invalid_grant","error_description":"Token is not active"}"""),
        )
    }

    @Test
    fun an_error_without_a_description_is_named_alone() = runTest {
        assertEquals("401 Unauthorized invalid_client", refusal(HttpStatusCode.Unauthorized, """{"error":"invalid_client"}"""))
    }

    @Test
    fun nothing_else_in_the_body_reaches_the_log() = runTest {
        val logged = refusal(
            HttpStatusCode.BadRequest,
            """{"error":"invalid_grant","refresh_token":"a-secret","access_token":"another-secret"}""",
        )

        assertEquals("400 Bad Request invalid_grant", logged)
        assertFalse("secret" in logged)
    }

    @Test
    fun a_body_that_is_not_an_oauth_error_leaves_the_status_alone() = runTest {
        assertEquals("502 Bad Gateway", refusal(HttpStatusCode.BadGateway, "<html>upstream down</html>"))
    }
}
