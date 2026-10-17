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
}
