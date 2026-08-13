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
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.InternalAPI
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val response = try {
            delegate.execute(data)
        } catch (t: Throwable) {
            if (isOptional(data)) {
                // A logo, a card art, some display extra. multipaz checks the status, so hand it one.
                // Keep the log short: Darwin's DNS errors are a screenful each.
                Logger.w(TAG, "optional GET ${data.url} failed (${t::class.simpleName}); reporting 502")
                return emptyResponse(HttpStatusCode.BadGateway)
            }
            throw t
        }

        return if (looksLikeSignedMetadata(data, response)) unwrapSignedMetadata(data, response)
        else response
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

    private fun looksLikeSignedMetadata(
        data: HttpRequestData,
        response: HttpResponseData,
    ): Boolean = response.statusCode == HttpStatusCode.OK &&
            data.url.encodedPath.contains(WELL_KNOWN) &&
            response.headers[CONTENT_TYPE]?.contains("jwt", ignoreCase = true) == true

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
    }
}
