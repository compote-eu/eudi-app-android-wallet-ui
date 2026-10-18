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

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.encodedPath
import org.multipaz.util.Logger
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost

/**
 * Retries a request **once** when it fails with `NSURLErrorNetworkConnectionLost` (`-1005`), and for nothing
 * else — not a timeout, not a server error, not any other transport failure.
 *
 * iOS suspends the wallet while the user is in the browser or in the eIDENTITA app, and may reclaim its
 * sockets meanwhile (Apple, TN2277). The first request on resume then reuses a dead connection and fails at
 * once. Measured on the iPhone against Plaut's issuer on 2026-09-28: 3 of 7 returns from eIDENTITA failed this
 * way, each 35–40 ms after the redirect arrived, on the wallet provider's attestation request — the first one
 * made on the way back, to the host the wallet had last talked to. `URLSession` retries an idempotent request
 * itself, but never a POST, and every request in these flows is one. The Android build was never seen to fail
 * here: its stack opens a fresh connection instead.
 *
 * Safe for every request in the OpenID4VCI flows: one the server never received is simply made once, and one
 * it did receive is refused on replay — a spent authorization code, a reused nonce — which is how it fails
 * today anyway. The request is sent again as it was, so its body must be replayable; everything here sends a
 * byte-array or text body.
 */
internal fun HttpClientConfig<*>.retryOnceWhenConnectionLost() {
    install(HttpRequestRetry) {
        // A response is never retried: an issuer's error is its answer.
        retryIf(maxRetries = 1) { _, _ -> false }
        retryOnExceptionIf(maxRetries = 1) { _, cause -> cause.isConnectionLost() }
        // A dead socket needs no back-off; the retry opens a new connection.
        delayMillis(respectRetryAfterHeader = false) { 0L }
        modifyRequest { request ->
            Logger.i(TAG, "retrying ${request.url.encodedPath} once: the connection was lost (-1005)")
        }
    }
}

/** Whether this failure, or one it wraps, is `NSURLErrorNetworkConnectionLost`. */
internal fun Throwable.isConnectionLost(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { failure ->
        val error = (failure as? DarwinHttpRequestException)?.origin ?: return@any false
        error.domain == NSURLErrorDomain && error.code == NSURLErrorNetworkConnectionLost
    }

private const val TAG = "ConnectionLostRetry"
private const val MAX_CAUSE_DEPTH = 8
