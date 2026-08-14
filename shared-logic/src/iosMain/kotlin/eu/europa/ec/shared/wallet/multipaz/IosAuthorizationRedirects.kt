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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.multipaz.util.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The OAuth redirect hand-off between the iOS app shell and the provisioning flow.
 *
 * OpenID4VCI authorization happens in a browser, and the authorization server sends the result to the
 * URI registered for this client — `eu.europa.ec.euidi://authorization`, the same value Android
 * configures as `ISSUE_AUTHORIZATION_DEEPLINK`. On iOS that arrives as a URL opened on the app, which
 * only the Swift shell sees; the coroutine waiting for it is in Kotlin. This is the queue between them.
 *
 * **Why a queue rather than a callback the host registers:** the redirect can arrive before the flow is
 * ready to receive it (the app is relaunched by the URL open), so the value has to be able to wait. A
 * conflated channel keeps only the newest, which is right — an older redirect carries a spent
 * authorization code.
 *
 * Also note what is *not* here: nothing decides whether the redirect is trustworthy. The `state`
 * parameter is checked by multipaz's provisioning client, which minted it, and that is the correct place
 * — a redirect for a different session is rejected there rather than by pattern-matching URLs here.
 */
object IosAuthorizationRedirects {

    private val redirects = Channel<String>(Channel.CONFLATED)

    /**
     * Called by the app shell when a URL is opened on the app. Safe to call from any thread, and safe
     * to call when nothing is waiting.
     *
     * @return true if the URL looked like an authorization redirect and was queued.
     */
    fun deliver(url: String): Boolean {
        if (!url.startsWith(REDIRECT_PREFIX)) {
            Logger.i(TAG, "ignoring an opened URL that is not an authorization redirect")
            return false
        }
        // trySend cannot fail on a conflated channel, but the result is checked rather than assumed.
        val queued = redirects.trySend(url).isSuccess
        Logger.i(TAG, "authorization redirect ${if (queued) "queued" else "dropped"}")
        return queued
    }

    /**
     * Waits for the next authorization redirect, or null if none arrives within [timeout].
     *
     * The default allows for a human logging in. Note that the *server's* patience is shorter: the PAR
     * `request_uri` these redirects belong to expires about a minute after it is issued, so a slow login
     * fails at the token endpoint rather than here.
     */
    suspend fun await(timeout: Duration = DEFAULT_TIMEOUT): String? =
        withTimeoutOrNull(timeout) { redirects.receive() }

    /** Drops any queued redirect, so a new session cannot pick up an old one. */
    fun clear() {
        while (redirects.tryReceive().isSuccess) {
            // Conflated, so at most one — the loop is for clarity, not necessity.
        }
    }

    private const val TAG = "IosAuthorizationRedirects"

    /**
     * The scheme registered for this client. Kept in one place because two things must agree on it: the
     * `redirectUrl` sent in the authorization request, and the `CFBundleURLTypes` entry in the app's
     * Info.plist that makes iOS hand the redirect to us at all.
     */
    const val REDIRECT_PREFIX = "eu.europa.ec.euidi://authorization"

    private val DEFAULT_TIMEOUT = 180.seconds
}
