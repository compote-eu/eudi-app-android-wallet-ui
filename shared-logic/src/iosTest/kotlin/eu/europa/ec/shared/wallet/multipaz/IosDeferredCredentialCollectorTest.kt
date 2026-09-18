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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toBase64Url
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [IosDeferredCredentialCollector] against a `MockEngine`, which is the only way to pin the protocol
 * details this flow turns on. The live run that discovered them is a probe (`--dpop-probe`) and cannot
 * be a test — the real issuer's requirements are what these assertions encode:
 *
 * - the **`ath` claim is mandatory**, so the proof this builds must carry it;
 * - the issuer **always** refuses the first attempt with `use_dpop_nonce`, so the retry is the normal
 *   path rather than an error branch, and the retried proof must carry the nonce it was handed;
 * - the client-attestation PoP claim is **`challenge`**, not `nonce`.
 *
 * A `SoftwareSecureArea` stands in for the Secure Enclave: a mock server verifies no signatures, and
 * the question of whether an *enclave* signature is accepted is a live one, answered by the probe.
 */
@OptIn(ExperimentalEncodingApi::class)
class IosDeferredCredentialCollectorTest {

    private val issuerUrl = "https://issuer.test"
    private val deferredEndpoint = "https://issuer.test/wallet/deferredEndpoint"
    private val tokenEndpoint = "https://as.test/token"
    private val json = Json { ignoreUnknownKeys = true }

