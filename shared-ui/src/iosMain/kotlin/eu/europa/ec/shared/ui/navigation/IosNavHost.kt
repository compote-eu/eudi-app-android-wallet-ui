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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
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
            entryProvider = entryProvider { entries(navigator) },
        )
    }
}

@Composable
private fun rememberHostViewModelStoreOwner(): ViewModelStoreOwner = remember {
    object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()
    }
}
