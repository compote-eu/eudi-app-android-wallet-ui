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
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpMethod
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.formUrlEncode
import io.ktor.http.parseUrlEncodedParameters
import io.ktor.http.Url
import io.ktor.client.utils.EmptyContent
import io.ktor.util.Attributes
import kotlinx.coroutines.Job
import io.ktor.utils.io.InternalAPI
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import eu.europa.ec.shared.wallet.document.IssuerMetadata
import org.multipaz.util.Logger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * What an issuer said when it deferred an issuance instead of performing it.
 *
 * Written by [OpenID4VciCompatibilityEngine] and read by the caller *after* the attempt has failed. It
 * exists because only the engine sees the credential endpoint's raw answer: multipaz turns a deferred
 * response into a plain "unexpected status" error, so without this the reason is a protocol string in a
 * message rather than something the app can reason about.
 *
 * @property transactionId the handle the issuer would honour at its `deferred_credential_endpoint` —
 *   recorded so it appears in diagnostics, not because anything can redeem it yet.
 * @property retryAfterSeconds the issuer's own `interval`, in seconds.
 */
class DeferredIssuanceNotice {
    var transactionId: String? = null
        internal set
    var retryAfterSeconds: Int? = null
        internal set

    /** True once the issuer has deferred this attempt. */
    val wasDeferred: Boolean get() = transactionId != null
}

/**
 * The issuer's per-claim display names, which only the raw metadata carries.
 *
 * Written by [OpenID4VciCompatibilityEngine] while it is already reading the metadata JSON, and read by
 * the provisioning handler when it builds the document's [IssuerMetadata]. It exists for the same reason
 * [DeferredIssuanceNotice] does: **multipaz parses the credential configuration and throws this part
 * away** — its `CredentialMetadata` keeps a credential-level `display` and no per-claim display at all —
 * so the engine is the only place that still sees it.
 *
 * Keyed by **doctype or vct**, not by configuration id: the handler joins on
 * `StoredDocumentFormat.identifier`, and `CredentialMetadata.format.formatId` is only the *format*
 * (`mso_mdoc`), which would not distinguish two documents. Configurations that share a doctype — this
 * issuer's `_deferred` twins do — carry the same claims, so last-one-wins is harmless.
 */
internal class IssuerClaimDisplayNotice {
    var claimsByDocumentType: Map<String, List<IssuerMetadata.Claim>> = emptyMap()
        internal set
}

/**
 * Why the authorization server refused a token exchange, in its own words.
 *
 * Written by [OpenID4VciCompatibilityEngine] and read after the attempt has failed, for the same reason
 * [DeferredIssuanceNotice] exists: multipaz collapses every non-OK token response into one message —
 * *"Refresh token (seed credential) rejected by the issuer"* — so the OAuth error code that says which
 * thing was actually rejected is visible only here. It is the difference between "add this document
 * again" and "try again later".
 *
 * @property error the `error` member of the refusal body, e.g. `invalid_grant` or `invalid_client`.
 */
class TokenRefusalNotice {
    var error: String? = null
        internal set
}

