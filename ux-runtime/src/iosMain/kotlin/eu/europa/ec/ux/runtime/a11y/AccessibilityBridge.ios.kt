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
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import platform.Foundation.NSAttributedString
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.UIKit.UIAccessibilityAnnouncementNotification
import platform.UIKit.UIAccessibilityFocusedElement
import platform.UIKit.UIAccessibilityIdentificationProtocol
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityNotificationVoiceOverIdentifier
import platform.UIKit.UIAccessibilityNotifications
import platform.UIKit.UIAccessibilityPostNotification
import platform.UIKit.UIAccessibilityScreenChangedNotification
import platform.UIKit.UIAccessibilitySpeechAttributeQueueAnnouncement
import platform.UIKit.UIAccessibilityTraitHeader
import platform.UIKit.UIApplication
import platform.UIKit.UIView
import platform.UIKit.accessibilityElementAtIndex
import platform.UIKit.accessibilityElementCount
import platform.UIKit.accessibilityElements
import platform.UIKit.accessibilityTraits
import platform.darwin.NSObject

@Composable
actual fun rememberPlatformAccessibilityBridge(): AccessibilityBridge = remember { IosAccessibilityBridge() }

/**
 * iOS (KC3, R8). After a state change:
 * - `screenChanged` waits (frame by frame, at most [maxFrames]) for VoiceOver's element of the new h1 — the element whose
 *   `accessibilityIdentifier` is the heading node's test tag (or `<node>.<part>`) and that has the Header trait. If
 *   Compose's own focus sync already put VoiceOver there, it posts nothing (no double speech); otherwise it posts
 *   `UIAccessibilityScreenChangedNotification` with that element, or with the heading text when no element is found.
 * - `announce` posts `UIAccessibilityAnnouncementNotification` with an `NSAttributedString` that carries
 *   `UIAccessibilitySpeechAttributeQueueAnnouncement`, so it waits for current speech instead of cutting it.
 * Compose Multiplatform exposes no handle from a semantics node to its UIKit element, hence the identifier lookup.
 * Every UIKit call is a parameter, so a test can record what would be posted.
 */
class IosAccessibilityBridge(
    private val post: (UIAccessibilityNotifications, Any?) -> Unit = { n, a -> UIAccessibilityPostNotification(n, a) },
    private val voiceOverRunning: () -> Boolean = { UIAccessibilityIsVoiceOverRunning() },
    private val findHeading: (String) -> Any? = UIKitHeadingFinder::find,
    private val voiceOverFocus: () -> Any? = { UIAccessibilityFocusedElement(UIAccessibilityNotificationVoiceOverIdentifier) },
    private val awaitFrame: suspend () -> Unit = { withFrameNanos { } },
    private val maxFrames: Int = MAX_FRAMES,
) : AccessibilityBridge {

    override suspend fun screenChanged(screen: String, headingNode: String?, headingText: String?, dialog: Boolean) {
        var element: Any? = null
        if (headingNode != null && voiceOverRunning()) {
            for (frame in 0 until maxFrames) {
                element = findHeading(headingNode)
                if (element != null && voiceOverFocus() === element) return
                if (element != null && frame >= SETTLE_FRAMES) break
                awaitFrame()
            }
        }
        post(UIAccessibilityScreenChangedNotification, element ?: headingText)
    }

    override suspend fun announce(screen: String, liveRegionNode: String?, text: String) {
        if (text.isBlank()) return
        post(UIAccessibilityAnnouncementNotification, queuedSpeech(text))
    }

    companion object {
        /** About half a second at 60 Hz: the Compose accessibility sync runs on its own loop after a layout change. */
        const val MAX_FRAMES: Int = 30

        /** Frames to let Compose's own focus notification land before this bridge posts on the element itself. */
        const val SETTLE_FRAMES: Int = 3

        fun queuedSpeech(text: String): NSAttributedString = NSAttributedString.create(
            string = text,
            attributes = mapOf<Any?, Any?>(UIAccessibilitySpeechAttributeQueueAnnouncement to NSNumber(bool = true)),
        )
    }
}

/** Finds the h1 element of a heading node in the key window's accessibility tree (Compose builds it while VoiceOver runs). */
internal object UIKitHeadingFinder {
    private const val MAX_DEPTH = 40
    private const val MAX_VISITS = 5000

    @Suppress("DEPRECATION") // keyWindow: the app has one window scene; the spike needs no scene lookup.
    fun find(node: String): Any? {
        val root = UIApplication.sharedApplication.keyWindow ?: return null
        val visits = intArrayOf(0)
        return search(root, node, 0, visits)
    }

    private fun search(o: Any?, node: String, depth: Int, visits: IntArray): Any? {
        if (o !is NSObject || depth > MAX_DEPTH || visits[0]++ > MAX_VISITS) return null
        val id = (o as? UIAccessibilityIdentificationProtocol)?.accessibilityIdentifier
        if ((id == node || id?.startsWith("$node.") == true) && (o.accessibilityTraits and UIAccessibilityTraitHeader) != 0uL) return o
        return children(o).firstNotNullOfOrNull { search(it, node, depth + 1, visits) }
    }

    private fun children(o: NSObject): List<Any?> {
        o.accessibilityElements?.let { return it + ((o as? UIView)?.subviews.orEmpty()) }
        if (o is UIView) return o.subviews
        val n = o.accessibilityElementCount()
        return if (n <= 0) emptyList() else (0 until n).map { o.accessibilityElementAtIndex(it) }
    }
}
