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

// Whether a refresh token can be re-bound to a DIFFERENT DPoP key.
//
// The question decides how to remove a hazard we introduced: one authorization now issues several
// documents, so they share a DPoP key, and multipaz's `DocumentStore.deleteDocument` deletes the key
// named in the deleted document's authorization data — taking it from its siblings.
//
// If each document could trade the shared refresh token for one of its own, bound to its own key, the
// hazard disappears at the source and multipaz's one-key-per-document assumption is restored.
// RFC 9449 says it cannot — a DPoP-bound refresh token is bound to the key that obtained it — but that
// is the letter of a spec, and this ecosystem's servers have already diverged from the letter twice
// today. So it is asked rather than assumed.
//
// ⛔ ORDER MATTERS. The re-bind attempt runs FIRST: a *successful* refresh rotates the token and would
// destroy the control. A rejected one leaves it spendable, so the control can still prove the token was
// good and the refusal was about the key.
package eu.europa.ec.shared.wallet.multipaz

import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import eu.europa.ec.shared.wallet.config.iosWalletConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import org.multipaz.webtoken.buildJwt
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

suspend fun probeDpopRebinding(onResult: (String) -> Unit) {
    onResult("--- DPoP re-binding ---")

    // Every document's key, so "one authorization, one key each" is visible rather than argued.
    MultipazWalletStore.open().documentStore.listDocuments().forEach { document ->
        document.authorizationData?.openID4VciAuthorization()?.let {
            onResult("  ${document.identifier} -> dpopKeyAlias=${it.dpopKeyAlias}")
        }
    }

    val store = MultipazWalletStore.open()
    // Newest first: a refresh token lives about 1800s, so an older document's is simply dead and the
    // question cannot be asked of it.
    val candidate = store.documentStore.listDocuments()
        .sortedByDescending { it.created }
        .firstNotNullOfOrNull { document ->
            document.authorizationData?.openID4VciAuthorization()?.let { document to it }
        }
    if (candidate == null) {
        onResult("  no document carries authorization data; issue one first")
        return
    }
    val (document, stored) = candidate
    onResult("  using ${document.identifier}, dpopKeyAlias=${stored.dpopKeyAlias}")

    val issuerUrl = IosWalletEngine().getIssuerReference(document.identifier)?.issuerId
    if (issuerUrl == null) {
        onResult("  the document names no issuer; cannot find its authorization server")
        return
    }

    val httpClient = openID4VciHttpClient(Darwin.create())
    try {
        val issuerMetadata = httpClient.get("${issuerUrl.trimEnd('/')}/.well-known/openid-credential-issuer")
            .bodyAsText().asJsonObjectOrJwtPayload()
        val authorizationServer = issuerMetadata["authorization_servers"]
            ?.let { runCatching { it.toString().trim('[', ']', '"') }.getOrNull() }
            ?: issuerUrl
        val asMetadata = httpClient
            .get("${authorizationServer.trimEnd('/')}/.well-known/openid-configuration")
            .bodyAsText().asJsonObjectOrJwtPayload()
        val tokenEndpoint = asMetadata["token_endpoint"]!!.jsonPrimitive.content
        val challengeEndpoint = asMetadata["challenge_endpoint"]?.jsonPrimitive?.content
        val asIdentifier = asMetadata["issuer"]?.jsonPrimitive?.content ?: authorizationServer
        onResult("  token endpoint: $tokenEndpoint")

        // ⛔ Without these the server answers `401 invalid_client` whatever key is used, which looks
        // exactly like a refused re-binding. The first run of this probe did precisely that, and only the
        // control showed it was the probe's fault rather than the server's.
        val attestationKey = store.keySecureArea.let { area ->
            val alias = "dpop-rebind-attestation-${Random.nextBytes(8).toBase64Url()}"
            area.createKey(alias, CreateKeySettings())
            AsymmetricKey.anonymous(area, alias)
        }
        val attestation = httpClient
            .post("${iosWalletConfig.walletProviderUrl}/wallet-instance-attestation/jwk") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("jwk", attestationKey.publicKey.toJwk()) }.toString())
            }.bodyAsText().asJsonObject()["walletInstanceAttestation"]!!.jsonPrimitive.content

        suspend fun attestationHeaders(): Map<String, String> {
            val challenge = challengeEndpoint?.let { endpoint ->
                runCatching {
                    httpClient.post(endpoint) {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("")
                    }.bodyAsText().asJsonObject()["attestation_challenge"]?.jsonPrimitive?.content
                }.getOrNull()
            }
            val pop = buildJwt(
                type = "oauth-client-attestation-pop+jwt",
                key = attestationKey,
                expiresIn = 2.minutes,
            ) {
                put("iss", IosIssuerCatalog.CLIENT_ID)
                put("aud", asIdentifier)
                put("jti", Random.nextBytes(15).toBase64Url())
                challenge?.let { put("challenge", it) }
            }
            return mapOf(
                "OAuth-Client-Attestation" to attestation,
                "OAuth-Client-Attestation-PoP" to pop,
            )
        }

        suspend fun refreshWith(key: AsymmetricKey, label: String): Boolean {
            val form = listOf(
                "grant_type" to "refresh_token",
                "refresh_token" to stored.refreshToken,
                "client_id" to IosIssuerCatalog.CLIENT_ID,
            ).joinToString("&") { (n, v) -> "${n.formEncoded()}=${v.formEncoded()}" }

            suspend fun attempt(nonce: String?): HttpResponse = httpClient.post(tokenEndpoint) {
                attestationHeaders().forEach { (name, value) -> header(name, value) }
                header("DPoP", key.proofFor(tokenEndpoint, nonce))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form)
            }

            var response = attempt(null)
            if (!response.status.isSuccessful()) {
                response.headers["DPoP-Nonce"]?.let { response = attempt(it) }
            }
            val raw = response.bodyAsText()
            if (!response.status.isSuccessful()) {
                onResult("  $label -> ${response.status}  ${raw.take(200)}")
                return false
            }
            // \U0001f511 The decisive bit: an access token says which key it is bound to, in `cnf.jkt`
            // (RFC 9449 §6). Comparing that against the thumbprint of the key we just used answers
            // whether the token was genuinely re-bound, or merely handed over while still bound to the
            // old key — which would fail later, at the issuer, rather than here at the token endpoint.
            val accessToken = raw.asJsonObject()["access_token"]?.jsonPrimitive?.content
            val boundTo = accessToken?.let {
                runCatching {
                    it.split('.')[1].base64UrlPayload().asJsonObject()["cnf"]
                        ?.jsonObject?.get("jkt")?.jsonPrimitive?.content
                }.getOrNull()
            }
            onResult("  $label -> ${response.status}, token bound to jkt=$boundTo (this key's jkt=${key.jkt()})")
            return response.status.isSuccessful()
        }

        // ⛔ The re-bind attempt FIRST — see the note at the top of this file.
        val freshKey = store.keySecureArea.let { area ->
            val alias = "dpop-rebind-probe-${Random.nextBytes(8).toBase64Url()}"
            area.createKey(alias, CreateKeySettings())
            AsymmetricKey.anonymous(area, alias)
        }
        val rebindWorked = refreshWith(freshKey, "REBIND (a brand new DPoP key)")

        val originalKey = runCatching {
            AsymmetricKey.anonymous(store.keySecureArea, stored.dpopKeyAlias)
        }.getOrNull()
        if (originalKey == null) {
            onResult("  CONTROL skipped: the original DPoP key is no longer in the secure area")
        } else {
            val controlWorked = refreshWith(originalKey, "CONTROL (the original DPoP key)")
            onResult(
                "  VERDICT: rebinding ${if (rebindWorked) "WORKS" else "is REFUSED"}; " +
                    "the token itself was ${if (controlWorked) "good" else "NOT usable — inconclusive"}"
            )
        }
    } catch (t: Throwable) {
        Logger.w("DpopRebindProbe", "probe failed", t)
        onResult("  probe failed: ${t::class.simpleName}: ${t.message}")
    } finally {
        httpClient.close()
    }
}

