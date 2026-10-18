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

package eu.europa.ec.shared.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import eu.europa.ec.corelogic.model.RevokedDocumentDataDomain
import eu.europa.ec.dashboardfeature.ui.dashboard.PendingLaunchIntent
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.uilogic.navigation.helper.IntentAction

/**
 * The platform-shaped behaviour the shared `entry<Route> { }` blocks need.
 *
 * Every shared screen already takes its platform behaviour as an injected lambda with a default; this
 * is where those lambdas get their answers, so the entries themselves can live in commonMain. Without
 * it they could not: the Android answers are `Context.cacheUri`, `handleDeepLinkAction`,
 * `findActivity()` and the revocation broadcast's `Parcelable` extra, which live in `:ui-logic` and
 * `:core-logic` — and **both of those depend on `:shared-ui`**, so commonMain can never call them.
 * Inverting that with an interface here, implemented there and supplied through
 * [LocalNavPlatformActions], is what makes the direction work.
 *
 * Every member defaults to doing nothing, which is deliberate: it is exactly what iOS wants for the
 * hooks it has no answer for, and it means adding a member does not break either platform.
 *
 * 🪤 The members are shaped after the *hooks the screens ask for*, not after primitives like
 * "cache a uri". That is on purpose. [parkAndReturn] returning a Boolean lets Android do
 * cache-then-pop while iOS declines and falls through to [openDeepLink] — which is how this
 * refactor kept both platforms' observable behaviour identical instead of giving iOS Android's.
 */
interface NavPlatformActions {

    /** A deep link the app was launched with and has not consumed yet. */
    fun pendingDeepLink(): String? = null

    /** What the app was launched with: a credential offer link, or an app action such as DC API. */
    fun pendingLaunchIntent(): PendingLaunchIntent = PendingLaunchIntent()

    /** Reads the one-shot intent action the request screen was pushed for, clearing it. */
    fun consumePendingIntentAction(): IntentAction? = null

    /** The revoked ids carried by a revocation broadcast, or null if this platform has none. */
    fun revokedDocumentsFromBroadcast(intent: PlatformIntent): List<RevokedDocumentDataDomain>? = null

    /** Ends the flow by handing [intent] back to whoever invoked the wallet — the DC API return. */
    fun finishWithResult(intent: PlatformIntent) {}

    /**
     * Parks [link] so [routeToPop] can consume it, then returns there. Answers whether it did; a
     * platform with nowhere to park returns false and the caller falls back to [openDeepLink].
     *
     * @param isPreAuthorization replaces the current entry rather than popping back to it, because a
     *   pre-authorization hand-off must not leave the screen it came from on the stack.
     */
    fun parkAndReturn(
        navigator: AppNavigator,
        link: String,
        routeToPop: AppRoute?,
        isPreAuthorization: Boolean = false,
    ): Boolean = false

    /** Follows [link] now, to [route] when the caller already resolved where it leads. */
    fun openDeepLink(navigator: AppNavigator, link: String, route: AppRoute? = null) {}

    /**
     * Bumped when a link arrives while the app is **already open and resumed**, so the dashboard can
     * read it there and then. Android returns a constant: it has no need of this, because caching the
     * intent is paired with a `popToDashboardScreen()` that performs the read by itself.
     *
     * Composable because the platform's answer is observable state, and the caller has to recompose
     * when it changes — that recomposition is the whole mechanism.
     */
    @Composable
    fun rememberPendingLaunchRetrigger(): Int = 0

    /** Follows an app [action] — DC API on Android, nothing anywhere else. */
    fun openIntentAction(navigator: AppNavigator, action: IntentAction, route: AppRoute?) {}
}

/** Does nothing, for a platform that has no answer for any of it — and for previews and tests. */
object NoNavPlatformActions : NavPlatformActions

/**
 * Supplied by each platform's host: Android from `EudiComponentActivity.Content`, where the `:ui-logic`
 * helpers are in scope, and iOS from `IosAppRoot`.
 *
 * `static` because it is set once per host and never changes, so readers should not recompose for it.
 */
val LocalNavPlatformActions = staticCompositionLocalOf<NavPlatformActions> { NoNavPlatformActions }
