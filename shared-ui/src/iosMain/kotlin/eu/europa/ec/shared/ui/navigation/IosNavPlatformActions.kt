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
        // The shared view-model already decided where the link leads; there is no iOS-side parsing.
        route?.let { navigator.navigateToRoute(it) }
    }
}