private suspend fun AsymmetricKey.proofFor(url: String, nonce: String?): String = buildJwt(
    type = "dpop+jwt",
    key = this,
    header = { put("jwk", publicKey.toJwk()) },
) {
    put("htm", "POST")
    put("htu", url)
    put("jti", Random.nextBytes(15).toBase64Url())
    nonce?.let { put("nonce", it) }
}

private fun io.ktor.http.HttpStatusCode.isSuccessful(): Boolean = value in 200..299

private fun String.formEncoded(): String = buildString {
    this@formEncoded.encodeToByteArray().forEach { byte ->
        val char = byte.toInt().toChar()
        if (char.isLetterOrDigit() || char in "-_.~") {
            append(char)
        } else {
            append('%').append(byte.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/**
 * This key's JWK thumbprint (RFC 7638), which is what an access token's `cnf.jkt` names.
 *
 * The member ordering is the specification's, not a preference: the thumbprint is a hash of the
 * canonical JSON, so `crv`, `kty`, `x`, `y` in that order with no whitespace is the whole contract.
 */
private suspend fun AsymmetricKey.jkt(): String {
    val jwk = publicKey.toJwk().jsonObject
    fun member(name: String) = jwk[name]?.jsonPrimitive?.content.orEmpty()
    val canonical = """{"crv":"${member("crv")}","kty":"${member("kty")}","x":"${member("x")}","y":"${member("y")}"}"""
    return Crypto.digest(Algorithm.SHA256, canonical.encodeToByteArray()).toBase64Url()
}

/** Decodes a JWT segment; the collector's equivalent is private to its own file. */
@OptIn(ExperimentalEncodingApi::class)
private fun String.base64UrlPayload(): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(this).decodeToString()
