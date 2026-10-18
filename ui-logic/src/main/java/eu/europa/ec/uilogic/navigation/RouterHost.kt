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

// Nav3 Stage 5: the host is a `NavDisplay` over a typed `AppRoute` back stack — composed by
// `AppNavDisplay` in :shared-ui/commonMain, which iOS's `IosNavHost` composes too. This file is the
// Android-only part: the saveable back stack, the imperative surface below, and the assertion that
// the Activity provided a ViewModelStoreOwner.
//
// What used to be a `NavHost` with six nested `navigation(route = ModuleRoute.X)` subgraphs is now
// one flat back stack plus six per-feature `entryProvider` contributions. The imperative surface
// this interface exposes to `EudiComponentActivity` (which runs outside composition, from
// `onNewIntent`) is unchanged in shape — only the currency changed, from `Screen` route patterns to
// [AppRoute] destination types.
package eu.europa.ec.uilogic.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import eu.europa.ec.analyticslogic.controller.AnalyticsController
import eu.europa.ec.shared.navigation.AppNavDisplay
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.SplashRoute
import eu.europa.ec.uilogic.config.ConfigUILogic
import kotlin.reflect.KClass

interface RouterHost {
    /**
     * The command layer over the live back stack.
     *
     * Only valid once [StartFlow] has composed; the activity's deep-link entry points are all
     * reached after `onFlowStart`, which is what the original `lateinit navController` relied on too.
     */
    fun getNavigator(): AppNavigator
    fun getNavContext(): Context
    fun userIsLoggedInWithDocuments(): Boolean
    fun userIsLoggedInWithNoDocuments(): Boolean
    fun popToDashboardScreen()
    fun popToIssuanceOnboardingScreen()

    /** Whether [destination] is the displayed entry or is waiting underneath it. */
    fun isRouteOnBackStackOrForeground(destination: KClass<out AppRoute>): Boolean

    @Composable
    fun StartFlow(entries: EntryProviderScope<NavKey>.(AppNavigator) -> Unit)
}

class RouterHostImpl(
    private val configUILogic: ConfigUILogic,
    private val analyticsController: AnalyticsController
) : RouterHost {

    private lateinit var navigator: AppNavigator
    private lateinit var context: Context

    override fun getNavigator(): AppNavigator = navigator
    override fun getNavContext(): Context = context

    @Composable
    override fun StartFlow(entries: EntryProviderScope<NavKey>.(AppNavigator) -> Unit) {

        val backStack = rememberNavBackStack(SplashRoute)
        val navigator = remember(backStack) { AppNavigator(backStack) }

        // Published for the activity's imperative deep-link surface, which runs outside composition.
        // Assigned here rather than from an effect so it is available as soon as the host composes —
        // the same point at which the old `navController` field was assigned, and before the
        // `LaunchedEffect` in `EudiComponentActivity.Content` fires `onFlowStart`.
        this.navigator = navigator
        context = LocalContext.current

        // The host body itself is shared with iOS — see `AppNavDisplay`. What stays here is what
        // only Android does: a back stack that survives process death, publishing the navigator for
        // the activity's out-of-composition deep-link surface, and asserting that the Activity
        // really did provide a ViewModelStoreOwner rather than silently falling back to one.
        AppNavDisplay(
            backStack = backStack,
            navigator = navigator,
            analytics = analyticsController,
            rootOwner = checkNotNull(LocalViewModelStoreOwner.current) {
                "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
            },
            entries = entries,
        )
    }

    override fun userIsLoggedInWithDocuments(): Boolean =
        isRouteOnBackStackOrForeground(configUILogic.dashboardRoute)

    override fun userIsLoggedInWithNoDocuments(): Boolean =
        isRouteOnBackStackOrForeground(configUILogic.issuanceRoute)

    override fun isRouteOnBackStackOrForeground(destination: KClass<out AppRoute>): Boolean =
        if (::navigator.isInitialized) navigator.isOnBackStack(destination) else false

    override fun popToDashboardScreen() {
        navigator.popUpTo(destination = configUILogic.dashboardRoute, inclusive = false)
    }

    override fun popToIssuanceOnboardingScreen() {
        navigator.popUpTo(destination = configUILogic.issuanceRoute, inclusive = false)
    }
}
