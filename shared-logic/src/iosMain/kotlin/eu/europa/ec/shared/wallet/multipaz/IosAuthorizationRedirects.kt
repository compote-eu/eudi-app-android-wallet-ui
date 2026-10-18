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

import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.shared.platform.IosBroadcasts
import eu.europa.ec.shared.platform.PlatformIntent
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import org.multipaz.util.Logger
import kotlin.time.Duration

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

    private val lock = reentrantLock()

    /** How many [await] calls are in progress. Written by the flow, read by the app shell's thread. */
    private var waiters = 0

    /** The coroutine of the newest [await], so a newer one can end it. Guarded by [lock]. */
    private var newest: Job? = null

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
        // Decided before queuing: the queued redirect wakes the waiting flow, which can take it and stop
        // waiting on its own thread before the next line runs here.
        val awaited = lock.withLock { waiters } > 0
        // trySend cannot fail on a conflated channel, but the result is checked rather than assumed.
        val queued = redirects.trySend(url).isSuccess
        Logger.i(TAG, "authorization redirect ${if (queued) "queued" else "dropped"}")
        if (queued) announceResume(url, awaited)
        return queued
    }

    /**
     * Tells the screen that started the flow that it has resumed — what Android's activity broadcasts
     * as [CoreActions.VCI_RESUME_ACTION], and what turns the screen's spinner back on after the browser
     * turned it off.
     *
     * Only while a flow is waiting. A redirect nobody awaits is not followed by any work, so a spinner
     * it switched on would never be switched off again.
     */
    private fun announceResume(url: String, awaited: Boolean) {
        if (!awaited) {
            Logger.i(TAG, "no flow is waiting for the redirect; resume not announced")
            return
        }
        val sent = IosBroadcasts.send(
            PlatformIntent(
                action = CoreActions.VCI_RESUME_ACTION,
                stringExtras = mapOf(RESUME_URI_EXTRA to url),
            )
        )
        Logger.i(TAG, "resume ${if (sent) "announced" else "not announced"} to the screen")
    }

    /**
     * Waits for the next authorization redirect, by default for as long as the caller lives, as Android's
     * wallet-core does: the wait ends when the redirect arrives or when the issuance waiting for it is
     * cancelled, as it is when its screen goes away. A login may take as long as the issuer allows.
     *
     * A newer call ends one still waiting by cancelling its coroutine, as wallet-core's
     * `BrowserAuthorizationHandler` cancels the previous authorization when a new one starts. Otherwise
     * an issuance the user abandoned in the browser would still be waiting, and could take the code meant
     * for the next one.
     *
     * @param timeout for tests; null is returned if it passes first.
     */
    suspend fun await(timeout: Duration = Duration.INFINITE): String? {
        val caller = currentCoroutineContext()[Job]
        val previous = lock.withLock { newest.also { newest = caller } }
        if (previous != null && previous !== caller && previous.isActive) {
            Logger.i(TAG, "a newer authorization ends the one still waiting")
            previous.cancel(CancellationException("superseded by a newer authorization"))
        }
        val waiting = lock.withLock { ++waiters }
        Logger.i(TAG, "waiting for the authorization redirect (waiters=$waiting)")
        var redirect: String? = null
        try {
            redirect = withTimeoutOrNull(timeout) { redirects.receive() }
            return redirect
        } finally {
            val left = lock.withLock {
                if (newest === caller) newest = null
                --waiters
            }
            Logger.i(TAG, "stopped waiting: ${if (redirect != null) "received" else "none"} (waiters=$left)")
        }
    }

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

    /** The extra the shared screens read the redirect from, as Android's `DeepLinkHelper` names it. */
    internal const val RESUME_URI_EXTRA = "uri"
}
