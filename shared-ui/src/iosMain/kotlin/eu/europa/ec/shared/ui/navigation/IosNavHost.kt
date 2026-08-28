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

// The iOS navigation host — deliberately the *same* `NavDisplay` the Android host uses, not a
// reimplementation.
//
// Google publishes `androidx.navigation3:navigation3-runtime` for iOS but NOT `navigation3-ui`, so
// `NavDisplay` looks Android-only (probed: "Could not resolve androidx.navigation3:navigation3-ui" for
// iosSimulatorArm64). JetBrains fills exactly that gap:
// `org.jetbrains.androidx.navigation3:navigation3-ui` publishes iosArm64/iosSimulatorArm64/iosX64
// (plus desktop and js) and depends on the androidx artifacts, so Android keeps using Google's build
// and only the non-Android targets take the fork. `androidx.lifecycle:lifecycle-viewmodel-navigation3`
// turns out to be multiplatform already, so the per-entry ViewModelStore decorator comes along too.
//
// The result is that this host is a near copy of `RouterHostImpl`, which is the outcome worth having:
// transitions, predictive back and the entry decorators are maintained upstream rather than by us.
//
// Still deliberately absent: state restoration across process death. That needs
// `rememberNavBackStack`, whose non-Android overload `require`s a `SavedStateConfiguration` carrying a
// `serializersModule` with `polymorphic(NavKey::class) { … }` registered for the route hierarchy. A
// plain `mutableStateListOf` is used until iOS actually needs restoration.
package eu.europa.ec.shared.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import eu.europa.ec.shared.navigation.AppNavigator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import eu.europa.ec.analyticslogic.controller.AnalyticsLogger
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.analyticsName
import eu.europa.ec.shared.navigation.analyticsParams
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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
    // Compose on iOS does not necessarily install a root ViewModelStoreOwner the way an Android
    // Activity does, and the per-entry decorator needs a parent to hang stores off. Fall back to one
    // scoped to this host rather than requiring the caller to provide it.
    val rootOwner = LocalViewModelStoreOwner.current ?: rememberHostViewModelStoreOwner()

    // Screen reporting, mirroring Android's `RouterHost` line for line — same `snapshotFlow` on the
    // back stack, same `distinctUntilChanged`, same `analyticsName`/`analyticsParams`. Doing it here
    // rather than per-entry is what keeps the two platforms reporting the same screens: a destination
    // added to either host is reported without anyone remembering to add a call.
    val analytics = remember { KoinPlatform.getKoin().get<AnalyticsLogger>() }
    LaunchedEffect(backStack) {
        snapshotFlow { backStack.lastOrNull() as? AppRoute }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { route -> analytics.logScreen(route.analyticsName, route.analyticsParams) }
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides rootOwner) {
        val viewModelStoreProvider = rememberViewModelStoreProvider(parent = rootOwner)

        NavDisplay(
            backStack = backStack,
            onBack = { navigator.pop() },
            entryDecorators = listOf(
                // Each entry keeps its own saveable state, and its own ViewModelStore cleared when it
                // is popped — what `koinViewModel()` scoping relies on.
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(viewModelStoreProvider),
            ),
            entryProvider = entryProvider(
                // Without this, a route with no iOS entry *throws* — `entryProvider`'s default
                // fallback does. That matters now that shared screens navigate: the dashboard's side
                // menu offers Change PIN, whose screen is still Android-only, so a tap would have
                // crashed the app rather than shown anything. A named placeholder is both survivable
                // and more useful than a stack trace while the port is in progress.
                fallback = { unknownRoute ->
                    NavEntry(key = unknownRoute) { MissingIosScreen(route = unknownRoute) }
                },
            ) { entries(navigator) },
        )
    }
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

@Composable
private fun rememberHostViewModelStoreOwner(): ViewModelStoreOwner = remember {
    object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()
    }
}
