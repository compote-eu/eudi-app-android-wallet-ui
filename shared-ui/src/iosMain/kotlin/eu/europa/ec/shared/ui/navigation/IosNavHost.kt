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

// The iOS end of the navigation host. It no longer contains a host: the `NavDisplay`, the entry
// decorators and the screen-reporting effect all live in `AppNavDisplay` in commonMain, which Android
// composes too. What is left here is the back stack, the analytics lookup and the missing-screen
// fallback — the three things the two platforms genuinely do differently.
//
// This file used to be a near copy of `RouterHostImpl`. That was already the good outcome, because it
// meant transitions, predictive back and the decorators were maintained upstream rather than by us;
// sharing the body removes the copy as well. It is possible because androidx publishes
// `navigation3-runtime` for iOS but NOT `navigation3-ui` (probed: "Could not resolve
// androidx.navigation3:navigation3-ui" for iosSimulatorArm64), while JetBrains' fork of
// `navigation3-ui` publishes both native targets AND an Android variant that depends on androidx's —
// so one commonMain dependency serves both platforms and Android still runs Google's code.
//
// Still deliberately absent: state restoration across process death. That needs
// `rememberNavBackStack`, whose non-Android overload `require`s a `SavedStateConfiguration` carrying a
// `serializersModule` with `polymorphic(NavKey::class) { … }` registered for the route hierarchy. A
// plain `mutableStateListOf` is used until iOS actually needs restoration — which is exactly why
// `AppNavDisplay` takes the back stack rather than creating it.
package eu.europa.ec.shared.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.shared.navigation.AppNavDisplay
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.analyticslogic.controller.AnalyticsLogger
import org.koin.mp.KoinPlatform

/**
 * Hosts a back stack of [NavKey] destinations.
 *
 * [entries] has the same shape the Android host takes — `EntryProviderScope<NavKey>.(AppNavigator) ->
 * Unit` — so a feature contributes its destinations identically on both platforms once its screens are
 * shared. Compare `featureStartupEntries` in :startup-feature.
 *
 * @param startRoute the destination the stack starts on.
 */
@Composable
fun IosNavHost(
    startRoute: NavKey,
    entries: EntryProviderScope<NavKey>.(AppNavigator) -> Unit,
) {
    val backStack = remember { mutableStateListOf(startRoute) }
    val navigator = remember(backStack) { AppNavigator(backStack) }
    IosNavHost(backStack = backStack, navigator = navigator, entries = entries)
}

/**
 * Overload for callers that own the back stack and [AppNavigator] — the iOS counterpart of Android's
 * `RouterHost`, which publishes its navigator so code outside composition (deep links) can drive it.
 */
@Composable
fun IosNavHost(
    backStack: MutableList<NavKey>,
    navigator: AppNavigator,
    entries: EntryProviderScope<NavKey>.(AppNavigator) -> Unit,
) {
    // The host body is shared with Android — see `AppNavDisplay`. Only four things are iOS's own:
    // resolving the analytics logger from Koin (Android injects it into `RouterHostImpl`), leaving
    // `rootOwner` to default because Compose on iOS does not necessarily install one the way an
    // Android Activity does, the fallback below, and the deep-link pop — which on Android is the
    // activity's job.
    val analytics = remember { KoinPlatform.getKoin().get<AnalyticsLogger>() }

    PopToDashboardOnDeepLink(navigator)

    AppNavDisplay(
        backStack = backStack,
        navigator = navigator,
        analytics = analytics,
        // Without this, a route with no iOS entry *throws* — `entryProvider`'s default fallback does.
        // That matters now that shared screens navigate: the dashboard's side menu offers Change PIN,
        // whose screen is still Android-only, so a tap would have crashed the app rather than shown
        // anything. A named placeholder is both survivable and more useful than a stack trace while
        // the port is in progress.
        fallback = { unknownRoute ->
            NavEntry(key = unknownRoute) { MissingIosScreen(route = unknownRoute) }
        },
        entries = entries,
    )
}

