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
// M7: the Android actual of AccessibilityBridge with the view's announcement replaced by a recorder (no bundle).
package eu.europa.ec.ux.runtime.a11y

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAccessibilityBridgeTest {
    private val spoken = mutableListOf<String>()
    private val bridge = AndroidAccessibilityBridge { spoken += it }

    @Test
    fun aProgressStepIsAnnouncedOnce() = runTest {
        bridge.announce("s", "hero", "Sending")
        bridge.announce("s", "hero", " ")
        assertEquals(listOf("Sending"), spoken)
    }

    @Test
    fun aDialogIsLeftToItsPaneTitleOtherScreensAnnounceTheirH1() = runTest {
        bridge.screenChanged("sheet", "title", "Review", dialog = true)
        bridge.screenChanged("outcome", "hero", "Shared", dialog = false)
        bridge.screenChanged("bare", null, null, dialog = false)
        assertEquals(listOf("Shared"), spoken)
    }
}
