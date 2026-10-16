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

import androidx.compose.runtime.Composable

/**
 * The platform side of the per-state focus and announcement hook (SPIKE-PLAN §1.4, KC3). The runtime calls it once per
 * state entry, after the `AnimatedContent` transition settled plus one frame, when the old state's nodes are gone and
 * the new ones are placed. It never names a screen: everything comes from the screen's bundle `a11y` and node tree.
 */
interface AccessibilityBridge {
    /**
     * `on_enter: focus-heading`: the runtime moved input focus to the h1 node [headingNode] (its test tag, or the
     * tag of an element that draws its h1 under `<node>.<part>`), whose spoken text is [headingText]. [dialog] is true
     * for a screen with a dialog label (TPL-sheet), which Android already announces through its pane title.
     */
    suspend fun screenChanged(screen: String, headingNode: String?, headingText: String?, dialog: Boolean)

    /** `on_enter: announce-live`: a step in progress. Speak [text] once, queued after current speech; focus stays. */
    suspend fun announce(screen: String, liveRegionNode: String?, text: String)

    companion object {
        val NONE: AccessibilityBridge = object : AccessibilityBridge {
            override suspend fun screenChanged(screen: String, headingNode: String?, headingText: String?, dialog: Boolean) = Unit

            override suspend fun announce(screen: String, liveRegionNode: String?, text: String) = Unit
        }
    }
}

/**
 * The bridge of this platform: iOS posts `UIAccessibilityScreenChangedNotification` on the h1 element and queued
 * `UIAccessibilityAnnouncementNotification`s; Android announces through the host view.
 */
@Composable
expect fun rememberPlatformAccessibilityBridge(): AccessibilityBridge
