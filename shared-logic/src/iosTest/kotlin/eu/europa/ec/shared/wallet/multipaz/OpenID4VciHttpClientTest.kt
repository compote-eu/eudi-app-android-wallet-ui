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

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parametersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun jwtOf(payload: String): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        return listOf(
            b64.encode("""{"typ":"JWT","alg":"ES256","x5c":["fake"]}""".encodeToByteArray()),
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
            }
        )

        val body = client.get(metadataUrl).readRawBytes().decodeToString()

        // multipaz parses this with `Json.parseToJsonElement(...).jsonObject`, so it must be the object.
        assertEquals(metadata, body)
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
        // The one check that survives not verifying the signature: a document that describes someone
        // else was substituted, whoever signed it.
        val client = openID4VciHttpClient(
            MockEngine {
                respond(
                    content = jwtOf("""{"credential_issuer":"https://attacker.test"}"""),
                    headers = headersOf("Content-Type", "application/jwt"),
                )
            }
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

    private val asMetadata = """
        {"issuer":"https://as.test/realms/r",
         "challenge_endpoint":"$challengeEndpoint",
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

        val token = client.post("https://as.test/realms/r/protocol/openid-connect/token")

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
}
