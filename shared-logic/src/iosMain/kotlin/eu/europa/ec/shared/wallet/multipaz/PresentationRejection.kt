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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
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
internal class PresentationRequestNotice(
    /** The `client_id` the link carried; the request object must repeat it. See [checkClientIdBinding]. */
    val linkClientId: String? = null,
) {
    var responseUri: String? = null
        private set
    var state: String? = null
        private set

    /**
     * The whole request object, kept because more than the rejection needs it.
     *
     * `verifier_info` — the verifier's registration certificate — is another claim multipaz does not
     * parse, and this is already the one place the signed request object is opened. Fetching it again
     * to read one more claim would risk the single-use `request_uri` for nothing.
     */
    var requestObject: JsonObject? = null
        private set

    /**
     * The chain that signed the request object, as its `x5c` carried it. The whole chain because naming
     * the verifier asks the trust lists about it, and those need more than the leaf.
     */
    var requestSignerChain: X509CertChain? = null
        private set

    /** The certificate that signed the request object; the registration must be bound to it. */
    val requestSigner: X509Cert? get() = requestSignerChain?.certificates?.firstOrNull()

    /**
     * What the verifier said when it refused the response, for the log.
     *
     * multipaz checks the answer's status with a bare `check(...)` and drops its body — as do the OpenID4VP
     * libraries of both official wallets — yet the verifier explains itself there: the EUDI verifier answers
     * `400 {"error", "description"}`. Logged rather than shown, because its wording is the verifier's own and
     * written for developers.
     */
    var verifierRefusal: String? = null
        private set

    /** True once a request object has been seen and it named somewhere to answer. */
    val canReject: Boolean get() = responseUri != null

    fun remember(responseUri: String?, state: String?) {
        if (responseUri.isNullOrBlank()) return
        this.responseUri = responseUri
        this.state = state
    }

    fun remember(requestObject: JsonObject, signerChain: X509CertChain?) {
        this.requestObject = requestObject
        this.requestSignerChain = signerChain
    }

    fun rememberRefusal(refusal: String) {
        verifierRefusal = refusal
    }
}

/**
 * A request object refused because nothing this wallet trusts vouches for the verifier that signed it.
 *
 * Android's openid4vp-kt refuses the same request (`Untrusted x5c`) and the app shows "Presentation
 * blocked"; the official iOS wallet does the same. The screen has its own words for it, so the message is
 * for the log only.
 *
 * Also what a proximity or DC API consent step throws for a reader that authenticated with a certificate
 * nothing vouches for ([isUntrustedReader]): multipaz lets it through unchanged, after ending the session.
 */
internal class UntrustedVerifierException : IllegalStateException(UNTRUSTED_VERIFIER)

private const val UNTRUSTED_VERIFIER = "the verifier's access certificate is not trusted"

/** That refusal, however the HTTP stack and multipaz have wrapped it on the way out. */
internal fun Throwable.isUntrustedVerifierRefusal(): Boolean =
    generateSequence(this) { it.cause }.any { it is UntrustedVerifierException }

/**
 * A request object refused because an `x509_hash` verifier did not carry exactly one registration
 * certificate while the user's registration check is on — openid4vp-kt's
 * `MissingRequiredRegistrationCertificate` and its two siblings, which Android answers with
 * `invalid_request` to the verifier and its generic error to the user.
 *
 * @param reason what was wrong with `verifier_info`, for the log; see [registrationCertificateRequirementFailure].
 */
internal class RegistrationCertificateRequiredException(reason: String) :
    IllegalStateException("an x509_hash verifier must send one registration certificate: $reason")

/** That refusal, however the HTTP stack and multipaz have wrapped it on the way out. */
internal fun Throwable.isRegistrationCertificateRefusal(): Boolean =
    generateSequence(this) { it.cause }.any { it is RegistrationCertificateRequiredException }

