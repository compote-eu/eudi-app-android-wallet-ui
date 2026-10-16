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

package eu.europa.ec.ux.runtime.a11y

/**
 * The platform announcement bridge (SPIKE-PLAN §1.4). The runtime calls it once per state change, after the
 * transition settled and after it moved focus to the heading (`on_enter: focus-heading`) or left the polite live
 * region to speak (`announce-live`). M5b leaves the platform side open: M7 adds the iOS one, which posts
 * `UIAccessibilityScreenChangedNotification`. The ids are node ids of the screen, never screen-specific code.
 */
interface AccessibilityBridge {
    /** Focus moved to the heading node [headingNode] (null: the screen has no heading). */
    fun screenChanged(screen: String, headingNode: String?)

    /** The screen announces itself through its live region [liveRegionNode]; focus is not moved. */
    fun liveRegionShown(screen: String, liveRegionNode: String?)

    companion object {
        val NONE: AccessibilityBridge = object : AccessibilityBridge {
            override fun screenChanged(screen: String, headingNode: String?) = Unit

            override fun liveRegionShown(screen: String, liveRegionNode: String?) = Unit
        }
    }
}
