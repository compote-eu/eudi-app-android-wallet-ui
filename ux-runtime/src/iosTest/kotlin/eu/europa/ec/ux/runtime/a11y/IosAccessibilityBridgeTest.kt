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
// M7: the iOS actual of AccessibilityBridge, with UIKit replaced by recorders where VoiceOver is needed (no bundle).
package eu.europa.ec.ux.runtime.a11y

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.host.FlowHost
import eu.europa.ec.ux.runtime.screen.FlowScreens
import eu.europa.ec.ux.runtime.screen.ScreenTree
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.ExportWalk
import eu.europa.ec.ux.runtime.support.TestCopy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSAttributedString
import platform.Foundation.NSNumber
import platform.Foundation.attribute
import platform.UIKit.UIAccessibilityAnnouncementNotification
import platform.UIKit.UIAccessibilityNotifications
import platform.UIKit.UIAccessibilityScreenChangedNotification
import platform.UIKit.UIAccessibilitySpeechAttributeQueueAnnouncement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IosAccessibilityBridgeTest {

    private val posted = mutableListOf<Pair<UIAccessibilityNotifications, Any?>>()
    private val record: (UIAccessibilityNotifications, Any?) -> Unit = { n, a -> posted += n to a }

    private fun NSAttributedString.queued(): Boolean =
        (attribute(UIAccessibilitySpeechAttributeQueueAnnouncement, 0uL, null) as? NSNumber)?.boolValue == true

    @Test
    fun announceIsAQueuedAnnouncement() = runTest {
        IosAccessibilityBridge(post = record).announce("s", "hero", "Sending")
        val (n, arg) = posted.single()
        assertEquals(UIAccessibilityAnnouncementNotification, n)
        val text = assertIs<NSAttributedString>(arg)
        assertEquals("Sending", text.string)
        assertTrue(text.queued(), "UIAccessibilitySpeechAttributeQueueAnnouncement = YES")
    }

    @Test
    fun withoutVoiceOverTheScreenChangeCarriesTheHeadingText() = runTest {
        var lookups = 0
        val bridge = IosAccessibilityBridge(post = record, voiceOverRunning = { false }, findHeading = { lookups++; null })
        bridge.screenChanged("s", "title", "Review", dialog = true)
        assertEquals(listOf<Pair<UIAccessibilityNotifications, Any?>>(UIAccessibilityScreenChangedNotification to "Review"), posted)
        assertEquals(0, lookups, "no tree walk while VoiceOver is off")
    }

    @Test
    fun withVoiceOverTheScreenChangeTargetsTheH1Element() = runTest {
        val h1 = Any()
        var frames = 0
        val bridge = IosAccessibilityBridge(
            post = record, voiceOverRunning = { true }, findHeading = { if (frames >= 1) h1 else null },
            voiceOverFocus = { null }, awaitFrame = { frames++ },
        )
        bridge.screenChanged("s", "title", "Review", dialog = false)
        val (n, arg) = posted.single()
        assertEquals(UIAccessibilityScreenChangedNotification, n)
        assertSame(h1, arg)
        assertEquals(IosAccessibilityBridge.SETTLE_FRAMES, frames, "waits for Compose's own focus sync first")
    }

    @Test
    fun whenVoiceOverIsAlreadyOnTheH1NothingIsPosted() = runTest {
        val h1 = Any()
        val bridge = IosAccessibilityBridge(
            post = record, voiceOverRunning = { true }, findHeading = { h1 }, voiceOverFocus = { h1 }, awaitFrame = {},
        )
        bridge.screenChanged("s", "title", "Review", dialog = false)
        assertTrue(posted.isEmpty(), "no second ScreenChanged: Compose's focus sync already moved VoiceOver")
    }

    @Test
    fun whenTheH1ElementNeverAppearsTheHeadingTextIsPosted() = runTest {
        var frames = 0
        val bridge = IosAccessibilityBridge(
            post = record, voiceOverRunning = { true }, findHeading = { null }, voiceOverFocus = { null },
            awaitFrame = { frames++ }, maxFrames = 5,
        )
        bridge.screenChanged("s", "title", "Review", dialog = false)
        assertEquals(5, frames)
        assertEquals(listOf<Pair<UIAccessibilityNotifications, Any?>>(UIAccessibilityScreenChangedNotification to "Review"), posted)
    }

    /**
     * The actual inside FlowHost (real frames, UIKit post replaced): the entry state and the next state of the export's
     * main path each post what their screen's `a11y.on_enter` asks (M9r, rule T1: states and texts read from ux-spec/).
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun flowHostDrivesTheIosActual() = runComposeUiTest {
        val bridge = IosAccessibilityBridge(post = record, voiceOverRunning = { false })
        val port = FakePort()
        val bundle = Fixture.bundle()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                FlowHost(
                    bundle = bundle, port = port, entry = ExportWalk.entry(bundle),
                    launch = ExportWalk.launch(ExportWalk.entry(bundle)), platform = FlowPlatform.IOS,
                    backHandler = { _, _ -> }, onExit = {},
                    screens = FlowScreens(copy = TestCopy.provider(Fixture.root), accessibility = bridge), log = FlowLog.NONE,
                )
            }
        }
        val first = ExportWalk.mainPath(bundle).first()
        waitUntil(timeoutMillis = 5_000) { posted.size == 1 }
        runOnIdle { port.complete(first.service!!, first.result!!) }
        waitUntil(timeoutMillis = 5_000) { posted.size == 2 }
        listOf(first.from, first.to).forEachIndexed { i, state ->
            val tree = ExportWalk.tree(bundle, state)
            if (tree.a11y.onEnter == "focus-heading") {
                assertEquals(UIAccessibilityScreenChangedNotification to spoken(tree, tree.a11y.heading!!), posted[i], state)
            } else {
                assertEquals(UIAccessibilityAnnouncementNotification, posted[i].first, state)
                assertTrue(assertIs<NSAttributedString>(posted[i].second).queued(), state)
            }
        }
    }

    /** What a node says, from the bundle: its `{copy}` props' exported texts, joined (as the runtime builds the heading text). */
    private fun spoken(tree: ScreenTree, node: String): String =
        tree.node(node)!!.props.values.mapNotNull { v -> ((v as? JsonObject)?.get("copy") as? JsonPrimitive)?.content }
            .mapNotNull { TestCopy.sk(Fixture.root, it) }.joinToString(". ")
}