/**
 * Wraps the transport multipaz uses for a presentation so the request object can be observed — and
 * refused, when the certificate that signed it does not prove the verifier it names ([checkClientIdBinding]),
 * or when nothing this wallet trusts vouches for that certificate ([UntrustedVerifierException]).
 *
 * The same shape as [OpenID4VciCompatibilityEngine] on the issuance side, and for the same reason:
 * the engine is the only place that sees what crosses the wire, and multipaz keeps the parsed request
 * to itself.
 */
internal class PresentationObservingEngineFactory(
    private val notice: PresentationRequestNotice,
    /**
     * Whether a trusted list vouches for the certificate chain that signed the request object. No default:
     * a permissive one would turn the check off for any caller that forgot it.
     */
    private val isVerifierTrusted: suspend (X509CertChain) -> Boolean,
    /**
     * The user's registration check. While it is on, an `x509_hash` verifier must carry exactly one
     * registration certificate, as openid4vp-kt requires on Android — see
     * [registrationCertificateRequirementFailure]. Asked only for such a verifier, after it has proved who it is.
     */
    private val isRegistrationCheckEnabled: suspend () -> Boolean,
    /**
     * How to build the engine underneath. A lambda rather than an [HttpClientEngineFactory] so a test
     * can substitute a `MockEngine`, whose config type is not Darwin's and could not otherwise satisfy
     * this factory's own signature.
     */
    private val delegate: (DarwinClientEngineConfig.() -> Unit) -> HttpClientEngine = { Darwin.create(it) },
) : HttpClientEngineFactory<DarwinClientEngineConfig> {

    override fun create(block: DarwinClientEngineConfig.() -> Unit): HttpClientEngine =
        PresentationObservingEngine(delegate(block), notice, isVerifierTrusted, isRegistrationCheckEnabled)
}

// `execute` carries `@InternalAPI` for everyone who implements an engine — the same opt-in
// `OpenID4VciCompatibilityEngine` takes, and for the same reason.
@OptIn(InternalAPI::class)
private class PresentationObservingEngine(
    private val delegate: HttpClientEngine,
    private val notice: PresentationRequestNotice,
    private val isVerifierTrusted: suspend (X509CertChain) -> Boolean,
    private val isRegistrationCheckEnabled: suspend () -> Boolean,
) : HttpClientEngineBase("presentation-observer") {

    override val config: HttpClientEngineConfig get() = delegate.config

    override val supportedCapabilities get() = delegate.supportedCapabilities

    override fun close() {
        delegate.close()
        super.close()
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val response = delegate.execute(data)
        // Once the request object has named its `response_uri`, a refused POST is the verifier turning the
        // response down — the one answer whose body multipaz discards.
        if (data.method == HttpMethod.Post && notice.responseUri != null && !response.statusCode.isSuccess()) {
            val (bytes, replayable) = response.replayableBody()
            notice.rememberRefusal(refusalOf(response.statusCode, bytes.decodeToString()))
            return replayable
        }
        // Spotted the way multipaz accepts one — by its media type, whatever the method: with
        // `request_uri_method=post` the request object is the answer to a POST.
        val contentType = response.headers[HttpHeaders.ContentType]
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        if (contentType?.match(REQUEST_OBJECT) != true) return response
        val (bytes, replayable) = response.replayableBody()
        val compact = bytes.decodeToString().trim()
        val claims = runCatching { compact.jwsClaims() }.getOrNull() ?: return replayable
        val chain = jwsCertificateChain(compact)
        val signer = chain?.certificates?.firstOrNull()
        // Before multipaz reads it, and before the notice learns its `response_uri`: a request that does
        // not prove who sent it is never put to the user, and never answered either.
        checkClientIdBinding(notice.linkClientId, claims, signer)
        // Then whether anyone vouches for that signer — where Android refuses too, before anything is matched
        // against the wallet or asked of the user. An undeterminable verdict refuses as well (fails closed).
        if (chain == null || !isVerifierTrusted(chain)) {
            Logger.w(TAG, "refusing the request object: $UNTRUSTED_VERIFIER")
            throw UntrustedVerifierException()
        }
        notice.remember(
            responseUri = claims["response_uri"]?.jsonPrimitive?.contentOrNull,
            state = claims["state"]?.jsonPrimitive?.contentOrNull,
        )
        notice.remember(requestObject = claims, signerChain = chain)
        // Last, as in openid4vp-kt, where this policy runs on a request that has already resolved: the
        // verifier is authenticated and trusted, so it is told why — the notice now knows where to answer.
        val clientId = claims["client_id"]?.jsonPrimitive?.contentOrNull
        if (clientId?.startsWith(X509_HASH_PREFIX) == true && isRegistrationCheckEnabled()) {
            registrationCertificateRequirementFailure(claims)?.let { reason ->
                Logger.w(TAG, "refusing the request object: an x509_hash verifier sent $reason")
                throw RegistrationCertificateRequiredException(reason)
            }
        }
        return replayable
    }
}

