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

package eu.europa.ec.shared.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import eu.europa.ec.dashboardfeature.ui.dashboard.PendingLaunchIntent
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.NavPlatformActions
import eu.europa.ec.shared.wallet.multipaz.IosDeepLinks
import eu.europa.ec.uilogic.component.openIosUrlExternally
import eu.europa.ec.uilogic.navigation.helper.navigateReplacingCurrent
import eu.europa.ec.uilogic.navigation.helper.popBackStackTo
import eu.europa.ec.uilogic.navigation.helper.navigateToRoute

/**
 * iOS's answers for the shared `entry<Route> { }` blocks.
 *
 * Only two of the hooks have an answer here, and they are the two the iOS entries already supplied
 * before the entries were shared: the pending universal link, and following one to the route the
 * shared view-model resolved it to.
 *
 * Everything else keeps [NavPlatformActions]'s do-nothing default, which is a statement rather than a
 * gap: [parkAndReturn] returns false because there is nowhere on iOS to park a link for a screen to
 * pick up later, so the shared entries fall through to [openDeepLink] — and with no route to go to
 * that does nothing, exactly as iOS's own `onExternalDeepLink` lambdas did. DC API intent actions,
 * the revocation broadcast and finishing with a result do not exist on this platform at all.
 *
 * ⚠️ One deliberate change came with sharing the entries: `pendingDeepLink()` now answers for the
 * document-details and credential-offer screens too, where iOS previously passed nothing. Android
 * has always resumed an interrupted flow from that slot, so this makes iOS match rather than
 * inventing behaviour — but it does mean those screens now consume `IosDeepLinks.takePending()`,
 * which is one-shot. If a link ever appears to go missing on iOS, this is the first place to look.
 */
object IosNavPlatformActions : NavPlatformActions {

    /**
     * Bumped by [IosDeepLinks] on every delivery. Lives here rather than in `IosDeepLinks` because
     * `:shared-logic` has no Compose on its classpath, and turning a callback into something
     * composition can watch is a UI-layer concern.
     */
    private val retrigger = mutableIntStateOf(0)

    init {
        IosDeepLinks.setOnDelivered { retrigger.value += 1 }
    }

    @Composable
    override fun rememberPendingLaunchRetrigger(): Int = retrigger.value

    override fun pendingDeepLink(): String? = IosDeepLinks.takePending()

    override fun pendingLaunchIntent(): PendingLaunchIntent =
        PendingLaunchIntent(deepLink = IosDeepLinks.takePending())

    override fun openDeepLink(navigator: AppNavigator, link: String, route: AppRoute?) {
        // The shared view-model already decided where the link leads; there is no iOS-side routing.
        route?.let { navigator.navigateToRoute(it) } ?: run {
            // With no route the link is not a destination, and one kind is still actionable: a
            // browser link, which Android opens (`DeepLinkHelper`,
            // `DeepLinkType.EXTERNAL -> context.openUrl`). Android's other routeless branches have no
            // iOS counterpart to mirror — the two broadcasts do not exist here, and the RQES ones are
            // claimed by `WalletDocumentSigner.resume` in Swift before a link reaches Kotlin. So
            // anything else keeps doing nothing, as before.
            if (link.isBrowserLink()) openIosUrlExternally(link)
        }
    }

    /**
     * Follows [link] and returns to [routeToPop] — iOS's answer to a contract written for Android.
     *
     * 🚨 **This used to return false, and that is what stopped wallet-centric signing on iOS.** After a
     * presentation the verifier hands back a `redirect_uri`, and opening it is how the *browser* session
     * that asked for the presentation learns it succeeded. Android follows it —
     * `DeepLinkHelper.handleDeepLinkAction` has `DeepLinkType.EXTERNAL -> context.openUrl(...)`. iOS
     * returned false here, so `entry<PresentationSuccessRoute>` fell through to a bare `navigator.pop()`:
     * the redirect was **discarded**, the QTSP's page waited for ever, and the pop landed the user back
     * on the **stale Data sharing request screen** it had pushed from. Measured on an iPhone 2026-09-08.
     *
     * 📌 The name still fits even though nothing is parked. Parking exists on Android because the link
     * has to survive a trip through the activity before a screen reads it; an external link needs no
     * such slot on either platform — it needs following, which is all Android does with it too.
     *
     * Only browser links are claimed. Anything else keeps the old `false`, because those DO want a park
     * slot iOS has not got, and the callers have their own fallbacks for it.
     */
    override fun parkAndReturn(
        navigator: AppNavigator,
        link: String,
        routeToPop: AppRoute?,
        isPreAuthorization: Boolean,
    ): Boolean {
        val destination = routeToPop ?: return false
        if (!link.isBrowserLink()) return false

        openIosUrlExternally(link)
        if (isPreAuthorization) {
            // A pre-authorization hand-off must not leave the screen it came from on the stack — the
            // same reason Android replaces rather than pops.
            navigator.navigateReplacingCurrent(destination)
        } else {
            navigator.popBackStackTo(route = destination, inclusive = false)
        }
        return true
    }
}

/**
 * Whether [this] is a link a browser owns, and therefore one iOS can be handed.
 *
 * 🪤 **Not `IosDeepLinkClassifier`, deliberately.** Android reaches the same decision through
 * `DeepLinkType.parse`, whose `else -> EXTERNAL` makes *every* unrecognised link external — safe there
 * because its own schemes are all enumerated first. The iOS classifier instead returns **null** for
 * anything it has no flow for, on purpose (see its KDoc), and `IosDeepLinkClassifierTest` pins that:
 * `classify("https://example.test/anything")` is null. Gating on `EXTERNAL` here would therefore have
 * matched nothing at all — the first version of this fix was exactly that no-op.
 *
 * Widening the classifier's fallback to `EXTERNAL` would align the two platforms, but it would also
 * hand our *own* unrouted schemes — `rqes:`, the authorization redirect — to the browser, which bounces
 * them straight back at us. So the narrow question is asked instead, and it is the honest one: this app
 * registers custom schemes only and claims no associated domains (`project.yml`), so an `http(s)` URL
 * can never resolve back to the wallet. It always leaves.
 */
internal fun String.isBrowserLink(): Boolean =
    substringBefore(':', missingDelimiterValue = "").lowercase() in setOf("http", "https")
