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

    private var onDelivered: (() -> Unit)? = null

    /**
     * Registers what to do the moment a link is kept, or clears it with null.
     *
     * Storing the link is not enough on its own: the shared screens read it from a
     * `LifecycleEffect(ON_RESUME)`, so a link arriving while the wallet is **already open and resumed**
     * was stored and then never read — nothing resumed, so nobody asked. Android has no such hole
     * because `EudiComponentActivity.handleDeepLink` caches the intent *and* calls
     * `popToDashboardScreen()`; the recomposition that causes is what performs the read.
     *
     * A plain callback rather than observable state because this module has no Compose on its
     * classpath, and should not: turning it into something composition can watch is
     * `IosNavPlatformActions`' job, one layer up.
     *
     * 🪤 Re-entering the destination is NOT a usable substitute, and it was tried: navigating to
     * `DashboardRoute` with `popUpTo(inclusive = true)` re-adds the **same** `NavKey`, so Nav3 keeps the
     * existing entry rather than building a new one, no fresh composition happens and `ON_RESUME` never
     * fires again. Only an explicit signal works.
     */
    fun setOnDelivered(listener: (() -> Unit)?) {
        onDelivered = listener
    }

    /**
     * Called by the app shell when a URL is opened on the app.
     *
     * @return true when the URL is one iOS has a flow for — a credential offer or a verifier's
     *   presentation request — and was kept. Anything else is declined rather than silently kept, since
     *   a link nothing will consume would be picked up by the wrong screen later.
     */
    fun deliver(url: String): Boolean {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        val kind = when (scheme) {
            in OFFER_SCHEMES -> "a credential offer"
            in PRESENTATION_SCHEMES -> "a presentation request"
            else -> {
                Logger.i(TAG, "ignoring an opened URL this app has no flow for")
                return false
            }
        }
        pending = url
        Logger.i(TAG, "$kind is waiting to be opened")
        // After storing, never before: whoever reacts to this will immediately read `pending`.
        onDelivered?.invoke()
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

    /**
     * The schemes a verifier's request arrives under, matching Android's four `*_OPENID4VP_SCHEME`
     * build-config values. All four are registered because the wallet does not choose: the *verifier*
     * builds the link, and the EUDI dev verifier emits `haip-vp://` by default while its OpenID4VP
     * profile emits `openid4vp://`. Registering only one would make the other simply not open the app.
     *
     * These must also appear in the app's `CFBundleURLTypes`, or iOS never hands the link over.
     */
    val PRESENTATION_SCHEMES: List<String> =
        listOf("openid4vp", "eudi-openid4vp", "mdoc-openid4vp", "haip-vp")
}
