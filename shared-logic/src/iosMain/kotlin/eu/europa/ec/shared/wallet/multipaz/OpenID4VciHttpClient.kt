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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.util.Logger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
): HttpClient =
    HttpClient(OpenID4VciCompatibilityEngine(engine)) {
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

    /** `credential_configuration_id` → OAuth `scope`, from the credential issuer's own metadata. */
    private var scopesByConfigurationId: Map<String, String> = emptyMap()

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val request =
            if (isPushedAuthorizationRequest(data)) withScopeInsteadOfAuthorizationDetails(data)
            else data

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

        return when {
            isWellKnown(data) -> rememberEndpoints(data, response)
            isPushedAuthorizationRequest(data) -> injectFreshAttestationChallenge(response)
            else -> response
        }
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
            json["credential_configurations_supported"]?.jsonObject?.let { configurations ->
                scopesByConfigurationId = configurations.mapNotNull { (id, configuration) ->
                    configuration.jsonObject["scope"]?.jsonPrimitive?.content?.let { id to it }
                }.toMap()
                Logger.i(TAG, "learned ${scopesByConfigurationId.size} credential scopes")
            }
        }

        return unwrapped.replacingBody(bytes, asJson = true)
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
        return HttpResponseData(
            statusCode = response.statusCode,
            requestTime = response.requestTime,
            headers = HeadersBuilder().apply {
                appendAll(response.headers)
                append(ATTESTATION_CHALLENGE_HEADER, challenge)
            }.build(),
            version = response.version,
            // Untouched: this path never reads the body, so it can be passed straight through.
            body = response.body,
            callContext = response.callContext,
        )
    }

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

    private companion object {
        const val TAG = "OpenID4VciHttpClient"
        const val WELL_KNOWN = ".well-known"
        const val CONTENT_TYPE = "Content-Type"
        const val CONTENT_LENGTH = "Content-Length"
        const val ATTESTATION_CHALLENGE_HEADER = "OAuth-Client-Attestation-Challenge"
    }
}
