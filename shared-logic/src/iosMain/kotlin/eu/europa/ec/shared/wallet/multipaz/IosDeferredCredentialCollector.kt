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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import org.multipaz.webtoken.buildJwt
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * Collects a credential the issuer promised to mint later — OpenID4VCI's deferred flow, which multipaz
 * cannot do at all.
 *
 * multipaz never parses `deferred_credential_endpoint` (zero occurrences in its sources, still at
 * 0.101.0) and treats the issuer's `202 Accepted` as an error, so this walks the protocol itself. That
 * is possible only because the pieces it needs are ours already: the **DPoP key lives in a
 * [org.multipaz.securearea.SecureArea] this wallet supplies**, its alias is in the `authorizationData`
 * CBOR that [withoutStoredWalletAttestation] already decodes, and the refresh token sits beside it.
 *
 * ## What the live issuer demands, all of it measured on 2026-09-16 rather than read off the spec
 *
 * - **The DPoP proof must carry `ath`** — `b64url(SHA-256(access_token))`. Omitting it is a flat 401.
 * - **The issuer requires a DPoP nonce, and rotates it.** The first call to the deferred endpoint is
 *   *always* refused with `use_dpop_nonce` and a `DPoP-Nonce` header; the retry with it succeeds. ⛔
 *   This is the opposite of the authorization server, which takes a proof with no nonce at all — so
 *   [OpenID4VciHttpClient]'s note that "this authorization server does not use DPoP nonces" is right
 *   about the AS and must not be carried across to the issuer. Two round trips are the normal path.
 * - **The signature must be raw `r||s`, not DER.** Handled by minting through multipaz's own
 *   [buildJwt], whose `EcSignature.toCoseEncoded()` is exactly that; a DER signature was measured
 *   being rejected.
 * - **The client-attestation PoP claim is `challenge`, not `nonce`** — multipaz's own spelling. With
 *   `nonce` the token endpoint answers a flat 401 *"Authentication failed."* that names nothing.
 *
 * ## Why it refreshes rather than reusing a token
 *
 * The access token lives **300 seconds** and the refresh token **1800**. A deferred credential is by
 * definition collected later, so a captured token is almost always dead by then; the refresh token is
 * the only thing worth persisting, and it is what multipaz already stores. Past 30 minutes neither
 * works and the document needs authorizing again — which is the honest limit of this flow, not a
 * defect in it.
 *
 * ⛔ Deliberately **not** routed through multipaz's `ProvisioningModel`: re-entering that would start a
 * *new* issuance rather than collect the parked one.
 */