    /** Issuer metadata as a signed JWT, which is what the EU dev issuer actually serves. */
    private fun issuerMetadataJwt(withDeferred: Boolean = true): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val deferred = if (withDeferred) """"deferred_credential_endpoint":"$deferredEndpoint",""" else ""
        val payload = """{"credential_issuer":"$issuerUrl",$deferred""" +
                """"authorization_servers":["https://as.test"]}"""
        return listOf(
            b64.encode("""{"typ":"JWT","alg":"ES256","x5c":["fake"]}""".encodeToByteArray()),
            b64.encode(payload.encodeToByteArray()),
            b64.encode("signature".encodeToByteArray()),
        ).joinToString(".")
    }

    private val asMetadata =
        """{"token_endpoint":"$tokenEndpoint","challenge_endpoint":"https://as.test/challenge"}"""

    private class Recorded(
        val url: String,
        val dpop: String?,
        val attestationPoP: String?,
        val body: String,
    )

    /**
     * A stand-in issuer. [deferredResponses] are served in order, so a test can express "refuses once
     * with a nonce, then succeeds" — the live issuer's actual behaviour.
     */
    private fun collectorOver(
        deferredResponses: List<Pair<HttpStatusCode, String>>,
        refreshStatus: HttpStatusCode = HttpStatusCode.OK,
        metadataJwt: String = issuerMetadataJwt(),
        recorded: MutableList<Recorded> = mutableListOf(),
    ): Pair<IosDeferredCredentialCollector, MutableList<Recorded>> {
        var deferredCalls = 0
        val engine = MockEngine { request ->
            val url = request.url.toString()
            recorded += Recorded(
                url = url,
                dpop = request.headers["DPoP"],
                attestationPoP = request.headers["OAuth-Client-Attestation-PoP"],
                body = request.body.toByteArray().decodeToString(),
            )
            when {
                url.endsWith(".well-known/openid-credential-issuer") ->
                    respond(metadataJwt, HttpStatusCode.OK)

                url.endsWith(".well-known/openid-configuration") ->
                    respond(asMetadata, HttpStatusCode.OK, jsonHeaders)

                url.endsWith("/wallet-instance-attestation/jwk") ->
                    respond("""{"walletInstanceAttestation":"wia.jwt.value"}""", HttpStatusCode.OK, jsonHeaders)

                url.endsWith("/challenge") ->
                    respond("""{"attestation_challenge":"challenge-value"}""", HttpStatusCode.OK, jsonHeaders)

                url == tokenEndpoint -> if (refreshStatus == HttpStatusCode.OK) {
                    respond("""{"access_token":"the-access-token","expires_in":300}""", HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond("""{"error":"invalid_grant"}""", refreshStatus, jsonHeaders)
                }

                url == deferredEndpoint -> {
                    val (status, body) = deferredResponses[deferredCalls.coerceAtMost(deferredResponses.size - 1)]
                    deferredCalls++
                    // The nonce only ever arrives on the refusal, exactly as the live issuer does it.
                    val headers = if (status == HttpStatusCode.Unauthorized) {
                        headersOf("DPoP-Nonce", listOf("nonce-from-issuer"))
                    } else {
                        jsonHeaders
                    }
                    respond(body, status, headers)
                }

                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val collector = IosDeferredCredentialCollector(
            httpClient = HttpClient(engine),
            walletProviderBaseUrl = "https://wallet-provider.test",
            clientId = "eudiw-abca",
        )
        return collector to recorded
    }

    private suspend fun key(alias: String): AsymmetricKey {
        val secureArea = SoftwareSecureArea.create(EphemeralStorage())
        secureArea.createKey(alias, SoftwareCreateKeySettings.Builder().setAlgorithm(Algorithm.ESP256).build())
        return AsymmetricKey.anonymous(secureArea, alias)
    }

    private suspend fun IosDeferredCredentialCollector.run(): DeferredCollection = collect(
        issuerUrl = issuerUrl,
        transactionId = "txn-abc-123",
        refreshToken = "the-refresh-token",
        dpopKey = key("dpop"),
        attestationKey = key("attestation"),
    )

    private fun String.jwtPayload() = json
        .parseToJsonElement(
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(split(".")[1]).decodeToString()
        ).jsonObject

    @Test
    fun a_deferred_credential_is_collected_after_the_nonce_round_trip() = runTest {
        val (collector, recorded) = collectorOver(
            listOf(
                HttpStatusCode.Unauthorized to """{"error":"use_dpop_nonce"}""",
                HttpStatusCode.OK to """{"credentials":[{"credential":"the-credential"}]}""",
            )
        )

        val result = collector.run()

        assertIs<DeferredCollection.Issued>(result)
        assertEquals(listOf("the-credential"), result.credentials)

        // The retry is the point: two calls, and only the second carries the nonce the issuer handed
        // out. A client that gave up on the first 401 would never collect anything from this issuer.
        val deferredCalls = recorded.filter { it.url == deferredEndpoint }
        assertEquals(2, deferredCalls.size)
        assertNull(deferredCalls[0].dpop!!.jwtPayload()["nonce"])
        assertEquals("nonce-from-issuer", deferredCalls[1].dpop!!.jwtPayload()["nonce"]?.jsonPrimitive?.content)
    }

    @Test
    fun the_proof_carries_ath_bound_to_the_access_token() = runTest {
        val (collector, recorded) = collectorOver(
            listOf(HttpStatusCode.OK to """{"credential":"the-credential"}""")
        )

        collector.run()

        // `ath` is mandatory at this endpoint — measured, and absent from the original plan. It must be
        // the hash of the token actually being presented, so it is compared against the real thing.
        val proof = recorded.first { it.url == deferredEndpoint }.dpop!!.jwtPayload()
        val ath = proof["ath"]?.jsonPrimitive?.content
        assertNotNull(ath)
        assertEquals(athOf("the-access-token"), ath)
        assertEquals("POST", proof["htm"]?.jsonPrimitive?.content)
        assertEquals(deferredEndpoint, proof["htu"]?.jsonPrimitive?.content)
    }

    @Test
    fun the_client_attestation_pop_uses_challenge_not_nonce() = runTest {
        val (collector, recorded) = collectorOver(
            listOf(HttpStatusCode.OK to """{"credential":"the-credential"}""")
        )

        collector.run()

        // With `nonce` the real authorization server answers a flat 401 that names nothing, so this is
        // pinned rather than left to be rediscovered.
        val pop = recorded.first { it.url == tokenEndpoint }.attestationPoP!!.jwtPayload()
        assertEquals("challenge-value", pop["challenge"]?.jsonPrimitive?.content)
        assertNull(pop["nonce"])
    }

    @Test
    fun the_refresh_grant_is_what_is_sent_to_the_token_endpoint() = runTest {
        val (collector, recorded) = collectorOver(
            listOf(HttpStatusCode.OK to """{"credential":"the-credential"}""")
        )

        collector.run()

        val body = recorded.first { it.url == tokenEndpoint }.body
        assertTrue(body.contains("grant_type=refresh_token"), body)
        assertTrue(body.contains("refresh_token=the-refresh-token"), body)
    }

    @Test
    fun an_issuer_still_working_on_it_reports_pending_with_its_own_interval() = runTest {
        val (collector, _) = collectorOver(
            listOf(HttpStatusCode.BadRequest to """{"error":"issuance_pending","interval":42}""")
        )

        val result = collector.run()

        assertIs<DeferredCollection.StillPending>(result)
        assertEquals(42, result.retryAfterSeconds)
    }

    @Test
    fun a_spent_transaction_id_is_abandoned_rather_than_retried() = runTest {
        val (collector, _) = collectorOver(
            listOf(HttpStatusCode.BadRequest to """{"error":"invalid_transaction_id"}""")
        )

        // The difference that matters to the caller: pending means poll again, this means stop.
        assertIs<DeferredCollection.Abandoned>(collector.run())
    }

    @Test
    fun an_expired_authorization_is_reported_as_such_and_the_issuer_is_never_asked() = runTest {
        val (collector, recorded) = collectorOver(
            deferredResponses = listOf(HttpStatusCode.OK to """{"credential":"never-reached"}"""),
            refreshStatus = HttpStatusCode.BadRequest,
        )

        assertIs<DeferredCollection.AuthorizationExpired>(collector.run())
        assertTrue(recorded.none { it.url == deferredEndpoint })
    }

    @Test
    fun an_issuer_without_the_endpoint_is_reported_rather_than_guessed_at() = runTest {
        val (collector, _) = collectorOver(
            deferredResponses = listOf(HttpStatusCode.OK to "{}"),
            metadataJwt = issuerMetadataJwt(withDeferred = false),
        )

        assertIs<DeferredCollection.Unsupported>(collector.run())
    }

    @Test
    fun success_with_no_credential_is_a_failure_not_an_empty_success() = runTest {
        val (collector, _) = collectorOver(
            listOf(HttpStatusCode.OK to """{"credentials":[]}""")
        )

        // Storing nothing here would leave the document parked for ever while reporting success.
        assertIs<DeferredCollection.Failed>(collector.run())
    }

    private suspend fun athOf(token: String): String =
        Crypto.digest(Algorithm.SHA256, token.encodeToByteArray()).toBase64Url()

    @Test
    fun a_200_carrying_a_transaction_id_is_still_pending_not_success() = runTest {
        // 🚨 What the live dev issuer actually sends while it is still working, measured 2026-09-16:
        // HTTP **200** with a `transaction_id` and an `interval` — NOT the `400 issuance_pending` the
        // drafts describe. Reading it as success parked the document for ever while reporting that it
        // had been collected, which is exactly what the first live run did.
        val (collector, _) = collectorOver(
            listOf(
                HttpStatusCode.OK to
                        """{"transaction_id":"013f71f1-65c4-419c-bb0f-e6954666ccd8","interval":48}"""
            )
        )

        val result = collector.run()

        assertIs<DeferredCollection.StillPending>(result)
        assertEquals(48, result.retryAfterSeconds)
        assertEquals("013f71f1-65c4-419c-bb0f-e6954666ccd8", result.transactionId)
    }

    private companion object {
        val jsonHeaders = headersOf("Content-Type", listOf("application/json"))
    }
}
