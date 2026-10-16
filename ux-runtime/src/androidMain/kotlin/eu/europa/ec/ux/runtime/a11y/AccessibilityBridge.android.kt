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

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
actual fun rememberPlatformAccessibilityBridge(): AccessibilityBridge {
    val view = LocalView.current
    return remember(view) { AndroidAccessibilityBridge(view::announce) }
}

/**
 * Android: the runtime's `requestFocus` moves input focus to the h1; a TPL-sheet dialog is announced by its pane title.
 * Other screens get their h1 announced through the host view, and a progress step its live-region text. Whether
 * TalkBack also follows input focus (double speech) is checked in D7-Android (M11).
 */
class AndroidAccessibilityBridge(private val speak: (String) -> Unit) : AccessibilityBridge {
    override suspend fun screenChanged(screen: String, headingNode: String?, headingText: String?, dialog: Boolean) {
        if (!dialog && !headingText.isNullOrBlank()) speak(headingText)
    }

    override suspend fun announce(screen: String, liveRegionNode: String?, text: String) {
        if (text.isNotBlank()) speak(text)
    }
}

// View.announceForAccessibility is deprecated from API 36 in favour of live regions and pane titles; the stable live
// node is not announced when it is inserted with a new AnimatedContent child, so the spike keeps the explicit call.
@Suppress("DEPRECATION")
private fun View.announce(text: String) = announceForAccessibility(text)
