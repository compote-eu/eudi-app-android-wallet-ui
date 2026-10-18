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

// Where a delivered deep link goes when it is not the dashboard that is on screen. `IosDeepLinks` only
// *stores* the link; the sole reader of it is the dashboard entry, and `NavDisplay` composes only the
// top one — so without this pop the link is silently kept and the flow it should start never begins.
package eu.europa.ec.shared.ui.navigation

import androidx.navigation3.runtime.NavKey
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentSignRoute
import eu.europa.ec.shared.navigation.SettingsRoute
import eu.europa.ec.shared.navigation.SplashRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopToDashboardOnDeepLinkTest {

    private fun navigatorOn(vararg stack: NavKey) = AppNavigator(stack.toMutableList())

    @Test
    fun a_link_delivered_over_the_sign_screen_pops_back_to_the_dashboard() {
        // The measured case: the QTSP's `haip-vp://` request arrived on the *Sign document* screen,
        // which has no deep-link hook of its own, so nothing ever read it.
        val navigator = navigatorOn(DashboardRoute, DocumentSignRoute)

        assertTrue(popToDashboardForDeliveredLink(navigator))

        assertEquals(listOf(DashboardRoute), navigator.entries)
    }

    @Test
    fun everything_above_the_dashboard_goes_not_just_the_top_entry() {
        val navigator = navigatorOn(DashboardRoute, SettingsRoute, DocumentSignRoute)

        assertTrue(popToDashboardForDeliveredLink(navigator))

        assertEquals(listOf(DashboardRoute), navigator.entries)
    }

    @Test
    fun the_dashboard_already_being_on_top_is_left_alone() {
        // Not a no-op by accident: re-adding the same `NavKey` would not recompose anything anyway, and
        // the dashboard's own `LaunchedEffect(pendingLaunchRetrigger)` is what reads the link there.
        val navigator = navigatorOn(DashboardRoute)

        assertFalse(popToDashboardForDeliveredLink(navigator))

        assertEquals(listOf(DashboardRoute), navigator.entries)
    }

    @Test
    fun a_link_arriving_before_there_is_a_dashboard_moves_nothing() {
        // Android's gate is the same question — `userIsLoggedInWithDocuments()` is itself
        // `isRouteOnBackStackOrForeground(dashboardRoute)`. The link stays pending for the cold read.
        val navigator = navigatorOn(SplashRoute)

        assertFalse(popToDashboardForDeliveredLink(navigator))

        assertEquals(listOf(SplashRoute), navigator.entries)
    }
}
