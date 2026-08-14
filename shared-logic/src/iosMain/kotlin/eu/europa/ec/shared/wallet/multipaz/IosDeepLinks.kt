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

import org.multipaz.util.Logger

/**
 * The credential offer the app was opened with, waiting to be picked up — iOS's counterpart of Android's
 * cached launch intent.
 *
 * **A one-shot slot rather than a stream, and that shape is Android's.** There, a deep link arrives at the
 * activity, is cached, and the next screen to resume *consumes* it through `Context.getPendingUri()`; the
 * shared screens are already written against that, which is why they take a `pendingDeepLink: () -> String?`
 * they call once per resume. So iOS needs to offer exactly the same thing, and then the shared view-models
 * classify the link and decide where it leads — no iOS-specific routing at all.
 *
 * Distinct from [IosAuthorizationRedirects], which looks similar but is not: that one hands a redirect back
 * to a flow already waiting for it, whereas this one starts a flow that nothing is waiting for yet.
 *
 * Main-thread only, hence no locking: the app delegate delivers on the main thread and composition reads
 * on the main thread.
 */
object IosDeepLinks {

    private var pending: String? = null

    /**
     * Called by the app shell when a URL is opened on the app.
     *
     * @return true when the URL is a credential offer and was kept. Presentation links
     *   (`openid4vp://`, `mdoc-openid4vp://`) are declined for now: iOS has no presentation flow to give
     *   them to, and silently keeping one would leave it to be picked up by the wrong screen later.
     */
    fun deliver(url: String): Boolean {
        val isOffer = OFFER_SCHEMES.any { url.startsWith("$it:") }
        if (!isOffer) {
            Logger.i(TAG, "ignoring an opened URL that is not a credential offer")
            return false
        }
        pending = url
        Logger.i(TAG, "a credential offer is waiting to be opened")
        return true
    }

    /** Takes the waiting offer, if any, and forgets it — reading it is what consumes it, as on Android. */
    fun takePending(): String? = pending.also { pending = null }

    /** Drops anything waiting, so a new session cannot pick up an old offer. */
    fun clear() {
        pending = null
    }

    private const val TAG = "IosDeepLinks"

    /**
     * The schemes an offer arrives under, matching Android's `CREDENTIAL_OFFER_SCHEME` and
     * `CREDENTIAL_OFFER_HAIP_SCHEME`. These must also appear in the app's `CFBundleURLTypes`, or iOS never
     * hands the link over in the first place.
     */
    val OFFER_SCHEMES: List<String> = listOf("openid-credential-offer", "haip-vci")
}
