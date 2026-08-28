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
    // The host body is shared with Android — see `AppNavDisplay`. Only three things are iOS's own:
    // resolving the analytics logger from Koin (Android injects it into `RouterHostImpl`), leaving
    // `rootOwner` to default because Compose on iOS does not necessarily install one the way an
    // Android Activity does, and the fallback below.
    val analytics = remember { KoinPlatform.getKoin().get<AnalyticsLogger>() }

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
