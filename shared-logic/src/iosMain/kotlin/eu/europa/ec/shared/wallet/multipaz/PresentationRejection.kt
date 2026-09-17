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
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.util.Logger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Where to tell a verifier that the user said no, learned while multipaz fetches the request object.
 *
 * OpenID4VP's error response goes to the request's own `response_uri`, carrying its `state` — neither
 * of which multipaz exposes. They are in the signed request object, which multipaz fetches exactly
 * once, so this reads them **in passing** rather than fetching it again: a `request_uri` may legitimately
 * be single-use, and a second GET could invalidate the very exchange it is trying to be polite about.
 */
internal class PresentationRequestNotice {
    var responseUri: String? = null
        private set
    var state: String? = null
        private set

    /** True once a request object has been seen and it named somewhere to answer. */
    val canReject: Boolean get() = responseUri != null

    fun remember(responseUri: String?, state: String?) {
        if (responseUri.isNullOrBlank()) return
        this.responseUri = responseUri
        this.state = state
    }
}

/**
 * Wraps the transport multipaz uses for a presentation so the request object can be observed.
 *
 * The same shape as [OpenID4VciCompatibilityEngine] on the issuance side, and for the same reason:
 * the engine is the only place that sees what crosses the wire, and multipaz keeps the parsed request
 * to itself.
 */
internal class PresentationObservingEngineFactory(
    private val notice: PresentationRequestNotice,
    /**
     * How to build the engine underneath. A lambda rather than an [HttpClientEngineFactory] so a test
     * can substitute a `MockEngine`, whose config type is not Darwin's and could not otherwise satisfy
     * this factory's own signature.
     */
    private val delegate: (DarwinClientEngineConfig.() -> Unit) -> HttpClientEngine = { Darwin.create(it) },
) : HttpClientEngineFactory<DarwinClientEngineConfig> {

    override fun create(block: DarwinClientEngineConfig.() -> Unit): HttpClientEngine =
        PresentationObservingEngine(delegate(block), notice)
}

// `execute` carries `@InternalAPI` for everyone who implements an engine — the same opt-in
// `OpenID4VciCompatibilityEngine` takes, and for the same reason.
@OptIn(InternalAPI::class)
private class PresentationObservingEngine(
    private val delegate: HttpClientEngine,
    private val notice: PresentationRequestNotice,
) : HttpClientEngineBase("presentation-observer") {

    override val config: HttpClientEngineConfig get() = delegate.config

    override val supportedCapabilities get() = delegate.supportedCapabilities

    override fun close() {
        delegate.close()
        super.close()
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val response = delegate.execute(data)
        // The request object is the only GET in this flow that answers with a JWS, so the shape is
        // enough to spot it without matching on URLs the verifier is free to choose.
        if (data.method != HttpMethod.Get) return response
        val (bytes, replayable) = response.replayableBody()
        runCatching { bytes.decodeToString().rememberRequestObject(notice) }
        return replayable
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun String.rememberRequestObject(notice: PresentationRequestNotice) {
    val parts = trim().split('.')
    if (parts.size != 3) return
    val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        .decode(parts[1]).decodeToString()
    val claims = Json.parseToJsonElement(payload).jsonObject
    notice.remember(
        responseUri = claims["response_uri"]?.jsonPrimitive?.contentOrNull,
        state = claims["state"]?.jsonPrimitive?.contentOrNull,
    )
}

private suspend fun HttpResponseData.replayableBody(): Pair<ByteArray, HttpResponseData> {
    val bytes = (body as? ByteReadChannel)?.readRemaining()?.readByteArray() ?: ByteArray(0)
    return bytes to HttpResponseData(
        statusCode = statusCode,
        requestTime = requestTime,
        headers = headers,
        version = version,
        body = ByteReadChannel(bytes),
        callContext = callContext,
    )
}

/**
 * Tells the verifier the user declined, per OpenID4VP's error response.
 *
 * multipaz has **no** rejection mechanism — `access_denied` does not appear anywhere in its sources,
 * and `OpenID4VP.generateResponse` simply throws `PresentmentCanceledException` when consent comes back
 * null — so nothing was ever sent and the verifier's transaction waited until it timed out. Measured
 * against the dev verifier on 2026-09-16: approving logged a response, a hand-sent `access_denied` was
 * accepted with HTTP 200 and logged, and cancelling in the wallet logged nothing at all.
 *
 * Best effort by design: a user who declines has finished either way, so a failure here is logged and
 * swallowed rather than turned into an error they cannot act on.
 *
 * @return true if the verifier accepted the rejection.
 */
internal suspend fun sendPresentationRejection(
    notice: PresentationRequestNotice,
    httpClient: HttpClient,
): Boolean {
    val responseUri = notice.responseUri ?: run {
        Logger.i(TAG, "declined before a request object arrived; there is nobody to tell")
        return false
    }
    return runCatching {
        val response = httpClient.submitForm(
            url = responseUri,
            formParameters = Parameters.build {
                append("error", ACCESS_DENIED)
                notice.state?.let { append("state", it) }
            },
        )
        val accepted = response.status.isSuccess()
        Logger.i(
            TAG,
            "told the verifier the user declined: ${response.status}" +
                    if (accepted) "" else " ${response.bodyAsText().take(120)}"
        )
        accepted
    }.getOrElse {
        Logger.w(TAG, "could not tell the verifier: ${it::class.simpleName}: ${it.message}")
        false
    }
}

private const val TAG = "PresentationRejection"
private const val ACCESS_DENIED = "access_denied"