private const val X509_HASH_PREFIX = "x509_hash:"

@OptIn(ExperimentalEncodingApi::class)
private fun String.jwsClaims(): JsonObject {
    val parts = split('.')
    require(parts.size == 3) { "not a compact JWS" }
    val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        .decode(parts[1]).decodeToString()
    return Json.parseToJsonElement(payload).jsonObject
}

private val REQUEST_OBJECT = ContentType("application", "oauth-authz-req+jwt")

/** `400 Bad Request InvalidVpToken: vp_token is not valid: …`, or the body itself when it is not that shape. */
private fun refusalOf(status: HttpStatusCode, body: String): String {
    val fields = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    fun field(name: String) = (fields?.get(name) as? JsonPrimitive)?.contentOrNull
    val said = listOfNotNull(field("error"), field("description")).joinToString(": ").ifEmpty { body.trim() }
    return "$status $said".take(MAX_REFUSAL_LENGTH)
}

/** A verifier's description can quote a whole document's validation; the log needs the start of it. */
private const val MAX_REFUSAL_LENGTH = 500

private suspend fun HttpResponseData.replayableBody(): Pair<ByteArray, HttpResponseData> {
    val bytes = (body as? ByteReadChannel)?.readBuffer()?.readByteArray() ?: ByteArray(0)
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
 * Also how a request the wallet refuses on its own account is answered — [INVALID_REQUEST] for an
 * `x509_hash` verifier without its registration certificate, as openid4vp-kt dispatches it on Android.
 *
 * @param error the OpenID4VP error code; [ACCESS_DENIED] is the user's "no".
 * @return true if the verifier accepted the rejection.
 */
internal suspend fun sendPresentationRejection(
    notice: PresentationRequestNotice,
    httpClient: HttpClient,
    error: String = ACCESS_DENIED,
): Boolean {
    val responseUri = notice.responseUri ?: run {
        Logger.i(TAG, "refused before a request object arrived; there is nobody to tell")
        return false
    }
    return runCatching {
        val response = httpClient.submitForm(
            url = responseUri,
            formParameters = Parameters.build {
                append("error", error)
                notice.state?.let { append("state", it) }
            },
        )
        val accepted = response.status.isSuccess()
        Logger.i(
            TAG,
            "told the verifier $error: ${response.status}" +
                    if (accepted) "" else " ${response.bodyAsText().take(120)}"
        )
        accepted
    }.getOrElse {
        Logger.w(TAG, "could not tell the verifier: ${it::class.simpleName}: ${it.message}")
        false
    }
}

private const val TAG = "PresentationRejection"
internal const val ACCESS_DENIED = "access_denied"

/** What openid4vp-kt dispatches for a request that fails a wallet policy (`AuthorizationPolicyValidationError`). */
internal const val INVALID_REQUEST = "invalid_request"