internal class IosDeferredCredentialCollector(
    private val httpClient: HttpClient,
    private val walletProviderBaseUrl: String,
    private val clientId: String,
) {

    /**
     * Asks the issuer for the credential behind [transactionId].
     *
     * @param dpopKey the key the refresh token is bound to — `authorizationData.dpopKeyAlias` opened on
     *   the secure area named by `secureAreaId`. Nothing else will do: the token is bound to its
     *   thumbprint, and a proof from any other key is refused with *"Invalid access token binding"*.
     * @param attestationKey a key for client authentication. May be freshly created and thrown away:
     *   the wallet attestation only says *which client this is*, and the dev wallet provider mints one
     *   for any JWK presented.
     */
    suspend fun collect(
        issuerUrl: String,
        transactionId: String,
        refreshToken: String,
        dpopKey: AsymmetricKey,
        attestationKey: AsymmetricKey,
    ): DeferredCollection = runCatching {
        val issuer = issuerMetadata(issuerUrl)
        val deferredEndpoint = issuer["deferred_credential_endpoint"]?.jsonPrimitive?.contentOrNull
            ?: return DeferredCollection.Unsupported(
                "the issuer no longer advertises a deferred_credential_endpoint"
            )
        val authorizationServer = issuer["authorization_servers"]?.jsonArray
            ?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: issuerUrl

        val accessToken = refreshAccessToken(authorizationServer, refreshToken, dpopKey, attestationKey)
            ?: return DeferredCollection.AuthorizationExpired

        requestCredential(deferredEndpoint, transactionId, accessToken, dpopKey)
    }.getOrElse { throwable ->
        Logger.w(TAG, "deferred collection failed: ${throwable::class.simpleName}: ${throwable.message}")
        DeferredCollection.Failed(throwable.message ?: "the issuer could not be reached")
    }

    /** Metadata may be a signed JWT — the same shape [OpenID4VciHttpClient] unwraps for multipaz. */
    private suspend fun issuerMetadata(issuerUrl: String): JsonObject {
        val body = httpClient.get("${issuerUrl.trimEnd('/')}/$ISSUER_METADATA_PATH").bodyAsText()
        return body.asJsonObjectOrJwtPayload()
    }

    /**
     * Trades the stored refresh token for an access token bound to [dpopKey].
     *
     * Returns null when the authorization is simply too old — the refusal that means "ask the user to
     * add this document again" rather than "retry later".
     */
    private suspend fun refreshAccessToken(
        authorizationServer: String,
        refreshToken: String,
        dpopKey: AsymmetricKey,
        attestationKey: AsymmetricKey,
    ): String? {
        val metadata = httpClient
            .get("${authorizationServer.trimEnd('/')}/$AS_METADATA_PATH")
            .bodyAsText().asJsonObjectOrJwtPayload()
        val tokenEndpoint = metadata["token_endpoint"]!!.jsonPrimitive.content
        val challengeEndpoint = metadata["challenge_endpoint"]?.jsonPrimitive?.contentOrNull

        val form = listOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId,
        ).formUrlEncoded()

        suspend fun attempt(nonce: String?): HttpResponse = httpClient.post(tokenEndpoint) {
            attestationHeaders(authorizationServer, challengeEndpoint, attestationKey)
                .forEach { (name, value) -> header(name, value) }
            header(DPOP_HEADER, dpopKey.dpopProof(tokenEndpoint, nonce = nonce))
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(form)
        }

        var response = attempt(nonce = null)
        // Spec'd retry. Measured not to happen at this endpoint, but a server may start any time and
        // the cost of being ready is three lines.
        if (!response.status.isSuccess()) {
            val nonce = response.headers[DPOP_NONCE_HEADER]
            if (nonce != null) response = attempt(nonce)
        }
        if (!response.status.isSuccess()) {
            Logger.w(TAG, "refresh refused: ${response.status}")
            return null
        }
        return response.bodyAsText().asJsonObject()["access_token"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * The deferred request itself, including the nonce round trip the issuer always demands.
     */
    private suspend fun requestCredential(
        deferredEndpoint: String,
        transactionId: String,
        accessToken: String,
        dpopKey: AsymmetricKey,
    ): DeferredCollection {
        val ath = Crypto.digest(Algorithm.SHA256, accessToken.encodeToByteArray()).toBase64Url()
        val body = buildJsonObject { put("transaction_id", transactionId) }.toString()

        suspend fun attempt(nonce: String?): HttpResponse = httpClient.post(deferredEndpoint) {
            header(HttpHeaders.Authorization, "$DPOP_SCHEME $accessToken")
            header(DPOP_HEADER, dpopKey.dpopProof(deferredEndpoint, ath = ath, nonce = nonce))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        var response = attempt(nonce = null)
        if (!response.status.isSuccess()) {
            val nonce = response.headers[DPOP_NONCE_HEADER]
            if (nonce != null) {
                Logger.i(TAG, "the issuer asked for a DPoP nonce, retrying with it")
                response = attempt(nonce)
            }
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) return text.asCredentials()

        val error = runCatching { text.asJsonObject()["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return when (error) {
            // The issuer is still working on it; `interval` is its own advice on when to ask again.
            ISSUANCE_PENDING -> DeferredCollection.StillPending(
                retryAfterSeconds = runCatching {
                    text.asJsonObject()["interval"]?.jsonPrimitive?.intOrNull
                }.getOrNull()
            )
            // The handle is spent or was never real: polling again cannot help.
            INVALID_TRANSACTION_ID -> DeferredCollection.Abandoned
            else -> DeferredCollection.Failed("${response.status}${error?.let { ": $it" } ?: ""}")
        }
    }

    /**
     * Client authentication, rebuilt per call because Keycloak rejects a reused challenge — the same
     * defect [OpenID4VciHttpClient] works around for multipaz's own requests.
     */
    private suspend fun attestationHeaders(
        authorizationServer: String,
        challengeEndpoint: String?,
        attestationKey: AsymmetricKey,
    ): Map<String, String> {
        val attestation = httpClient.post("$walletProviderBaseUrl$WALLET_INSTANCE_ATTESTATION_PATH") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("jwk", attestationKey.publicKey.toJwk()) }.toString())
        }.bodyAsText().asJsonObject()["walletInstanceAttestation"]!!.jsonPrimitive.content

        val challenge = challengeEndpoint?.let {
            runCatching {
                httpClient.post(it) {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("")
                }.bodyAsText().asJsonObject()["attestation_challenge"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }

        val pop = buildJwt(
            type = CLIENT_ATTESTATION_POP_TYPE,
            key = attestationKey,
            expiresIn = 2.minutes,
        ) {
            put("iss", clientId)
            put("aud", authorizationServer)
            put("jti", Random.nextBytes(JTI_BYTES).toBase64Url())
            // `challenge`, NOT `nonce`. multipaz's own spelling, and the one Keycloak accepts.
            challenge?.let { put("challenge", it) }
        }
        return mapOf(
            CLIENT_ATTESTATION_HEADER to attestation,
            CLIENT_ATTESTATION_POP_HEADER to pop,
        )
    }

    /**
     * A DPoP proof of the shape multipaz mints in its own `OpenID4VCIUtil.generateDPoP`, built on the
     * same public [buildJwt] so the signature encoding is multipaz's rather than a copy of it.
     */
    private suspend fun AsymmetricKey.dpopProof(
        url: String,
        ath: String? = null,
        nonce: String? = null,
    ): String = buildJwt(
        type = DPOP_JWT_TYPE,
        key = this,
        header = { put("jwk", publicKey.toJwk()) },
    ) {
        put("htm", "POST")
        put("htu", url)
        put("jti", Random.nextBytes(JTI_BYTES).toBase64Url())
        nonce?.let { put("nonce", it) }
        ath?.let { put("ath", it) }
    }

    private companion object {
        const val TAG = "IosDeferredCollector"
        const val ISSUER_METADATA_PATH = ".well-known/openid-credential-issuer"
        const val AS_METADATA_PATH = ".well-known/openid-configuration"
        const val WALLET_INSTANCE_ATTESTATION_PATH = "/wallet-instance-attestation/jwk"
        const val CLIENT_ATTESTATION_HEADER = "OAuth-Client-Attestation"
        const val CLIENT_ATTESTATION_POP_HEADER = "OAuth-Client-Attestation-PoP"
        const val CLIENT_ATTESTATION_POP_TYPE = "oauth-client-attestation-pop+jwt"
        const val DPOP_HEADER = "DPoP"
        const val DPOP_NONCE_HEADER = "DPoP-Nonce"
        const val DPOP_SCHEME = "DPoP"
        const val DPOP_JWT_TYPE = "dpop+jwt"
        const val ISSUANCE_PENDING = "issuance_pending"
        const val INVALID_TRANSACTION_ID = "invalid_transaction_id"
        const val JTI_BYTES = 15
    }
}

/**
 * What came back from asking the issuer for a deferred credential.
 *
 * Public because `:shared-ui` branches on it: the documents screen treats "ask again later" and "this
 * will never arrive" differently, and collapsing them into a boolean would lose exactly the
 * distinction that decides whether a spinner keeps spinning.
 */
sealed interface DeferredCollection {

    /** The issuer minted it. [credentials] are the raw values, in the issuer's own encoding. */
    data class Issued(val credentials: List<String>) : DeferredCollection

    /**
     * Not ready yet, and the issuer would like to be asked again in [retryAfterSeconds].
     *
     * [transactionId] is whatever handle the issuer echoed back. ⚠️ **Measured 2026-09-16: this issuer
     * returns the SAME handle it was asked with** — an earlier note here claimed it rotated, which was
     * an inference from comparing the body against a handle that had never been printed, and the live
     * run disproved it. The caller still stores a handle that differs, because the spec permits one and
     * the cost of being ready is a comparison.
     */
    data class StillPending(
        val retryAfterSeconds: Int?,
        val transactionId: String? = null,
    ) : DeferredCollection

    /** The handle is spent or unknown: polling again cannot help, so stop. */
    data object Abandoned : DeferredCollection

    /** The refresh token has expired — the document has to be added again. */
    data object AuthorizationExpired : DeferredCollection

    /** The issuer stopped advertising the endpoint; nothing to do but say so. */
    data class Unsupported(val reason: String) : DeferredCollection

    /** Anything transient: worth another attempt later. */
    data class Failed(val reason: String) : DeferredCollection
}

/**
 * Parses a metadata document that may be **either** JSON or a signed JWT.
 *
 * `dev.issuer-backend.eudiw.dev` serves its issuer metadata as `application/jwt` with an `x5c` header,
 * which OpenID4VCI permits and multipaz chokes on — the same asymmetry [OpenID4VciHttpClient] unwraps
 * on multipaz's behalf. Here the body is fetched directly, so the unwrapping has to happen here too.
 *
 * ⚠️ The signature is **not** verified, deliberately: this reads only endpoint URLs, and every one of
 * them is then contacted over TLS with a DPoP-bound token. Trust in the *issuer* is established by
 * [MultipazRevocationChecker] and the trust wiring, not here.
 */
internal fun String.asJsonObjectOrJwtPayload(): JsonObject {
    val trimmed = trim()
    if (trimmed.startsWith("{")) return trimmed.asJsonObject()
    // Three dot-separated base64url segments is a JWS; take the payload.
    val parts = trimmed.split('.')
    require(parts.size == 3) { "metadata is neither JSON nor a JWT" }
    return parts[1].decodeBase64UrlToString().asJsonObject()
}

internal fun String.asJsonObject(): JsonObject =
    LenientDeferredJson.parseToJsonElement(this).jsonObject

@OptIn(ExperimentalEncodingApi::class)
private fun String.decodeBase64UrlToString(): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(this).decodeToString()

/**
 * Reads the credentials out of a deferred response.
 *
 * Both shapes the drafts have used are accepted: a bare `credential`, and the `credentials` array that
 * replaced it. An issuer that answers 200 with neither is a failure rather than an empty success —
 * silently storing nothing would park the document for ever.
 */
private fun String.asCredentials(): DeferredCollection {
    val body = runCatching { asJsonObject() }.getOrElse {
        return DeferredCollection.Failed("the issuer's answer was not JSON")
    }
    val fromArray = body["credentials"]?.jsonArray?.mapNotNull { entry ->
        // Each entry is an object carrying `credential`; a bare string is tolerated.
        runCatching { entry.jsonObject["credential"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: runCatching { entry.jsonPrimitive.contentOrNull }.getOrNull()
    }.orEmpty()
    val single = body["credential"]?.jsonPrimitive?.contentOrNull
    val credentials = (fromArray + listOfNotNull(single)).filter { it.isNotBlank() }
    if (credentials.isEmpty()) {
        // 🚨 Measured against the live dev issuer on 2026-09-16, and NOT what the drafts describe:
        // "not ready yet" arrives as **HTTP 200** carrying a `transaction_id` and an `interval`, not as
        // a 400 with `issuance_pending`. Reading that as success would have parked the document
        // forever while reporting that it had been collected.
        val rotated = body["transaction_id"]?.jsonPrimitive?.contentOrNull
        if (!rotated.isNullOrBlank()) {
            return DeferredCollection.StillPending(
                retryAfterSeconds = body["interval"]?.jsonPrimitive?.intOrNull,
                transactionId = rotated,
            )
        }
    }
    return if (credentials.isEmpty()) {
        // The body is logged because the shape an issuer actually sends is the thing most likely to
        // differ from the drafts, and without it this failure says nothing about what to fix.
        // The body goes into the reason, not just the log: multipaz's Logger writes to the unified
        // log and a file sink, neither of which a `simctl --console-pty` run shows, and the shape an
        // issuer actually sends is the thing most likely to differ from the drafts.
        Logger.w(COLLECTOR_TAG, "200 with no credential in it; body was ${take(400)}")
        DeferredCollection.Failed(
            "the issuer reported success but sent no credential; body was ${take(200)}"
        )
    } else {
        DeferredCollection.Issued(credentials)
    }
}

private fun List<Pair<String, String>>.formUrlEncoded(): String =
    joinToString("&") { (name, value) -> "$name=${value.formUrlEncode()}" }

/** Percent-encoding for the handful of values posted here; only unreserved characters survive. */
private fun String.formUrlEncode(): String = buildString {
    this@formUrlEncode.encodeToByteArray().forEach { byte ->
        val char = byte.toInt().toChar()
        if (char.isLetterOrDigit() || char in "-._~") {
            append(char)
        } else {
            append('%').append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }
}

private const val COLLECTOR_TAG = "IosDeferredCollector"

/** The issuer publishes far more than this reads, and a new member must not fail a collection. */
private val LenientDeferredJson = Json { ignoreUnknownKeys = true }
