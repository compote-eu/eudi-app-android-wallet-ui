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
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.cookies.HttpCookies
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureEnclaveCreateKeySettings
import org.multipaz.securearea.SecureEnclaveSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toBase64Url
import org.multipaz.webtoken.buildJwt
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * Does a **real EUDI issuer accept a DPoP proof signed by a key in the Secure Enclave**?
 *
 * This is the last unproven link in iOS deferred issuance (multipaz #1948), and the only one that
 * cannot be answered outside a running app. Everything around it was measured on 2026-09-16 with
 * `openssl`: both dev issuers advertise `deferred_credential_endpoint`, the authorization server binds
 * the access token to whatever DPoP key is presented, and the issuer's deferred endpoint lets a
 * well-formed proof through to its business logic while rejecting nine different broken ones.
 *
 * What that run could not establish is the part specific to this wallet: production hands multipaz
 * `authorizationSecureArea = store.keySecureArea`, which is a **[org.multipaz.securearea.SecureEnclaveSecureArea]**.
 * A Secure Enclave key signs through `SwiftBridge.secureEnclaveEcSign` — a *different* function from
 * the software path's `SwiftBridge.ecSign`, though both then split a raw signature in half into
 * `EcSignature(r, s)`. Since the issuer is measured to **reject DER-encoded signatures**, whether that
 * particular bridge really yields raw `r||s` is worth watching rather than reasoning about.
 *
 * ## Why this is a probe and not a test
 *
 * A bare Kotlin/Native test binary **cannot create a Secure Enclave key at all** — `SwiftBridge`
 * returns nothing and multipaz raises *"Error creating EC key - on iOS simulator?"*, because the
 * process is unsigned and has no keychain entitlement. Inside the app the same call works, so the only
 * place this question can be asked is a probe run. Measured 2026-09-16, and it is why
 * `DeferredDPoPLiveTest` does not exist.
 *
 * Nothing is issued and nothing is stored: the keys are created in an ephemeral store, and the only
 * credential-shaped thing asked for is a deliberately invalid `transaction_id`. The *expected* success
 * is therefore the issuer answering `invalid_transaction_id` — which it can only reach after accepting
 * both the proof and the token.
 */
internal class DeferredDPoPProbe(
    private val onResult: (String) -> Unit,
    private val username: String,
    private val password: String,
) {

    private val authorizationServer = "https://dev.authenticate.eudiw.dev/realms/pid-issuer-realm"
    private val parEndpoint = "$authorizationServer/protocol/openid-connect/ext/par/request"
    private val authorizationEndpoint = "$authorizationServer/protocol/openid-connect/auth"
    private val tokenEndpoint = "$authorizationServer/protocol/openid-connect/token"
    private val challengeEndpoint = "$authorizationServer/challenge"
    private val walletProvider =
        "https://dev.wallet-provider.eudiw.dev/wallet-instance-attestation/jwk"
    private val deferredEndpoint = "https://dev.issuer-backend.eudiw.dev/wallet/deferredEndpoint"
    private val clientId = IosIssuerCatalog.CLIENT_ID
    private val redirectUri = IosIssuerCatalog.REDIRECT_URI
    private val scope = "eu.europa.ec.eudi.pid_mso_mdoc"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(secureArea: SecureArea) {
        onResult("--- deferred issuance: a DPoP proof signed in the Secure Enclave ---")

        val dpopKey = runCatching { secureArea.enclaveKey("probe-dpop") }.getOrElse {
            onResult("  could not create a Secure Enclave key: ${it::class.simpleName}: ${it.message}")
            return
        }
        val instanceKey = secureArea.enclaveKey("probe-instance")
        onResult("  created two ${secureArea::class.simpleName} keys")

        HttpClient(Darwin) {
            // The authorization leg is a browser flow: Keycloak bounces it to a login page and back,
            // and the session rides on cookies. Redirects are followed by hand because the LAST hop
            // goes to the wallet's custom scheme, which no HTTP client can follow — that hop IS the
            // authorization code.
            followRedirects = false
            install(HttpCookies)
            expectSuccess = false
        }.use { client ->
            val verifier = Random.nextBytes(32).toBase64Url()
            val requestUri = client.pushAuthorizationRequest(instanceKey, verifier)
            val code = client.logIn(requestUri)
            onResult("  authorization code obtained")

            // --- the authorization server's verdict ------------------------------------------------
            val token = client.exchange(code, verifier, instanceKey, dpopKey)
            val accessToken = token["access_token"]!!.jsonPrimitive.content
            val boundTo = json.parseToJsonElement(accessToken.jwtPayload())
                .jsonObject["cnf"]?.jsonObject?.get("jkt")?.jsonPrimitive?.content
            val ours = dpopKey.publicKey.toJwkThumbprint(Algorithm.SHA256).toByteArray().toBase64Url()
            onResult(
                "  token endpoint accepted the proof and bound the token to our key = ${boundTo == ours}" +
                        " (cnf.jkt=$boundTo)"
            )

            // --- the issuer's verdict -----------------------------------------------------------------
            val ath = Crypto.digest(Algorithm.SHA256, accessToken.encodeToByteArray()).toBase64Url()

            // RFC 9449 §9: this resource server refuses the first attempt and hands out a nonce, so the
            // retry is the normal path rather than an edge case. Measured, not assumed.
            val challenged = client.callDeferred(accessToken, dpopKey.dpopProof(ath = ath))
            val nonce = challenged.headers[DPOP_NONCE]
            onResult(
                "  first call (no nonce) -> ${challenged.status.value} " +
                        "${challenged.headers[HttpHeaders.WWWAuthenticate]?.take(70)}, nonce handed out = ${nonce != null}"
            )
            if (nonce == null) return@use

            val accepted = client.callDeferred(accessToken, dpopKey.dpopProof(ath = ath, nonce = nonce))
            val body = accepted.bodyAsText()
            onResult("  retried with the nonce -> ${accepted.status.value} $body")

            // The control. Without it an acceptance means nothing: the same call with one flipped byte
            // in the signature has to be refused, or the endpoint is not checking signatures at all.
            val forgedNonce = accepted.headers[DPOP_NONCE] ?: nonce
            val forged = client.callDeferred(
                accessToken,
                dpopKey.dpopProof(ath = ath, nonce = forgedNonce).withBrokenSignature(),
            )
            onResult(
                "  CONTROL, one byte of the signature flipped -> ${forged.status.value} " +
                        "${forged.headers[HttpHeaders.WWWAuthenticate]?.take(70)}"
            )

            val proofAccepted = accepted.status.value == 400 && body.contains("invalid_transaction_id")
            val forgeryRejected = forged.status.value == 401
            onResult(
                "  VERDICT: a Secure-Enclave-signed DPoP proof is " +
                        (if (proofAccepted && forgeryRejected) "ACCEPTED by the live issuer, and a forged one is not"
                        else "NOT proven (accepted=$proofAccepted, forgery rejected=$forgeryRejected)")
            )
        }
    }

    /** A P-256 key, the shape multipaz creates for its own DPoP key. */
    private suspend fun SecureArea.enclaveKey(alias: String): AsymmetricKey {
        createKey(
            alias,
            SecureEnclaveCreateKeySettings.Builder().setAlgorithm(Algorithm.ESP256).build(),
        )
        return AsymmetricKey.anonymous(this, alias)
    }

    /**
     * A DPoP proof of exactly the shape multipaz mints in `OpenID4VCIUtil.generateDPoP`, built on the
     * same public [buildJwt] — so the signature encoding is multipaz's own rather than a copy of it.
     */
    private suspend fun AsymmetricKey.dpopProof(
        url: String = deferredEndpoint,
        ath: String? = null,
        nonce: String? = null,
    ): String = buildJwt(
        type = "dpop+jwt",
        key = this,
        header = { put("jwk", publicKey.toJwk()) },
    ) {
        put("htm", "POST")
        put("htu", url)
        put("jti", Random.nextBytes(15).toBase64Url())
        nonce?.let { put("nonce", it) }
        ath?.let { put("ath", it) }
    }

    /** The same JWT with one byte of its signature flipped — the control. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun String.withBrokenSignature(): String {
        val signature = BASE64_URL.decode(substringAfterLast('.'))
        signature[0] = (signature[0].toInt() xor 0xFF).toByte()
        return substringBeforeLast('.') + "." + signature.toBase64Url()
    }

    /**
     * Attestation-based client authentication: a wallet attestation over [instanceKey] plus a PoP over
     * a **fresh** challenge. Keycloak rejects a reused challenge, which is why this is re-done per call
     * rather than hoisted — the same defect [OpenID4VciHttpClient] works around for multipaz.
     */
    private suspend fun HttpClient.attestationHeaders(instanceKey: AsymmetricKey): Map<String, String> {
        val attestation = post(walletProvider) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("jwk", instanceKey.publicKey.toJwk()) }.toString())
        }.jsonBody()["walletInstanceAttestation"]!!.jsonPrimitive.content

        val challenge = post(challengeEndpoint) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("")
        }.jsonBody()["attestation_challenge"]!!.jsonPrimitive.content

        val pop = buildJwt(
            type = "oauth-client-attestation-pop+jwt",
            key = instanceKey,
            expiresIn = 2.minutes,
        ) {
            put("iss", clientId)
            put("aud", authorizationServer)
            put("jti", Random.nextBytes(15).toBase64Url())
            // `challenge`, NOT `nonce` — multipaz's own claim name. With `nonce` the PAR comes back as
            // a flat 401 "Authentication failed." that names nothing.
            put("challenge", challenge)
        }
        return mapOf(
            "OAuth-Client-Attestation" to attestation,
            "OAuth-Client-Attestation-PoP" to pop,
        )
    }

    /** PAR is mandatory for this client, whatever the realm metadata says about requiring it. */
    private suspend fun HttpClient.pushAuthorizationRequest(
        instanceKey: AsymmetricKey,
        verifier: String,
    ): String {
        val challenge = Crypto.digest(Algorithm.SHA256, verifier.encodeToByteArray()).toBase64Url()
        val response = post(parEndpoint) {
            attestationHeaders(instanceKey).forEach { (name, value) -> header(name, value) }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                formBody(
                    "response_type" to "code",
                    "client_id" to clientId,
                    "redirect_uri" to redirectUri,
                    "scope" to scope,
                    "state" to Random.nextBytes(8).toBase64Url(),
                    "code_challenge" to challenge,
                    "code_challenge_method" to "S256",
                )
            )
        }
        check(response.status.isSuccess()) { "PAR failed: ${response.status} ${response.bodyAsText()}" }
        return response.jsonBody()["request_uri"]!!.jsonPrimitive.content
    }

    /**
     * Answers Keycloak's login form and returns the authorization code.
     *
     * The `request_uri` expires in 60 seconds, which is why this is scripted rather than tapped — the
     * same reason `keycloak-login-script.py` exists for the wallet probe.
     */
    private suspend fun HttpClient.logIn(requestUri: String): String {
        var url = "$authorizationEndpoint?client_id=$clientId&request_uri=$requestUri"
        var page = ""
        repeat(MAX_REDIRECTS) {
            val response = get(url)
            val location = response.headers[HttpHeaders.Location]
            if (location == null) {
                page = response.bodyAsText()
                return@repeat
            }
            url = if (location.startsWith("http")) location else "$authorizationServer$location"
        }
        val action = Regex("""<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"""")
            .find(page)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?: error("no Keycloak login form in the page (${page.take(200)})")

        val submitted = post(action) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody("username" to username, "password" to password, "credentialId" to ""))
        }
        val location = submitted.headers[HttpHeaders.Location]
            ?: error("Keycloak re-rendered the form: credentials, or a stale request_uri")
        return Regex("""[?&]code=([^&]+)""").find(location)?.groupValues?.get(1)
            ?: error("no code in the redirect: $location")
    }

    /** The token exchange, carrying a DPoP proof signed in the Secure Enclave. */
    private suspend fun HttpClient.exchange(
        code: String,
        verifier: String,
        instanceKey: AsymmetricKey,
        dpopKey: AsymmetricKey,
    ): JsonObject {
        val response = post(tokenEndpoint) {
            attestationHeaders(instanceKey).forEach { (name, value) -> header(name, value) }
            header(DPOP, dpopKey.dpopProof(url = tokenEndpoint))
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                formBody(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "client_id" to clientId,
                    "code_verifier" to verifier,
                )
            )
        }
        check(response.status.isSuccess()) {
            "token exchange failed: ${response.status} ${response.bodyAsText()}"
        }
        return response.jsonBody()
    }

    private suspend fun HttpClient.callDeferred(accessToken: String, proof: String): HttpResponse =
        post(deferredEndpoint) {
            header(HttpHeaders.Authorization, "DPoP $accessToken")
            header(DPOP, proof)
            contentType(ContentType.Application.Json)
            setBody("""{"transaction_id":"probe-not-a-real-handle"}""")
        }

    private suspend fun HttpResponse.jsonBody(): JsonObject =
        json.parseToJsonElement(bodyAsText()).jsonObject

    @OptIn(ExperimentalEncodingApi::class)
    private fun String.jwtPayload(): String = BASE64_URL.decode(split(".")[1]).decodeToString()

    private fun formBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (name, value) -> "$name=${value.formUrlEncode()}" }

    /** Enough encoding for the handful of values posted here; Ktor's own encoder is not on this path. */
    private fun String.formUrlEncode(): String = buildString {
        this@formUrlEncode.encodeToByteArray().forEach { byte ->
            val char = byte.toInt().toChar()
            when {
                char.isLetterOrDigit() || char in "-._~" -> append(char)
                else -> append('%')
                    .append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    private companion object {
        const val DPOP = "DPoP"
        const val DPOP_NONCE = "DPoP-Nonce"
        const val MAX_REDIRECTS = 5

        @OptIn(ExperimentalEncodingApi::class)
        val BASE64_URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }
}

/**
 * Runs [DeferredDPoPProbe] against the live EU dev infrastructure and reports each line.
 *
 * Off unless asked for, like every other probe here — the app delegate gates it on a launch argument:
 *
 * ```
 * xcrun simctl launch --console-pty <device> <bundle-id> --dpop-probe <username> <password>
 * ```
 *
 * The dev realm's test credentials are passed in rather than written down here, so a shipped binary
 * carries the mechanism but no account. Keys are created in an [EphemeralStorage], so the real wallet
 * store is untouched.
 */
fun probeDeferredDPoP(username: String, password: String, onResult: (String) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        try {
            DeferredDPoPProbe(onResult, username, password)
                .run(SecureEnclaveSecureArea.create(EphemeralStorage()))
        } catch (throwable: Throwable) {
            onResult("  probe failed: ${throwable::class.simpleName}: ${throwable.message}")
        }
    }
}
