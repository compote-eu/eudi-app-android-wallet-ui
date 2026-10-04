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

import io.ktor.http.ContentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.bytestring.ByteString
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.JsonWebEncryption
import org.multipaz.util.toBase64Url
import kotlin.random.Random

/**
 * OpenID4VCI 1.0 credential request and response encryption, as one issuer's metadata offers it
 * (`credential_request_encryption` and `credential_response_encryption`).
 *
 * multipaz implements neither, in 0.99.0 or 0.101.0: its credential request is always plain JSON, so an
 * issuer that sets `encryption_required` refuses it with `400 invalid_credential_request: "Credential
 * request encryption is required"`. Measured against Plaut's dev PID issuer, 2026-09-28.
 *
 * ### The same rules as Android
 *
 * Android issues through wallet-core 0.30.2 and eudi-lib-jvm-openid4vci-kt 0.13.1, read in their sources:
 * - the request is encrypted **whenever the issuer takes encrypted requests**, and must be when it
 *   requires them (`IssuanceEncryption.requestEncryptionSpec`);
 * - an encrypted response is asked for **whenever the issuer can send one** — wallet-core's default policy
 *   is `CredentialResponseEncryptionPolicy.REQUIRED` (`OpenId4VciManager.Config`).
 *
 * So the EU issuer, which offers both without requiring them, is encrypted too, as it is on Android.
 * ⚠️ One difference, deliberately: wallet-core also *refuses* an issuer that offers no response encryption
 * at all. This does not — such an issuer is served in plain JSON, as before.
 *
 * Only ECDH-ES (direct key agreement) with AES-GCM, because that is what multipaz's [JsonWebEncryption]
 * implements, and what both the EU and Plaut issuers offer. An issuer that *requires* encryption and
 * offers nothing usable is refused here with a message that says so, rather than sent plaintext for the
 * issuer to refuse less legibly.
 */
