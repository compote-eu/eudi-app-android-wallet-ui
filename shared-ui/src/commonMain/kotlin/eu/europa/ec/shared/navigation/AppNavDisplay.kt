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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import eu.europa.ec.analyticslogic.controller.AnalyticsLogger
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * The navigation host body, shared by both platforms.
 *
 * Android's `RouterHostImpl.StartFlow` and iOS's `IosNavHost` were near-copies of each other: same
 * `NavDisplay`, same two entry decorators, same screen-reporting effect. This is that common part,
 * with the three things that genuinely differ left to the caller — how the back stack is created,
 * where the [AnalyticsLogger] comes from, and what happens for a destination with no entry.
 *
 * `NavDisplay` itself is multiplatform here only because of how the artifacts are laid out: androidx
 * publishes navigation3-*runtime* for iOS but not navigation3-*ui*, and JetBrains' fork of the latter
 * publishes an Android variant that depends on androidx's. So Android still runs Google's code — see
 * the dependency comment in `shared-ui/build.gradle.kts`.
 *
 * @param backStack the live back stack. Android passes a `rememberNavBackStack`, which survives
 *   process death; iOS passes a plain `mutableStateListOf`, because restoring it needs a
 *   `SavedStateConfiguration` with a `polymorphic(NavKey::class)` serializers module that iOS has no
 *   use for yet. That difference is the reason this parameter is not created here.
 * @param navigator the command layer over [backStack]. Taken rather than created because Android
 *   publishes it for the deep-link entry points that run outside composition.
 * @param analytics receives one `logScreen` per navigation. Injected on Android and resolved from
 *   Koin on iOS, hence a parameter; `AnalyticsLogger` is the common supertype of Android's
 *   `AnalyticsController`.
 * @param rootOwner the [ViewModelStoreOwner] the per-entry stores hang off. Defaults to whatever the
 *   composition provides, which on Android is always the Activity. iOS may provide nothing, so a
 *   host-scoped owner is created instead of failing — pass a non-null value to assert one is present.
 * @param fallback what to show for a destination no [entries] block binds. `null` keeps
 *   `entryProvider`'s own behaviour, which throws. iOS passes a placeholder screen instead, because
 *   a shared screen can navigate to a route whose iOS entry does not exist yet.
 * @param entries each feature's `entry<Route> { }` contributions.
 */
@Composable
fun AppNavDisplay(
    backStack: MutableList<NavKey>,
    navigator: AppNavigator,
    analytics: AnalyticsLogger,
    rootOwner: ViewModelStoreOwner? = LocalViewModelStoreOwner.current,
    fallback: ((NavKey) -> NavEntry<NavKey>)? = null,
    entries: EntryProviderScope<NavKey>.(AppNavigator) -> Unit,
) {
    val owner = rootOwner ?: rememberHostViewModelStoreOwner()

    // Reporting the top of the back stack here, rather than from each entry, is what keeps the two
    // platforms reporting the same screens: a destination added to either host is reported without
    // anyone remembering to add a call.
    LaunchedEffect(backStack) {
        snapshotFlow { backStack.lastOrNull() as? AppRoute }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { route -> analytics.logScreen(route.analyticsName, route.analyticsParams) }
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val viewModelStoreProvider = rememberViewModelStoreProvider(parent = owner)

        NavDisplay(
            backStack = backStack,
            onBack = { navigator.pop() },
            entryDecorators = listOf(
                // Each entry keeps its own saveable state, and its own ViewModelStore cleared when it
                // is popped — what `koinViewModel()` scoping relies on.
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(viewModelStoreProvider),
            ),
            entryProvider = if (fallback != null) {
                entryProvider(fallback = fallback) { entries(navigator) }
            } else {
                entryProvider { entries(navigator) }
            },
        )
    }
}

/**
 * A last-resort owner for platforms whose composition root provides none. Android's Activity always
 * does, so this is iOS's path in practice.
 */
@Composable
private fun rememberHostViewModelStoreOwner(): ViewModelStoreOwner = remember {
    object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()
    }
}