/**
 * The HTTP client multipaz's OpenID4VCI code should be given on iOS.
 *
 * Two things sit between it and the network, both because the EU dev issuers and multipaz disagree —
 * measured by running them against each other, not guessed:
 *
 * 1. **`dev.issuer-backend.eudiw.dev` serves its issuer metadata as a signed JWT**
 *    (`Content-Type: application/jwt`, an `x5c` header), which OpenID4VCI permits. multipaz parses the
 *    body as JSON and fails with *"Element class JsonLiteral is not a JsonObject"*. So a metadata
 *    response that is a JWT is unwrapped to its payload here.
 * 2. **`ec.dev.issuer.eudiw.dev`'s metadata points its display logos at `examplestate.com`**, a host
 *    that does not resolve. multipaz fetches logos *while parsing metadata* and already tolerates a
 *    non-OK status — but a DNS failure throws out of the engine and takes the whole metadata read with
 *    it. So a failed optional GET becomes a 502 rather than an exception.
 * 3. **multipaz reuses the client-attestation challenge across PAR and the token request**, and Keycloak
 *    treats those challenges as single-use, so authorization-code issuance dies at the token endpoint
 *    with `401 invalid_client`. See [injectFreshAttestationChallenge] — this one is a multipaz bug and
 *    the fix here is a stop-gap, not the answer.
 * 4. **multipaz asks for the credential with `authorization_details` where this server only understands
 *    `scope`**, which fails the token request with `400 invalid_authorization_details`. See
 *    [withScopeInsteadOfAuthorizationDetails] — also a multipaz bug, also a stop-gap.
 * 5. **multipaz never fetches a client-attestation challenge for a credential *refresh* at all**, so
 *    `grant_type=refresh_token` sends a PoP with no `challenge` claim and is refused `401
 *    invalid_client`. Same underlying bug as (3), but a refresh makes no PAR request, so (3)'s hook
 *    never fires. See [armRefreshRetry].
 *
 * Both live here rather than in a fork of multipaz because multipaz takes the client from its caller for
 * every request, which makes this the one seam that needs no patched dependency. Both should disappear:
 * (1) when multipaz reads signed metadata, (2) when it stops letting a logo fetch fail a metadata read
 * (or when the issuer fixes its URLs).
 */
internal fun openID4VciHttpClient(
    // Swappable so the compatibility rules can be tested against a `MockEngine` rather than the
    // network; production always takes the default.
    engine: HttpClientEngine = Darwin.create(),
    /** Filled in if the issuer defers an issuance during this client's lifetime. */
    deferredNotice: DeferredIssuanceNotice? = null,
    /** Filled in if the authorization server refuses a token exchange. */
    refusalNotice: TokenRefusalNotice? = null,
    /** Filled in with the issuer's per-claim display names, which multipaz discards. */
    claimDisplayNotice: IssuerClaimDisplayNotice? = null,
): HttpClient =
    HttpClient(
        OpenID4VciCompatibilityEngine(engine, deferredNotice, refusalNotice, claimDisplayNotice)
    ) {
        // multipaz's requirement, not ours: the OAuth redirect must come back to the caller so the
        // authorization code can be read off it.
        followRedirects = false
    }

/**
 * Wraps another engine and applies the two compatibility fixes described on [openID4VciHttpClient].
 *
 * An engine rather than an `HttpClientPlugin` deliberately: multipaz reads bodies with
 * `readRawBytes()`, which bypasses the response-transformation pipeline a plugin would hook, so a
 * plugin could not rewrite what multipaz actually sees. `HttpClientEngineBase` is the extension point
 * JetBrains documents for custom engines; `execute` carries `@InternalAPI` for everyone who implements
 * one, which is why the opt-in below is not a smell.
 */
