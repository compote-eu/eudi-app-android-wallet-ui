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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.JsonWebEncryption
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OpenID4VCI 1.0 credential request and response encryption: the offer rules, the engine rule that applies
 * them to multipaz's requests, and the deferred collector that applies them by hand.
 *
 * The stand-in issuer is real on the one point that matters: it **decrypts** what it is sent with its own
 * private key and **encrypts** its answer to the key the request carried, exactly as Plaut's dev issuer has
 * to. Nothing here can pass by sending plaintext and reading plaintext.
 *
 * ⚠️ [CredentialEncryptionRegistry] is process-wide, so every test uses a host of its own.
 */
class CredentialEncryptionTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf("Content-Type", "application/json")
    private val jwtHeaders = headersOf("Content-Type", "application/jwt")

    /** The issuer's side: its request-encryption key and the metadata that publishes it. */
    private class StandInIssuer(val privateKey: EcPrivateKey, val metadata: JsonObject) {
        val decryptionKey: AsymmetricKey get() = AsymmetricKey.anonymous(privateKey)
    }

    /**
     * Metadata in the shape Plaut's dev issuer publishes (measured 2026-09-28): an EC P-256 key with a
     * `kid` plus `x5c`/`nbf`/`exp`, `ECDH-ES`, `A128GCM` first, both halves required.
     */
    private suspend fun standInIssuer(
        host: String,
        requestRequired: Boolean = true,
        responseRequired: Boolean = true,
        encValues: List<String> = listOf("A128GCM", "A256GCM"),
        withKid: Boolean = true,
        withDeferred: Boolean = false,
    ): StandInIssuer {
        val privateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val jwk = privateKey.publicKey.toJwk(
            buildJsonObject {
                if (withKid) put("kid", "request-encryption")
                put("use", "enc")
                put("alg", "ECDH-ES")
                // Present in Plaut's key and irrelevant to it; they must not stop it being used.
                put("x5c", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("MIIB")) })
                put("nbf", 1790000000)
                put("exp", 1990000000)
            }
        )
        val enc = buildJsonArray { encValues.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        val metadata = buildJsonObject {
            put("credential_issuer", "https://$host")
            put("credential_endpoint", "https://$host/credential")
            if (withDeferred) put("deferred_credential_endpoint", "https://$host/deferred")
            put("credential_request_encryption", buildJsonObject {
                put("jwks", buildJsonObject { put("keys", buildJsonArray { add(jwk) }) })
                put("enc_values_supported", enc)
                put("encryption_required", requestRequired)
            })
            put("credential_response_encryption", buildJsonObject {
                put("alg_values_supported", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("ECDH-ES")) })
                put("enc_values_supported", enc)
                put("encryption_required", responseRequired)
            })
        }
        return StandInIssuer(privateKey, metadata)
    }

    /** What the issuer does with a request: decrypt it, and encrypt [answer] to the key it names. */
    private suspend fun StandInIssuer.answer(requestJwe: String, answer: JsonObject): Pair<JsonObject, String> {
        val request = JsonWebEncryption.decrypt(requestJwe, decryptionKey)
        val responseEncryption = request["credential_response_encryption"]!!.jsonObject
        val walletKey = EcPublicKey.fromJwk(responseEncryption["jwk"]!!.jsonObject)
        val method = Algorithm.fromJoseAlgorithmIdentifier(responseEncryption["enc"]!!.jsonPrimitive.content)
        // Empty rather than null, for the reason given in [CredentialExchange.encode].
        val encrypted = JsonWebEncryption.encrypt(answer, walletKey, method, apu = ByteString(), apv = ByteString())
        return request to encrypted
    }

    private val credentials = buildJsonObject {
        put("credentials", buildJsonArray { add(buildJsonObject { put("credential", "the-credential") }) })
    }

    // ── The offer ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun metadata_shaped_like_plauts_encrypts_both_halves_with_the_issuers_first_method() = runTest {
        val encryption = CredentialEncryption.fromIssuerMetadata(standInIssuer("offer-a.test").metadata)

        assertTrue(encryption.isActive)
        assertEquals("request=A128GCM/kid=request-encryption response=A128GCM", encryption.toString())
    }

    @Test
    fun an_issuer_that_offers_encryption_without_requiring_it_is_encrypted_too_as_on_android() = runTest {
        // The EU issuer's shape: both offered, neither required. Android encrypts it; so does this.
        val metadata = standInIssuer("offer-b.test", requestRequired = false, responseRequired = false).metadata

        assertTrue(CredentialEncryption.fromIssuerMetadata(metadata).isActive)
    }

    @Test
    fun a_required_request_encryption_without_a_usable_key_is_refused() = runTest {
        val metadata = standInIssuer("offer-c.test", withKid = false).metadata

        val refused = assertFailsWith<IllegalStateException> { CredentialEncryption.fromIssuerMetadata(metadata) }
        assertTrue(refused.message!!.contains("requires encrypted credential requests"), refused.message)
    }

    @Test
    fun an_unusable_offer_that_is_not_required_stays_plain() = runTest {
        val metadata = standInIssuer(
            "offer-d.test",
            requestRequired = false,
            responseRequired = false,
            encValues = listOf("A128CBC-HS256"),
        ).metadata

        val encryption = CredentialEncryption.fromIssuerMetadata(metadata)
        assertFalse(encryption.isActive)
        val body = encryption.begin().encode(buildJsonObject { put("credential_configuration_id", "x") })
        assertEquals(ContentType.Application.Json, body.contentType)
        assertEquals("""{"credential_configuration_id":"x"}""", body.text)
    }

    @Test
    fun metadata_without_an_offer_is_plain() = runTest {
        val encryption = CredentialEncryption.fromIssuerMetadata(
            buildJsonObject { put("credential_endpoint", "https://offer-e.test/credential") }
        )

        assertFalse(encryption.isActive)
    }

    // ── One exchange ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_request_round_trips_through_the_issuer_and_its_answer_comes_back_readable() = runTest {
        val issuer = standInIssuer("exchange-a.test")
        val exchange = CredentialEncryption.fromIssuerMetadata(issuer.metadata).begin()
        val payload = buildJsonObject {
            put("credential_configuration_id", "eu.europa.ec.eudi.pid_mso_mdoc")
            put("proofs", buildJsonObject { put("jwt", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("p")) }) })
        }

        val body = exchange.encode(payload)
        assertEquals(ContentType("application", "jwt"), body.contentType)
        assertEquals(5, body.text.split('.').size, "a compact JWE, not JSON")

        val (received, answer) = issuer.answer(body.text, credentials)
        // Everything the wallet meant to send arrives, plus the key to answer to.
        assertEquals(payload["credential_configuration_id"], received["credential_configuration_id"])
        assertEquals(payload["proofs"], received["proofs"])
        val responseEncryption = received["credential_response_encryption"]!!.jsonObject
        assertEquals("A128GCM", responseEncryption["enc"]!!.jsonPrimitive.content)
        val walletJwk = responseEncryption["jwk"]!!.jsonObject
        assertEquals("EC", walletJwk["kty"]!!.jsonPrimitive.content)
        assertEquals("enc", walletJwk["use"]!!.jsonPrimitive.content)
        assertEquals("ECDH-ES", walletJwk["alg"]!!.jsonPrimitive.content)
        assertNotNull(walletJwk["kid"])
        assertFalse("d" in walletJwk, "only the public half is sent")

        assertEquals(credentials, json.parseToJsonElement(exchange.decode(answer)).jsonObject)
    }

    @Test
    fun a_plain_answer_to_a_request_for_an_encrypted_one_is_refused() = runTest {
        val exchange = CredentialEncryption.fromIssuerMetadata(standInIssuer("exchange-b.test").metadata).begin()
        exchange.encode(buildJsonObject { put("credential_configuration_id", "x") })

        assertFailsWith<IllegalStateException> { exchange.decode(credentials.toString()) }
    }

    @Test
    fun each_exchange_sends_a_response_key_of_its_own() = runTest {
        val issuer = standInIssuer("exchange-c.test")
        val encryption = CredentialEncryption.fromIssuerMetadata(issuer.metadata)
        val payload = buildJsonObject { put("credential_configuration_id", "x") }

        fun JsonObject.walletKey() = this["credential_response_encryption"]!!.jsonObject["jwk"]!!.jsonObject["x"]
        val first = JsonWebEncryption.decrypt(encryption.begin().encode(payload).text, issuer.decryptionKey)
        val second = JsonWebEncryption.decrypt(encryption.begin().encode(payload).text, issuer.decryptionKey)

        assertTrue(first.walletKey() != second.walletKey())
    }

    // ── The engine, for every request multipaz makes ───────────────────────────────────────────────

    /** An engine client over [issuer], with the metadata already read — as multipaz reads it first. */
    private suspend fun engineOver(
        host: String,
        issuer: StandInIssuer?,
        metadata: JsonObject = issuer!!.metadata,
        deferredNotice: DeferredIssuanceNotice? = null,
        credentialAnswer: suspend (requestBody: String, requestContentType: String?) -> Pair<HttpStatusCode, Pair<String, Boolean>>,
    ): HttpClient {
        val client = openID4VciHttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    url.endsWith(".well-known/openid-credential-issuer") ->
                        respond(metadata.toString(), HttpStatusCode.OK, jsonHeaders)
                    url == "https://$host/credential" -> {
                        val (status, answer) = credentialAnswer(
                            request.body.toByteArray().decodeToString(),
                            request.body.contentType?.toString(),
                        )
                        val (text, encrypted) = answer
                        respond(text, status, if (encrypted) jwtHeaders else jsonHeaders)
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
            deferredNotice = deferredNotice,
            issuerTrust = null,
        )
        client.get("https://$host/.well-known/openid-credential-issuer")
        return client
    }

    private suspend fun HttpClient.postCredentialRequest(host: String) = post("https://$host/credential") {
        contentType(ContentType.Application.Json)
        setBody("""{"credential_configuration_id":"eu.europa.ec.eudi.pid_mso_mdoc"}""")
    }

    @Test
    fun the_engine_encrypts_the_request_and_hands_multipaz_the_decrypted_answer() = runTest {
        val host = "engine-a.test"
        val issuer = standInIssuer(host)
        var received: JsonObject? = null
        var receivedContentType: String? = null
        val client = engineOver(host, issuer) { body, contentType ->
            receivedContentType = contentType
            val (request, answer) = issuer.answer(body, credentials)
            received = request
            HttpStatusCode.OK to (answer to true)
        }

        val response = client.postCredentialRequest(host)

        assertEquals("application/jwt", receivedContentType)
        assertEquals("eu.europa.ec.eudi.pid_mso_mdoc", received!!["credential_configuration_id"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals(credentials, json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }

    @Test
    fun an_encrypted_deferral_is_decrypted_before_it_is_noted() = runTest {
        // The deferral note reads `transaction_id` out of the body; from a JWE it would read nothing and
        // the document would be deleted instead of parked.
        val host = "engine-b.test"
        val issuer = standInIssuer(host)
        val notice = DeferredIssuanceNotice()
        val client = engineOver(host, issuer, deferredNotice = notice) { body, _ ->
            val deferral = buildJsonObject { put("transaction_id", "txn-1"); put("interval", 30) }
            HttpStatusCode.Accepted to (issuer.answer(body, deferral).second to true)
        }

        val response = client.postCredentialRequest(host)

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("txn-1", notice.transactionId)
        assertEquals(30, notice.retryAfterSeconds)
    }

    @Test
    fun an_encrypted_deferral_sent_as_200_is_handed_on_as_the_202_the_plain_protocol_uses() = runTest {
        // What the EUDI reference issuer actually does, and what Plaut's dev issuer sent on 2026-09-28:
        // every encrypted answer is `200`, so the deferral is only visible inside the JWE.
        val host = "engine-f.test"
        val issuer = standInIssuer(host)
        val notice = DeferredIssuanceNotice()
        val client = engineOver(host, issuer, deferredNotice = notice) { body, _ ->
            val deferral = buildJsonObject { put("transaction_id", "txn-2"); put("interval", 10) }
            HttpStatusCode.OK to (issuer.answer(body, deferral).second to true)
        }

        val response = client.postCredentialRequest(host)

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("txn-2", notice.transactionId)
        assertEquals(10, notice.retryAfterSeconds)
    }

    @Test
    fun an_error_answer_is_passed_through_as_it_came() = runTest {
        val host = "engine-c.test"
        val issuer = standInIssuer(host)
        val error = """{"error":"invalid_proof","error_description":"bad nonce"}"""
        val client = engineOver(host, issuer) { _, _ -> HttpStatusCode.BadRequest to (error to false) }

        val response = client.postCredentialRequest(host)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(error, response.bodyAsText())
    }

    @Test
    fun a_request_to_an_issuer_that_offers_no_encryption_is_left_alone() = runTest {
        val host = "engine-d.test"
        val metadata = buildJsonObject {
            put("credential_issuer", "https://$host")
            put("credential_endpoint", "https://$host/credential")
        }
        var sent: String? = null
        var sentContentType: String? = null
        val client = engineOver(host, issuer = null, metadata = metadata) { body, contentType ->
            sent = body
            sentContentType = contentType
            HttpStatusCode.OK to (credentials.toString() to false)
        }

        client.postCredentialRequest(host)

        assertTrue(sentContentType!!.startsWith("application/json"))
        assertEquals("""{"credential_configuration_id":"eu.europa.ec.eudi.pid_mso_mdoc"}""", sent)
    }

    @Test
    fun an_issuer_requiring_encryption_it_offers_no_usable_key_for_is_refused_before_sending() = runTest {
        val host = "engine-e.test"
        val issuer = standInIssuer(host, withKid = false)
        var reached = false
        val client = engineOver(host, issuer) { _, _ ->
            reached = true
            HttpStatusCode.OK to (credentials.toString() to false)
        }

        val refused = assertFailsWith<IllegalStateException> { client.postCredentialRequest(host) }
        assertTrue(refused.message!!.contains("requires encrypted credential requests"), refused.message)
        assertFalse(reached, "nothing was sent in plain JSON")
    }

    // ── The deferred collector, which is not behind the engine ─────────────────────────────────────

    /**
     * A collector over a stand-in issuer. [deferredAnswer] gets each decrypted deferred request and the
     * call's ordinal, and returns the status and the *plaintext* answer, which is then encrypted to the
     * key that request carried — or served as-is for a status that is not a success.
     */
    private suspend fun collectorOver(
        host: String,
        issuer: StandInIssuer,
        deferredAnswer: (request: JsonObject, call: Int) -> Pair<HttpStatusCode, JsonObject>,
    ): IosDeferredCredentialCollector {
        val metadata = JsonObject(
            issuer.metadata + ("authorization_servers" to buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("https://as.$host"))
            })
        )
        var deferredCalls = 0
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.endsWith(".well-known/openid-credential-issuer") ->
                    respond(metadata.toString(), HttpStatusCode.OK, jsonHeaders)
                url.endsWith(".well-known/openid-configuration") ->
                    respond("""{"token_endpoint":"https://as.$host/token"}""", HttpStatusCode.OK, jsonHeaders)
                url.endsWith("/wallet-instance-attestation/jwk") ->
                    respond("""{"walletInstanceAttestation":"wia"}""", HttpStatusCode.OK, jsonHeaders)
                url == "https://as.$host/token" ->
                    respond("""{"access_token":"at","expires_in":300}""", HttpStatusCode.OK, jsonHeaders)
                url == "https://$host/deferred" -> {
                    deferredCalls++
                    val body = request.body.toByteArray().decodeToString()
                    val decrypted = JsonWebEncryption.decrypt(body, issuer.decryptionKey)
                    val (status, plain) = deferredAnswer(decrypted, deferredCalls)
                    if (status.value in 200..299) {
                        respond(issuer.answer(body, plain).second, status, jwtHeaders)
                    } else {
                        respond(plain.toString(), status, headersOf("DPoP-Nonce", listOf("the-nonce")))
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return IosDeferredCredentialCollector(
            httpClient = HttpClient(engine),
            walletProviderBaseUrl = "https://wallet-provider.$host",
            clientId = "eudiw-abca",
        )
    }

    private suspend fun IosDeferredCredentialCollector.collectFrom(host: String) = collect(
        issuerUrl = "https://$host",
        transactionId = "txn-abc",
        refreshToken = "rt",
        dpopKey = softwareKey("dpop"),
        attestationKey = softwareKey("attestation"),
    )

    @Test
    fun a_deferred_collection_is_encrypted_and_each_attempt_decrypts_with_its_own_key() = runTest {
        // The live issuers refuse the first deferred attempt with a DPoP nonce, so the answer that carries
        // the credential is to the SECOND request — and has to be decrypted with that request's key.
        val host = "collector-a.test"
        val issuer = standInIssuer(host, withDeferred = true)
        val receivedTransactionIds = mutableListOf<String>()
        val collector = collectorOver(host, issuer) { request, call ->
            receivedTransactionIds += request["transaction_id"]!!.jsonPrimitive.content
            if (call == 1) HttpStatusCode.Unauthorized to buildJsonObject { put("error", "use_dpop_nonce") }
            else HttpStatusCode.OK to credentials
        }

        val result = collector.collectFrom(host)

        assertIs<DeferredCollection.Issued>(result)
        assertEquals(listOf("the-credential"), result.credentials)
        assertEquals(listOf("txn-abc", "txn-abc"), receivedTransactionIds, "both attempts were readable by the issuer")
    }

    @Test
    fun an_encrypted_pending_202_without_a_handle_is_still_pending() = runTest {
        // The reference issuer answers a pending deferred request with `202`; the body need not repeat
        // the transaction id, and without this it was read as "success, but no credential".
        val host = "collector-b.test"
        val issuer = standInIssuer(host, withDeferred = true)
        val collector = collectorOver(host, issuer) { _, _ ->
            HttpStatusCode.Accepted to buildJsonObject { put("interval", 60) }
        }

        val result = collector.collectFrom(host)

        assertIs<DeferredCollection.StillPending>(result)
        assertEquals(60, result.retryAfterSeconds)
    }

    private suspend fun softwareKey(alias: String): AsymmetricKey {
        val secureArea = SoftwareSecureArea.create(EphemeralStorage())
        secureArea.createKey(alias, SoftwareCreateKeySettings.Builder().setAlgorithm(Algorithm.ESP256).build())
        return AsymmetricKey.anonymous(secureArea, alias)
    }
}
