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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.provisioning.CredentialKeyAttestation
import org.multipaz.securearea.KeyAttestation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The wallet-provider client, against a `MockEngine`.
 *
 * What these pin down is the **wire contract with a service this repository does not own**: paths, the
 * field names inside the request bodies, and the field the JWT is read out of. Those were copied from
 * Android's `WalletAttestationRepositoryImpl`, and a silent drift from it — a renamed field, a changed
 * path — would fail at issuance time against a live server with a 400 that says very little. Here it
 * fails in a second.
 */
class IosOpenID4VciBackendTest {

    private suspend fun keyAttestation() = KeyAttestation(
        publicKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
        certChain = null,
    )

    private fun backend(engine: MockEngine) = IosOpenID4VciBackend(
        walletProviderBaseUrl = "https://wallet-provider.test",
        clientId = "eudiw-abca",
        httpClient = HttpClient(engine),
    )

    @Test
    fun the_client_id_is_the_configured_one() = runTest {
        assertEquals("eudiw-abca", backend(MockEngine { respond("") }).getClientId())
    }

    @Test
    fun a_wallet_attestation_posts_the_public_jwk_and_reads_the_jwt_back() = runTest {
        var path: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            body = request.body.toByteArray().decodeToString()
            respond(
                content = """{"walletInstanceAttestation":"the.wallet.jwt"}""",
                headers = headersOf("Content-Type", "application/json"),
            )
        }

        val attestation = backend(engine).createJwtWalletAttestation(keyAttestation())

        assertEquals("the.wallet.jwt", attestation)
        assertEquals("/wallet-instance-attestation/jwk", path)
        val jwk = Json.parseToJsonElement(body!!).jsonObject["jwk"]!!.jsonObject
        // A public EC JWK and nothing more — no private material may cross this boundary.
        assertEquals("EC", jwk["kty"]?.jsonPrimitive?.content)
        assertEquals("P-256", jwk["crv"]?.jsonPrimitive?.content)
        assertTrue(jwk["x"] != null && jwk["y"] != null)
        assertTrue(jwk["d"] == null, "the private scalar must never be sent")
    }

    @Test
    fun a_key_attestation_posts_every_credential_key_with_the_challenge() = runTest {
        var body: String? = null
        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            body = request.body.toByteArray().decodeToString()
            respond(
                content = """{"keyAttestation":"the.key.jwt"}""",
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val credentials = listOf(
            CredentialKeyAttestation("cred-1", keyAttestation()),
            CredentialKeyAttestation("cred-2", keyAttestation()),
        )

        val attestation = backend(engine).createJwtKeyAttestation(
            credentialKeyAttestations = credentials,
            challenge = "the-c-nonce",
        )

        assertEquals("the.key.jwt", attestation)
        assertEquals("/key-attestation/jwk-set", path)
        val json = Json.parseToJsonElement(body!!).jsonObject
        assertEquals("the-c-nonce", json["nonce"]?.jsonPrimitive?.content)
        // One entry per credential key, in the batch the issuer will certify.
        assertEquals(2, json["jwkSet"]!!.jsonObject["keys"]!!.jsonArray.size)
    }

    @Test
    fun a_rejected_request_reports_the_status_rather_than_a_null_token() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadRequest, "no such client") }

        val failure = assertFailsWith<IllegalStateException> {
            backend(engine).createJwtWalletAttestation(keyAttestation())
        }

        assertTrue("400" in failure.message.orEmpty(), "unexpected: ${failure.message}")
        assertTrue("no such client" in failure.message.orEmpty(), "unexpected: ${failure.message}")
    }

    @Test
    fun a_response_without_the_expected_field_is_an_error_not_an_empty_attestation() = runTest {
        // A 200 whose body is a different shape would otherwise become an empty proof and fail much
        // later, at the credential endpoint.
        val engine = MockEngine {
            respond(
                content = """{"somethingElse":"x"}""",
                headers = headersOf("Content-Type", "application/json"),
            )
        }

        val failure = assertFailsWith<IllegalStateException> {
            backend(engine).createJwtKeyAttestation(
                credentialKeyAttestations = listOf(CredentialKeyAttestation("c", keyAttestation())),
                challenge = "n",
            )
        }

        assertTrue("keyAttestation" in failure.message.orEmpty(), "unexpected: ${failure.message}")
    }

    @Test
    fun a_client_assertion_is_refused_with_an_explanation() = runTest {
        // Reachable only against an authorization server that offers `private_key_jwt` and not
        // `attest_jwt_client_auth`; the EU dev servers offer both, so multipaz never asks.
        val failure = assertFailsWith<UnsupportedOperationException> {
            backend(MockEngine { respond("") }).createJwtClientAssertion("https://as.test")
        }

        assertTrue("as.test" in failure.message.orEmpty(), "unexpected: ${failure.message}")
    }
}