internal class CredentialEncryption private constructor(
    private val requestKey: CredentialExchange.RequestKey?,
    private val responseMethod: Algorithm?,
) {
    /** Whether anything here changes the exchange at all. */
    val isActive: Boolean get() = requestKey != null || responseMethod != null

    /**
     * One request and its answer.
     *
     * The response key is made here and lives only as long as the exchange — a DPoP retry starts a new
     * one, so each request on the wire names its own key.
     */
    suspend fun begin(): CredentialExchange = CredentialExchange(
        requestKey = requestKey,
        response = responseMethod?.let { it to AsymmetricKey.ephemeral() },
    )

    /** For diagnostics only: which halves are encrypted and with what. */
    override fun toString(): String = buildString {
        append("request=")
        append(requestKey?.let { "${it.method.joseAlgorithmIdentifier}/kid=${it.kid}" } ?: "plain")
        append(" response=")
        append(responseMethod?.joseAlgorithmIdentifier ?: "plain")
    }

    companion object {
        /** No encryption offered, or none usable and none required. */
        val None = CredentialEncryption(requestKey = null, responseMethod = null)

        /**
         * What [metadata] (the credential issuer's, already unwrapped if it was signed) offers.
         *
         * @throws IllegalStateException when the issuer requires encryption this wallet cannot do.
         */
        fun fromIssuerMetadata(metadata: JsonObject): CredentialEncryption {
            val requestKey = requestKeyOf(metadata["credential_request_encryption"] as? JsonObject)
            val responseMethod = responseMethodOf(metadata["credential_response_encryption"] as? JsonObject)
            return if (requestKey == null && responseMethod == null) None
            else CredentialEncryption(requestKey, responseMethod)
        }

        private fun requestKeyOf(offer: JsonObject?): CredentialExchange.RequestKey? {
            offer ?: return null
            val method = offer.firstSupportedMethod()
            val key = offer["jwks"]?.jsonObject?.get("keys")?.jsonArray.orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstNotNullOfOrNull { it.usableRequestKey() }
            if (method != null && key != null) return CredentialExchange.RequestKey(key.first, key.second, method)
            check(!offer.isRequired()) {
                "The issuer requires encrypted credential requests, but offers no key this wallet can " +
                    "encrypt to (an EC key with a kid, for ECDH-ES) or no content encryption it supports " +
                    "(A128GCM, A192GCM, A256GCM)."
            }
            return null
        }

        private fun responseMethodOf(offer: JsonObject?): Algorithm? {
            offer ?: return null
            val method = offer.firstSupportedMethod()
            val takesEcdhEs = offer["alg_values_supported"]?.jsonArray.orEmpty()
                .any { (it as? JsonPrimitive)?.contentOrNull == ECDH_ES }
            if (method != null && takesEcdhEs) return method
            check(!offer.isRequired()) {
                "The issuer requires encrypted credential responses, but supports neither ECDH-ES nor a " +
                    "content encryption this wallet can decrypt (A128GCM, A192GCM, A256GCM)."
            }
            return null
        }

        /** The issuer's own order decides, as in openid4vci-kt: its list, narrowed to what multipaz can do. */
        private fun JsonObject.firstSupportedMethod(): Algorithm? =
            this["enc_values_supported"]?.jsonArray.orEmpty()
                .firstNotNullOfOrNull { SUPPORTED_METHODS[(it as? JsonPrimitive)?.contentOrNull] }

        private fun JsonObject.isRequired(): Boolean =
            this["encryption_required"]?.jsonPrimitive?.booleanOrNull == true

        /**
         * An EC key usable for ECDH-ES, with the `kid` the JWE header must name — the same conditions
         * openid4vci-kt enforces (`CredentialRequestEncryptionKeysMustHaveKeyId`, `…EncryptionUsage`).
         * Anything else in it (`x5c`, `nbf`, `exp`, a thumbprint) is ignored.
         */
        private fun JsonObject.usableRequestKey(): Pair<EcPublicKey, String>? {
            fun string(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull
            val kid = string("kid") ?: return null
            if (string("kty") != "EC" || string("crv") !in EC_CURVES) return null
            if ((string("use") ?: "enc") != "enc") return null
            if ((string("alg") ?: ECDH_ES) != ECDH_ES) return null
            val key = runCatching { EcPublicKey.fromJwk(this) }.getOrNull() ?: return null
            return key to kid
        }

        private const val ECDH_ES = "ECDH-ES"
        private val EC_CURVES = setOf("P-256", "P-384", "P-521")
        private val SUPPORTED_METHODS = mapOf(
            "A128GCM" to Algorithm.A128GCM,
            "A192GCM" to Algorithm.A192GCM,
            "A256GCM" to Algorithm.A256GCM,
        )
    }
}

/** One encrypted request and the answer to it; see [CredentialEncryption.begin]. */
internal class CredentialExchange internal constructor(
    private val requestKey: RequestKey?,
    private val response: Pair<Algorithm, AsymmetricKey>?,
) {
    /** The issuer's key the request is encrypted to, and the content encryption both sides agreed on. */
    internal class RequestKey(val publicKey: EcPublicKey, val kid: String, val method: Algorithm)

    /** Whether a successful answer to this exchange will be a JWE. */
    val decryptsResponse: Boolean get() = response != null

    /** A request body and the content type it has to be sent with. */
    data class Body(val text: String, val contentType: ContentType)

    /**
     * [payload] as it has to go on the wire: with `credential_response_encryption` added when an encrypted
     * answer is wanted, then as a compact JWE when the issuer takes encrypted requests.
     *
     * The response key travels inside the encrypted request, which is the point of encrypting both.
     */
    suspend fun encode(payload: JsonObject): Body {
        val withResponseKey = response?.let { (method, key) ->
            JsonObject(
                payload + (RESPONSE_ENCRYPTION to buildJsonObject {
                    put(
                        "jwk",
                        key.publicKey.toJwk(
                            buildJsonObject {
                                put("kid", Random.nextBytes(KID_BYTES).toBase64Url())
                                put("use", "enc")
                                put("alg", "ECDH-ES")
                            }
                        )
                    )
                    put("enc", method.joseAlgorithmIdentifier!!)
                })
            )
        } ?: payload

        val key = requestKey ?: return Body(withResponseKey.toString(), ContentType.Application.Json)
        val jwe = JsonWebEncryption.encrypt(
            claimsSet = withResponseKey,
            recipientPublicKey = key.publicKey,
            encAlg = key.method,
            // 🚨 EMPTY, never null. multipaz's `encrypt` (0.99.0 and 0.101.0) leaves a null apu/apv out of the
            // Concat KDF entirely, while RFC 7518 §4.6.2 — and multipaz's own `decrypt` — encode an absent one
            // as a four-byte zero length. The two derive different keys, so a null here produces a JWE that no
            // conformant issuer can open; caught by this module's round-trip test. An empty value is
            // encoded the RFC's way on both sides.
            apu = ByteString(),
            apv = ByteString(),
            kid = key.kid,
        )
        return Body(jwe, APPLICATION_JWT)
    }

    /**
     * A **successful** answer's JSON text, decrypted when this exchange asked for an encrypted one.
     *
     * Errors are never encrypted — the issuer cannot know an error was not provoked by a bad key — so the
     * caller hands only 2xx bodies here.
     *
     * @throws IllegalStateException when an encrypted answer was asked for and plain JSON came back. The
     *   issuer was sent a key and chose not to use it; Android's library refuses the same answer.
     */
    suspend fun decode(body: String): String {
        val (_, key) = response ?: return body
        val compact = body.trim()
        check(compact.split('.').size == JWE_PARTS) {
            "The issuer answered in plain JSON, although the wallet asked for an encrypted credential response."
        }
        return JsonWebEncryption.decrypt(compact, key).toString()
    }

    private companion object {
        const val RESPONSE_ENCRYPTION = "credential_response_encryption"
        const val KID_BYTES = 12
        const val JWE_PARTS = 5
        val APPLICATION_JWT = ContentType("application", "jwt")
    }
}

/**
 * Every issuer's [CredentialEncryption] this process has read, keyed by the endpoints it governs — the
 * credential endpoint and the deferred credential endpoint.
 *
 * Process-wide on purpose, mirroring multipaz: `IssuerConfiguration.get` caches issuer metadata for the
 * whole process, so the client that finally posts a credential request may never have read the metadata
 * itself. In [IosCredentialIssuer]'s batch path the session reads it once and every document posts through
 * a client of its own. Whatever did read it went through [OpenID4VciCompatibilityEngine], so this is
 * exactly as fresh as multipaz's cache, and empty again on the next launch, as that cache is.
 *
 * An issuer that *requires* encryption this wallet cannot do is recorded as that failure, so the request
 * that would have gone out in plain JSON fails here instead, saying why.
 */
internal object CredentialEncryptionRegistry {
    private val lock = Mutex()
    private val byEndpoint = mutableMapOf<String, Result<CredentialEncryption>>()

    /** Records what [metadata] offers, and returns it; nothing, if it names no credential endpoint. */
    suspend fun record(metadata: JsonObject): Result<CredentialEncryption>? {
        val endpoints = listOf("credential_endpoint", "deferred_credential_endpoint")
            .mapNotNull { (metadata[it] as? JsonPrimitive)?.contentOrNull }
        if (endpoints.isEmpty()) return null
        val offer = runCatching { CredentialEncryption.fromIssuerMetadata(metadata) }
        lock.withLock { endpoints.forEach { byEndpoint[it] = offer } }
        return offer
    }

    /**
     * What the issuer behind [url] offers, or null if no metadata naming it has been read.
     *
     * @throws IllegalStateException when that issuer requires encryption this wallet cannot do.
     */
    suspend fun forEndpoint(url: String): CredentialEncryption? =
        lock.withLock { byEndpoint[url] }?.getOrThrow()
}