@OptIn(InternalAPI::class)
internal class OpenID4VciCompatibilityEngine(
    private val delegate: HttpClientEngine,
    private val deferredNotice: DeferredIssuanceNotice? = null,
    private val refusalNotice: TokenRefusalNotice? = null,
    private val claimDisplayNotice: IssuerClaimDisplayNotice? = null,
) : HttpClientEngineBase("openid4vci-compat") {

    override val config: HttpClientEngineConfig get() = delegate.config

    override val supportedCapabilities get() = delegate.supportedCapabilities

    /**
     * Endpoints learned from the authorization server's own metadata, so nothing here has to guess at
     * URL shapes. Plain vars: a provisioning session is sequential, and the only other traffic through
     * this engine (display logos) never touches them.
     */
    private var challengeEndpoint: String? = null
    private var pushedAuthorizationRequestEndpoint: String? = null
    private var tokenEndpoint: String? = null

    /** So the retry is armed once per session, not on every 401 the server sends. */
    private var armedRefreshRetry = false

    /** `credential_configuration_id` → OAuth `scope`, from the credential issuer's own metadata. */
    private var scopesByConfigurationId: Map<String, String> = emptyMap()

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val request =
            if (isPushedAuthorizationRequest(data)) withScopeInsteadOfAuthorizationDetails(data)
            else data

        val isTokenExchange = isTokenRequest(data)
        val isRefreshExchange = isTokenExchange && isRefreshGrant(data)
        if (isTokenExchange) traceTokenRequest(data)

        val response = try {
            delegate.execute(request)
        } catch (t: Throwable) {
            if (isOptional(data)) {
                // A logo, a card art, some display extra. multipaz checks the status, so hand it one.
                // Keep the log short: Darwin's DNS errors are a screenful each.
                Logger.w(TAG, "optional GET ${data.url} failed (${t::class.simpleName}); reporting 502")
                return emptyResponse(HttpStatusCode.BadGateway)
            }
            throw t
        }

        if (isTokenExchange) traceTokenResponse(response)

        return when {
            isWellKnown(data) -> rememberEndpoints(data, response)
            isPushedAuthorizationRequest(data) -> injectFreshAttestationChallenge(response)
            isRefreshExchange && response.statusCode != HttpStatusCode.OK ->
                noteAndMaybeArm(response)

            isDeferredIssuance(response) -> noteDeferredIssuance(response)
            else -> response
        }
    }

    /**
     * An issuer that will issue later answers the credential request with `202 Accepted` and a
     * `transaction_id` instead of credentials (OpenID4VCI's deferred flow).
     */
    private fun isDeferredIssuance(response: HttpResponseData): Boolean =
        deferredNotice != null && response.statusCode == HttpStatusCode.Accepted

    /**
     * Records the deferred handle and passes the response through **unchanged**.
     *
     * Deliberately an observation rather than a rewrite, unlike the other rules here: multipaz cannot
     * complete a deferred issuance at all — it never parses `deferred_credential_endpoint` and treats any
     * non-200 as an error — so there is nothing to paper over. What the note buys is a truthful message
     * instead of `Error getting a credential issued: 202 Accepted {...}`.
     */
    private suspend fun noteDeferredIssuance(response: HttpResponseData): HttpResponseData {
        val (bytes, replayable) = replayableBody(response)
        val body = runCatching {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        }.getOrNull()

        body?.get("transaction_id")?.jsonPrimitive?.contentOrNull?.let { transactionId ->
            deferredNotice?.transactionId = transactionId
            deferredNotice?.retryAfterSeconds =
                body["interval"]?.jsonPrimitive?.content?.toIntOrNull()
            Logger.i(TAG, "the issuer deferred this issuance (transaction $transactionId)")
        }
        return replayable
    }

    /** Reads a response body and hands back an equivalent response, since a channel is read once. */
    private suspend fun replayableBody(response: HttpResponseData): Pair<ByteArray, HttpResponseData> {
        val bytes = (response.body as? ByteReadChannel)?.readRemaining()?.readByteArray()
            ?: ByteArray(0)
        return bytes to HttpResponseData(
            statusCode = response.statusCode,
            requestTime = response.requestTime,
            headers = response.headers,
            version = response.version,
            body = ByteReadChannel(bytes),
            callContext = response.callContext,
        )
    }

    override fun close() {
        delegate.close()
        super.close()
    }

    /**
     * Whether a failure here can be reported instead of thrown.
     *
     * Only GETs, and never the protocol's own discovery documents: an unreachable `.well-known` is a
     * real failure and must not be softened into a confusing parse error further up. Everything else a
     * GET fetches in this flow is presentation — logos and card art — which multipaz treats as
     * optional already. POSTs (PAR, token, nonce, credential) always throw.
     */
    private fun isOptional(data: HttpRequestData): Boolean =
        data.method == HttpMethod.Get && !data.url.encodedPath.contains(WELL_KNOWN)

    private fun isWellKnown(data: HttpRequestData): Boolean =
        data.url.encodedPath.contains(WELL_KNOWN)

    private fun isSignedMetadata(response: HttpResponseData): Boolean =
        response.statusCode == HttpStatusCode.OK &&
                response.headers[CONTENT_TYPE]?.contains("jwt", ignoreCase = true) == true

    private fun isPushedAuthorizationRequest(data: HttpRequestData): Boolean =
        data.method == HttpMethod.Post &&
                pushedAuthorizationRequestEndpoint?.let { data.url.toString() == it } == true

    private fun isTokenRequest(data: HttpRequestData): Boolean =
        data.method == HttpMethod.Post &&
                tokenEndpoint?.let { data.url.toString() == it } == true

    private fun isRefreshGrant(data: HttpRequestData): Boolean =
        formParametersOf(data)?.get("grant_type") == "refresh_token"

    private fun formParametersOf(data: HttpRequestData): Parameters? =
        (data.body as? OutgoingContent.ByteArrayContent)
            ?.bytes()?.decodeToString()?.parseUrlEncodedParameters()

    /**
     * Unwraps signed metadata if needed, and remembers the two endpoints [injectFreshAttestationChallenge]
     * depends on.
     *
     * Reading the body here means it has to be handed back re-wrapped, which is why every metadata
     * response goes through [HttpResponseData.replacingBody] even when nothing was rewritten.
     */
    private suspend fun rememberEndpoints(
        data: HttpRequestData,
        response: HttpResponseData,
    ): HttpResponseData {
        if (response.statusCode != HttpStatusCode.OK) return response
        val unwrapped =
            if (isSignedMetadata(response)) unwrapSignedMetadata(data, response) else response
        val bytes = (unwrapped.body as ByteReadChannel).readRemaining().readByteArray()

        runCatching {
            val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            json["challenge_endpoint"]?.jsonPrimitive?.content?.let {
                challengeEndpoint = it
                Logger.i(TAG, "learned challenge endpoint")
            }
            json["pushed_authorization_request_endpoint"]?.jsonPrimitive?.content?.let {
                pushedAuthorizationRequestEndpoint = it
            }
            json["token_endpoint"]?.jsonPrimitive?.content?.let { tokenEndpoint = it }
            json["credential_configurations_supported"]?.jsonObject?.let { configurations ->
                scopesByConfigurationId = configurations.mapNotNull { (id, configuration) ->
                    configuration.jsonObject["scope"]?.jsonPrimitive?.content?.let { id to it }
                }.toMap()
                Logger.i(TAG, "learned ${scopesByConfigurationId.size} credential scopes")
                rememberClaimDisplayNames(configurations)
            }
        }

        return unwrapped.replacingBody(bytes, asJson = true)
    }

    /**
     * Keeps the per-claim display names out of `credential_metadata.claims`, so a document can show
     * "Family Name(s)" instead of `family_name`.
     *
     * Deserialised straight into [IssuerMetadata.Claim], whose `@SerialName`s already match the wire
     * shape — the alternative was a hand-rolled parse of the same JSON. Unknown members are ignored
     * because the issuer publishes more per claim than this needs, and a new one must not fail an
     * issuance over a cosmetic feature.
     */
    private fun rememberClaimDisplayNames(configurations: JsonObject) {
        val notice = claimDisplayNotice ?: return
        val byDocumentType = configurations.values.mapNotNull { configuration ->
            val configured = configuration.jsonObject
            // `doctype` for mdoc, `vct` for SD-JWT VC — whichever this configuration is.
            val documentType = (configured["doctype"] ?: configured["vct"])
                ?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val claims = configured["credential_metadata"]?.jsonObject?.get("claims")
                ?: return@mapNotNull null
            runCatching {
                documentType to LenientJson.decodeFromJsonElement(ListSerializer(IssuerMetadata.Claim.serializer()), claims)
            }.getOrNull()
        }.toMap()

        if (byDocumentType.isNotEmpty()) {
            notice.claimsByDocumentType = byDocumentType
            Logger.i(
                TAG,
                "learned claim display names for ${byDocumentType.size} document type(s): " +
                        byDocumentType.entries.joinToString { "${it.key}=${it.value.size}" }
            )
        }
    }

    /**
     * Adds an `OAuth-Client-Attestation-Challenge` header carrying a **fresh** challenge to the PAR
     * response.
     *
     * 🩹 **Working around a multipaz bug, and it should be removed when that is fixed.**
     * `OpenID4VCIProvisioningClient.obtainToken` refreshes the client-attestation challenge only when a
     * *pre-authorized* code is used:
     * ```
     * if (preauthorizedCode != null) { maybeObtainClientAttestationChallenge() }
     * ```
     * On the authorization-code path it therefore reuses the challenge minted before PAR. Keycloak
     * treats attestation challenges as single-use, so the token request's PoP is a replay and the
     * answer is `401 invalid_client`. Verified by diffing against the Android app, whose call order is
     * `/challenge → PAR → /challenge → /token` while multipaz's is `/challenge → PAR → /token`.
     *
     * The lever is that multipaz *does* pick up a challenge offered in this response header (it reads it
     * after both PAR and the token request). Keycloak does not send one, so it is fetched and injected
     * here — leaving multipaz's own logic untouched.
     */
    private suspend fun injectFreshAttestationChallenge(
        response: HttpResponseData,
    ): HttpResponseData {
        val endpoint = challengeEndpoint ?: return response
        if (response.headers[ATTESTATION_CHALLENGE_HEADER] != null) {
            // The server offered one itself; nothing to do, and it knows better than we do.
            return response
        }

        val challenge = runCatching { fetchAttestationChallenge(endpoint) }.getOrNull()
        if (challenge == null) {
            Logger.w(TAG, "could not pre-fetch an attestation challenge; the token request may fail")
            return response
        }

        Logger.i(TAG, "injected a fresh attestation challenge into the PAR response")
        return response.withExtraHeaders { append(ATTESTATION_CHALLENGE_HEADER, challenge) }
    }

    /**
     * Records why a refresh was refused, and arms the retry if the refusal is one a retry can fix.
     *
     * Both halves need the body, and a response body can be read once, so they share one read. Only a
     * `401` is worth retrying: that is the client being rejected, which [armRefreshRetry] can answer.
     * A `400 invalid_grant` means the refresh token itself is spent or expired, and retrying it with
     * better client credentials would fail the same way — the honest thing is to let it through and let
     * the caller say so.
     */
    private suspend fun noteAndMaybeArm(response: HttpResponseData): HttpResponseData {
        val (bytes, replayable) = replayableBody(response)
        refusalNotice?.error = runCatching {
            Json.parseToJsonElement(bytes.decodeToString())
                .jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        refusalNotice?.error?.let { Logger.i(TAG, "the refresh was refused: $it") }

        return if (response.statusCode == HttpStatusCode.Unauthorized) armRefreshRetry(replayable)
        else replayable
    }

    /**
     * Makes multipaz retry a refused **refresh** token request with a challenge it never fetched.
     *
     * 🩹 **The same multipaz bug as [injectFreshAttestationChallenge], on the path that shim cannot
     * reach.** `obtainToken` refreshes the client-attestation challenge only when a pre-authorized code
     * is in play, so on `grant_type=refresh_token` — a brand-new provisioning client, and no PAR
     * anywhere in the flow — `clientAttestationChallenge` is still null when the PoP is minted, and
     * `createWalletAttestationPoP` just leaves the claim out.
     *
     * Measured against `dev.issuer-backend.eudiw.dev`, not inferred: the refused refresh sends
     * `OAuth-Client-Attestation-PoP` with `claims=[aud, iat, iss, jti]` and **no `challenge`**, while
     * the issuance request accepted seconds later by the same endpoint carries one. So `401
     * invalid_client` is the literal truth — the *client* was rejected, not the refresh token that
     * multipaz's message blames. Note what this rules out: a PoP with no challenge claim at all fails
     * the same way one second after issuance as it does a week later, so the wallet attestation's short
     * life is not what is biting here.
     *
     * The only lever left is multipaz's own retry, which fires once per token exchange:
     * ```
     * response.headers["DPoP-Nonce"]?.let { authorizationDPoPNonce = it }
     * response.headers["OAuth-Client-Attestation-Challenge"]?.let { clientAttestationChallenge = it }
     * if (...) { if (!retried && authorizationDPoPNonce != null) { retried = true; continue } }
     * ```
     * It picks a fresh challenge off *this* response before deciding, so the challenge header alone
     * would make the second attempt correct — but the retry is gated on a DPoP nonce this server never
     * sends, so the header has to be answered too. See [RETRY_ARMING_DPOP_NONCE] for why that is
     * tolerable.
     */
    private suspend fun armRefreshRetry(response: HttpResponseData): HttpResponseData {
        if (armedRefreshRetry) return response
        val endpoint = challengeEndpoint ?: return response

        val challenge = runCatching { fetchAttestationChallenge(endpoint) }.getOrNull()
        if (challenge == null) {
            Logger.w(TAG, "no challenge to retry the refused refresh with; reporting the refusal")
            return response
        }

        armedRefreshRetry = true
        Logger.i(TAG, "arming multipaz's retry with a fresh attestation challenge for the refresh")
        return response.withExtraHeaders {
            if (response.headers[ATTESTATION_CHALLENGE_HEADER] == null) {
                append(ATTESTATION_CHALLENGE_HEADER, challenge)
            }
            if (response.headers[DPOP_NONCE_HEADER] == null) {
                append(DPOP_NONCE_HEADER, RETRY_ARMING_DPOP_NONCE)
            }
        }
    }

    /** The same response with more headers, and the body passed straight through unread. */
    private fun HttpResponseData.withExtraHeaders(
        extra: HeadersBuilder.() -> Unit,
    ): HttpResponseData = HttpResponseData(
        statusCode = statusCode,
        requestTime = requestTime,
        headers = HeadersBuilder().apply { appendAll(headers); extra() }.build(),
        version = version,
        body = body,
        callContext = callContext,
    )

    /**
     * Replaces `authorization_details` in a PAR body with the equivalent `scope`.
     *
     * 🩹 **Working around a second multipaz bug.** `performPushedAuthorizationRequest` drops a
     * configuration's `scope` whenever another configuration shares that scope *and* format, logging
     * *"Scope does not uniquely identify credential for configuration id …"*, and falls back to
     * `authorization_details` with `type=openid_credential`. On this issuer **every** configuration has a
     * `_deferred` twin with the same scope and format, so the fallback is taken every time — and the
     * authorization server does not implement that RAR type, answering
     * `400 invalid_authorization_details: Unsupported type 'openid_credential'`. The Android wallet sends
     * `scope=…` for the same credential and succeeds.
     *
     * Rewriting a *request* body is safe here specifically because the proofs are not over it: the DPoP
     * and client-attestation PoP JWTs bind the method and URL (`htm`/`htu`), not the payload.
     *
     * Anything unexpected — no mapping for the requested configuration, a body that is not form data, a
     * request that already carries a scope — is left exactly as multipaz built it.
     */
    private fun withScopeInsteadOfAuthorizationDetails(data: HttpRequestData): HttpRequestData {
        val body = data.body as? OutgoingContent.ByteArrayContent ?: return data
        val parameters = body.bytes().decodeToString().parseUrlEncodedParameters()
        val details = parameters["authorization_details"] ?: return data
        if (parameters["scope"] != null) return data

        val configurationIds = runCatching {
            Json.parseToJsonElement(details).jsonArray.mapNotNull {
                it.jsonObject["credential_configuration_id"]?.jsonPrimitive?.content
            }
        }.getOrElse { emptyList() }

        val scopes = configurationIds.mapNotNull { scopesByConfigurationId[it] }
        if (scopes.isEmpty() || scopes.size != configurationIds.size) {
            Logger.w(TAG, "no scope known for $configurationIds; leaving authorization_details alone")
            return data
        }

        Logger.i(TAG, "sending scope '${scopes.joinToString(" ")}' instead of authorization_details")
        val rewritten = Parameters.build {
            parameters.forEach { name, values ->
                if (name != "authorization_details") appendAll(name, values)
            }
            append("scope", scopes.joinToString(" "))
        }.formUrlEncode()

        return HttpRequestData(
            url = data.url,
            method = data.method,
            // Content-Length would describe the old body; the new content supplies its own.
            headers = HeadersBuilder().apply {
                data.headers.forEach { name, values ->
                    if (!name.equals(CONTENT_LENGTH, ignoreCase = true)) appendAll(name, values)
                }
            }.build(),
            body = TextContent(rewritten, ContentType.Application.FormUrlEncoded),
            executionContext = data.executionContext,
            attributes = data.attributes,
        )
    }

    /** POSTs the challenge endpoint through the delegate, so tests can serve it like any other call. */
    private suspend fun fetchAttestationChallenge(endpoint: String): String? {
        val response = delegate.execute(
            HttpRequestData(
                url = Url(endpoint),
                method = HttpMethod.Post,
                headers = Headers.Empty,
                body = EmptyContent,
                executionContext = Job(),
                attributes = Attributes(),
            )
        )
        if (response.statusCode != HttpStatusCode.OK) return null
        val text = (response.body as ByteReadChannel).readRemaining().readByteArray().decodeToString()
        return Json.parseToJsonElement(text).jsonObject["attestation_challenge"]?.jsonPrimitive?.content
    }

    /**
     * Replaces a `statuslist`-style signed metadata response with the JWT's payload.
     *
     * ⚠️ **The signature is NOT verified, and that is a known gap rather than an oversight.** Verifying
     * it means validating the `x5c` chain to a trust anchor, and iOS has no ARF trust list wired up yet
     * — the same gap that leaves `wallet.trust` on the Android side of the port. Leaf-only verification
     * would be theatre: an attacker who can rewrite the body can present their own chain. What still
     * holds is TLS, and the check below that the metadata actually describes the issuer we asked about,
     * which catches a substituted document. The signer is logged so a run can be audited by eye.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun unwrapSignedMetadata(
        data: HttpRequestData,
        response: HttpResponseData,
    ): HttpResponseData {
        val jwt = (response.body as ByteReadChannel).readRemaining().readByteArray().decodeToString()
        val parts = jwt.trim().split('.')
        if (parts.size != 3) {
            Logger.w(TAG, "signed metadata from ${data.url} is not a JWT; passing it through")
            return response.replacingBody(jwt.encodeToByteArray())
        }

        val payload = try {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
                .decode(parts[1])
                .decodeToString()
        } catch (t: Throwable) {
            Logger.w(TAG, "signed metadata payload from ${data.url} would not decode: ${t.message}")
            return response.replacingBody(jwt.encodeToByteArray())
        }

        val json = Json.parseToJsonElement(payload).jsonObject
        val issuer = json["credential_issuer"]?.jsonPrimitive?.content
        val expected = "${data.url.protocol.name}://${data.url.host}"
        if (issuer != null && !issuer.startsWith(expected)) {
            // Cheap but real: a metadata document for a *different* issuer means something replaced it.
            throw IllegalStateException(
                "signed metadata from ${data.url} describes '$issuer', not '$expected'"
            )
        }
        Logger.i(TAG, "unwrapped signed metadata for $issuer (signer: ${signerOf(parts[0])})")

        return response.replacingBody(payload.encodeToByteArray(), asJson = true)
    }

    /** The `kid`/`x5c` hint from the JWT header, for the log line only. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun signerOf(encodedHeader: String): String = try {
        val header = Json.parseToJsonElement(
            Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
                .decode(encodedHeader)
                .decodeToString()
        ) as JsonObject
        header["kid"]?.jsonPrimitive?.content
            ?: header["x5c"]?.let { "x5c(${it.toString().length} bytes)" }
            ?: "unknown"
    } catch (_: Throwable) {
        "unparseable"
    }

    private fun HttpResponseData.replacingBody(
        bytes: ByteArray,
        asJson: Boolean = false,
    ): HttpResponseData = HttpResponseData(
        statusCode = statusCode,
        requestTime = requestTime,
        headers = HeadersBuilder().apply {
            headers.forEach { name, values ->
                // Content-Length and Content-Type no longer describe the body we are handing back.
                if (!name.equals(CONTENT_LENGTH, ignoreCase = true) &&
                    !name.equals(CONTENT_TYPE, ignoreCase = true)
                ) {
                    appendAll(name, values)
                }
            }
            append(CONTENT_TYPE, if (asJson) "application/json" else "application/jwt")
            append(CONTENT_LENGTH, bytes.size.toString())
        }.build(),
        version = version,
        body = ByteReadChannel(bytes),
        callContext = callContext,
    )

    /**
     * A synthetic response, built the way ktor's own `MockEngine` builds one.
     *
     * `callContext()` matters and is not interchangeable with the request's `executionContext`: the
     * client waits on the call context's job to finish the call, and handing it the *request's* job
     * deadlocks — the first tolerated logo failure hung the whole probe until this was fixed.
     */
    private suspend fun emptyResponse(status: HttpStatusCode): HttpResponseData = HttpResponseData(
        statusCode = status,
        requestTime = GMTDate(),
        headers = Headers.Empty,
        version = HttpProtocolVersion.HTTP_1_1,
        body = ByteReadChannel(ByteArray(0)),
        callContext = callContext(),
    )

    /**
     * Logs the *shape* of a token request, and what the server made of it.
     *
     * Values are deliberately absent: the form carries a refresh token and the headers carry a wallet
     * attestation. What is logged is which parameters and headers are present, and which claims each
     * proof JWT asserts — because that is what every token-endpoint failure on this stack has turned
     * on. The first `401 invalid_client` was found by diffing Android's claim set against multipaz's,
     * and so was the refresh one, which is why `challenge` is called out by name.
     */
    private fun traceTokenRequest(data: HttpRequestData) {
        val parameters = formParametersOf(data)
        Logger.i(
            TAG,
            "token request: grant_type=${parameters?.get("grant_type")} " +
                    "params=${parameters?.names()?.sorted()} " +
                    "headers=${data.headers.names().sorted()}"
        )
        listOf(DPOP_HEADER, ATTESTATION_POP_HEADER).forEach { header ->
            data.headers[header]?.let { Logger.i(TAG, "  $header -> ${claimsOf(it)}") }
        }
    }

    private fun traceTokenResponse(response: HttpResponseData) {
        Logger.i(
            TAG,
            "token response: ${response.statusCode}" +
                    ", $DPOP_NONCE_HEADER=${presence(response.headers[DPOP_NONCE_HEADER])}" +
                    ", $ATTESTATION_CHALLENGE_HEADER=" +
                    presence(response.headers[ATTESTATION_CHALLENGE_HEADER])
        )
    }

    /** The claims a proof JWT asserts, with `challenge` called out since that is the one that bites. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun claimsOf(jwt: String): String = runCatching {
        val payload = Json.parseToJsonElement(
            Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
                .decode(jwt.split('.')[1])
                .decodeToString()
        ).jsonObject
        "claims=${payload.keys.sorted()}, " +
                "challenge=${if (payload.containsKey("challenge")) "present" else "ABSENT"}"
    }.getOrElse { "unparseable" }

    private fun presence(value: String?): String = if (value != null) "present" else "absent"

    private companion object {
        const val TAG = "OpenID4VciHttpClient"

        /** The issuer publishes more per claim than this reads; ignoring the rest is deliberate. */
        val LenientJson = Json { ignoreUnknownKeys = true }
        const val WELL_KNOWN = ".well-known"
        const val CONTENT_TYPE = "Content-Type"
        const val CONTENT_LENGTH = "Content-Length"
        const val ATTESTATION_CHALLENGE_HEADER = "OAuth-Client-Attestation-Challenge"
        const val ATTESTATION_POP_HEADER = "OAuth-Client-Attestation-PoP"
        const val DPOP_HEADER = "DPoP"
        const val DPOP_NONCE_HEADER = "DPoP-Nonce"

        /**
         * Handed to multipaz as a `DPoP-Nonce` purely so its retry fires; the value is never checked
         * by anything that matters.
         *
         * This authorization server does not use DPoP nonces — it sends none on any response, and
         * RFC 9449 has a server ignore a `nonce` claim it did not ask for — so the retried proof
         * carrying one costs nothing. It is confined to the token endpoint too: multipaz keeps the
         * issuer's nonce in a separate field, so the credential requests that follow are untouched.
         * If the server ever does start demanding nonces, it will send a real one and this is never
         * reached.
         */
        const val RETRY_ARMING_DPOP_NONCE = "retry"
    }
}
