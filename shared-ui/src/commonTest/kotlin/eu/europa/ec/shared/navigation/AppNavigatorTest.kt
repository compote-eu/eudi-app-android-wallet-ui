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

import androidx.navigation3.runtime.NavKey
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the back-stack command layer reproduces the retired navController behaviors
 * (Android + iOS): plain push, push-with-popUpTo, pop, replace-current, the splash "start anew"
 * reset, and — the Stage-5 requirement — that pop targets match by destination rather than by value.
 */
class AppNavigatorTest {

    private fun navigator(vararg initial: NavKey) = AppNavigator(mutableListOf(*initial))

    @Test
    fun navigate_pushes_onto_the_stack() {
        val nav = navigator(SplashRoute)
        nav.navigate(DashboardRoute)
        assertEquals(listOf(SplashRoute, DashboardRoute), nav.entries)
        assertEquals(DashboardRoute, nav.current)
    }

    @Test
    fun pop_removes_the_top_but_keeps_the_root() {
        val nav = navigator(DashboardRoute, DocumentDetailsRoute("d1"))
        assertTrue(nav.pop())
        assertEquals(listOf(DashboardRoute), nav.entries)
        assertFalse(nav.pop()) // root remains
        assertEquals(listOf(DashboardRoute), nav.entries)
    }

    @Test
    fun navigate_with_popUpTo_inclusive_matches_navigate_block_popUpTo() {
        // navigate(target){ popUpTo(Splash){ inclusive = true } }
        val nav = navigator(SplashRoute)
        nav.navigate(DashboardRoute, popUpTo = SplashRoute, popUpToInclusive = true)
        assertEquals(listOf(DashboardRoute), nav.entries)
    }

    @Test
    fun replaceAll_starts_anew_like_splash_replacing_itself() {
        val nav = navigator(SplashRoute)
        nav.replaceAll(DashboardRoute) // splash pops itself inclusively, leaving one entry
        assertEquals(listOf(DashboardRoute), nav.entries)
    }

    @Test
    fun plain_navigate_leaves_the_existing_stack_intact() {
        val nav = navigator(SplashRoute, SettingsRoute)
        nav.navigate(DashboardRoute) // no popUpTo argument = nothing is cleared
        assertEquals(listOf(SplashRoute, SettingsRoute, DashboardRoute), nav.entries)
    }

    @Test
    fun popUpTo_exclusive_keeps_the_target() {
        val nav = navigator(DashboardRoute, SettingsRoute, DocumentSignRoute)
        assertTrue(nav.popUpTo(DashboardRoute, inclusive = false))
        assertEquals(listOf(DashboardRoute), nav.entries)
    }

    @Test
    fun replaceCurrent_swaps_the_displayed_entry() {
        val nav = navigator(DashboardRoute, SettingsRoute)
        nav.replaceCurrent(DocumentSignRoute)
        assertEquals(listOf(DashboardRoute, DocumentSignRoute), nav.entries)
    }

    /**
     * The Stage-5 requirement: config-carrying pop targets are rebuilt at the call site, so an
     * equality match would miss the entry that is actually on the stack. Matching the destination
     * finds it — which is also what navigation-compose's `popUpTo(routePattern)` did.
     */
    @Test
    fun popUpTo_matches_the_destination_not_the_arguments() {
        val onStack = AddDocumentRoute(IssuanceUiConfig(IssuanceFlowType.NoDocument))
        val rebuiltWithDifferentConfig =
            AddDocumentRoute(IssuanceUiConfig(IssuanceFlowType.ExtraDocument("mDL")))
        assertFalse(onStack == rebuiltWithDifferentConfig)

        val nav = navigator(DashboardRoute, onStack, SettingsRoute)
        assertTrue(nav.popUpTo(rebuiltWithDifferentConfig, inclusive = true))
        assertEquals(listOf(DashboardRoute), nav.entries)
    }

    @Test
    fun navigate_popUpTo_also_matches_by_destination() {
        val nav = navigator(
            DashboardRoute,
            AddDocumentRoute(IssuanceUiConfig(IssuanceFlowType.NoDocument)),
        )
        nav.navigate(
            route = DashboardRoute,
            popUpTo = AddDocumentRoute(IssuanceUiConfig(IssuanceFlowType.ExtraDocument("mDL"))),
            popUpToInclusive = true,
        )
        assertEquals(listOf(DashboardRoute, DashboardRoute), nav.entries)
    }

    @Test
    fun popUpTo_reports_false_when_the_destination_is_absent() {
        val nav = navigator(DashboardRoute, SettingsRoute)
        assertFalse(nav.popUpTo(DocumentSignRoute))
        assertEquals(listOf(DashboardRoute, SettingsRoute), nav.entries)
    }

    @Test
    fun isOnBackStack_sees_displayed_and_buried_destinations() {
        val nav = navigator(DashboardRoute, DocumentDetailsRoute("d1"), SettingsRoute)
        assertTrue(nav.isOnBackStack(DashboardRoute::class))
        assertTrue(nav.isOnBackStack(DocumentDetailsRoute::class))
        assertTrue(nav.isOnBackStack(SettingsRoute::class))
        assertFalse(nav.isOnBackStack(DocumentSignRoute::class))
    }
}
