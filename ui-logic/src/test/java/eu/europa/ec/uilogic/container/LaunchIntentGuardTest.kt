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

package eu.europa.ec.uilogic.container

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.DebugFlowHook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchIntentGuardTest {

    @Test
    fun `hook absent never skips the cold-boot link, whatever the flag (upstream behaviour)`() {
        assertTrue(shouldHandleLaunchIntent(guardActive = false, consumed = false))
        assertTrue(shouldHandleLaunchIntent(guardActive = false, consumed = true))
    }

    @Test
    fun `hook present handles the first launch and skips a restore after consumption`() {
        assertTrue(shouldHandleLaunchIntent(guardActive = true, consumed = false))
        assertFalse(shouldHandleLaunchIntent(guardActive = true, consumed = true))
    }

    @Test
    fun `guard is inactive with no hook (release)`() {
        assertFalse(launchGuardActive(null))
    }

    @Test
    fun `guard is inactive with a bound hook whose switch is off (debug, switch off = upstream)`() {
        val hook = FakeHook(enabled = false)
        assertFalse(launchGuardActive(hook))
        assertTrue(shouldHandleLaunchIntent(launchGuardActive(hook), consumed = true))
    }

    @Test
    fun `guard is active with a bound hook whose switch is on`() {
        val hook = FakeHook(enabled = true)
        assertTrue(launchGuardActive(hook))
        assertFalse(shouldHandleLaunchIntent(launchGuardActive(hook), consumed = true))
    }

    private class FakeHook(private val enabled: Boolean) : DebugFlowHook {
        override fun EntryProviderScope<NavKey>.entries(navigator: AppNavigator) = Unit
        override fun redirect(route: NavKey): NavKey? = null
        override fun isEnabled(): Boolean = enabled
    }
}