/**
 * Brings a freshly delivered deep link into view — iOS's missing half of Android's
 * `popToDashboardScreen()`.
 *
 * `IosDeepLinks.deliver` stores the link and bumps `IosNavPlatformActions`' retrigger, but the only
 * reader of that retrigger is `entry<DashboardRoute>` (see `SharedEntries`), and `NavDisplay` composes
 * **only the top entry**. So a link arriving with any other route on top was stored and then never
 * read: nobody was composed to ask for it. That is how a verifier's `haip-vp://` request went missing
 * while the *Sign document* screen was up — `DocumentSignRoute`'s screen has no deep-link hook at all
 * — leaving the whole wallet-centric signing journey unable to finish on iOS.
 *
 * This lives in the host rather than in an entry precisely because the host is composed for the app's
 * life whatever is on top, and it holds the [navigator] the pop needs.
 *
 * 🪤 The 🪤 in `IosDeepLinks.setOnDelivered` does **not** apply: it says re-*adding* `DashboardRoute`
 * keeps the existing `NavKey` so nothing recomposes, which is true — and why the dashboard is skipped
 * here when it is already on top. That case is already covered by the dashboard's own
 * `LaunchedEffect(pendingLaunchRetrigger)`. The two are complementary, and because that same effect
 * runs on the dashboard's *first* composition whenever the retrigger is non-zero, the read after this
 * pop does not depend on `ON_RESUME` firing again.
 *
 * 📌 **No exemption for the screens that read a link themselves**, though Android has one. There the
 * activity dispatches an OPENID4VP link *in place* when `AddDocumentRoute`, `DocumentOfferRoute` or
 * `DocumentDetailsRoute` is up. Our shared view-models cannot: `DocumentDetailsViewModel` and
 * `DocumentOfferViewModel` act on `EXTERNAL` only, and `AddDocumentViewModel` on `CREDENTIAL_OFFER` and
 * `EXTERNAL` with `else -> {}`. A presentation request handed to any of them is **consumed and
 * dropped**, `takePending()` being one-shot. Popping is therefore not a coarser rule than Android's
 * here, it is the only one that delivers the link — and it closes that silent drop as well.
 *
 * A no-op when `DashboardRoute` is not on the stack at all, which is `popUpTo`'s own answer and exactly
 * Android's gate: `userIsLoggedInWithDocuments()` is itself just
 * `isRouteOnBackStackOrForeground(dashboardRoute)`. A link that arrives during onboarding stays pending
 * for the cold-boot read, as before.
 *
 * ✅ Measured A/B on an iPhone SE (3rd gen), iOS 26.6.1, both legs driven entirely from the CLI with
 * `devicectl device process launch --payload-url` — which delivers to the *running* instance, so an
 * offer link puts `DocumentOfferRoute` on top and a following `haip-vp://` link is the test. With this
 * effect the presentation flow is entered (`IosRemotePresenter` reports on the request); with the call
 * to it removed, the link logs `DEEP-LINK: delivered haip-vp` and then nothing at all.
 */
@Composable
private fun PopToDashboardOnDeepLink(navigator: AppNavigator) {
    val retrigger = IosNavPlatformActions.rememberPendingLaunchRetrigger()

    LaunchedEffect(retrigger) {
        // `> 0` for the same reason the dashboard's own read carries it: nothing has been delivered on
        // the initial composition, and popping then would move the user for no link.
        if (retrigger == 0) return@LaunchedEffect
        popToDashboardForDeliveredLink(navigator)
    }
}

/**
 * The stack move [PopToDashboardOnDeepLink] performs, split out so it can be tested without a
 * composition — `AppNavigator` takes a plain `MutableList`, so a test needs nothing else.
 *
 * @return whether the stack moved.
 */
internal fun popToDashboardForDeliveredLink(navigator: AppNavigator): Boolean {
    // Already there — the dashboard's own retrigger effect performs the read.
    if (navigator.current is DashboardRoute) return false
    return navigator.popUpTo(DashboardRoute::class, inclusive = false)
}

@Composable
private fun MissingIosScreen(route: NavKey) {
    println("IosNavHost: no iOS screen for $route yet.")
    Box(
        modifier = Modifier.fillMaxSize().padding(SPACING_LARGE.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${route::class.simpleName} has no iOS screen yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}
