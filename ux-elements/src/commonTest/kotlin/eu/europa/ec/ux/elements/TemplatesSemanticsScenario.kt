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


package eu.europa.ec.ux.elements

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.europa.ec.ux.elements.templates.TplProgress
import eu.europa.ec.ux.elements.templates.TplSheet
import eu.europa.ec.ux.theme.UxDecisionSteps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Semantics of the four templates, and D7-semantics-preliminary: after a simulated state change (the screen content
 * changes, then the runtime asks the template's heading focus target for focus, as `on_enter: focus-heading` will),
 * each template shows its heading, sets the live region where it has status text, and the focus target holds focus.
 * Run on the iOS simulator and, under Robolectric, on the Android host.
 */
@OptIn(ExperimentalTestApi::class)
abstract class TemplatesSemanticsScenario {

    private fun live(mode: LiveRegionMode) = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)

    @Test
    fun sheetDrawsAnInertAppBarBehindAScrimAndTheDialogIsNamedByTheH1() = runComposeUiTest {
        setUx { SheetUnderTest(UxDecisionSteps.Sheet, "Review request") }
        // The appbar region is inert: its node exists, but nothing under it is in the accessibility tree.
        onNodeWithTag("s.appbar").assertExists()
        onAllNodes(hasText("Wallet")).assertCountEquals(0)
        onNodeWithTag("s.scrim").assertExists()
        onNodeWithTag("s.dialog").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Review request"))
        onAllNodes(isHeading).assertCountEquals(1)
        onNodeWithTag("s.title").assert(isHeading).assertTextEquals("Review request")
        onNodeWithTag("s.send").assertIsDisplayed()
        onNodeWithTag("s.cancel").assertIsDisplayed()
    }

    @Test
    fun sheetPageLayoutIsTheSameDialogWithoutBackdropOrScrim() = runComposeUiTest {
        setUx { SheetUnderTest(UxDecisionSteps.Page, "Review request") }
        onNodeWithTag("s.appbar").assertDoesNotExist()
        onNodeWithTag("s.scrim").assertDoesNotExist()
        onNodeWithTag("s.dialog").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Review request"))
        onAllNodes(isHeading).assertCountEquals(1)
        onNodeWithTag("s.send").assertIsDisplayed()
    }

    @Test
    fun sheetStateChangeKeepsTheHeadingAndFocusMovesToIt() = runComposeUiTest {
        // D7-semantics-preliminary, TPL-sheet: a dialog has no status text, so no live region may be set.
        val focus = FocusRequester()
        var title by mutableStateOf("Review request")
        setUx { SheetUnderTest(UxDecisionSteps.Sheet, title, focus) }
        onNodeWithTag("s.title").assert(isHeading)
        title = "Authenticate"
        waitForIdle()
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("s.title").assert(isHeading).assertTextEquals("Authenticate").assertIsFocused()
        onAllNodes(isHeading).assertCountEquals(1)
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
    }

    @Test
    fun sheetActionsFireAndPrimaryComesFirst() = runComposeUiTest {
        val pressed = mutableListOf<String>()
        setUx {
            TplSheet(
                "s",
                header = { UxText("s.title", "T", UxTextVariant.Title) },
                actions = {
                    UxButton("s.send", "Send", UxButtonVariant.Primary, { pressed += "send" })
                    UxButton("s.cancel", "Cancel", UxButtonVariant.Secondary, { pressed += "cancel" })
                },
            )
        }
        onNodeWithTag("s.send").performClick()
        onNodeWithTag("s.cancel").performClick()
        assertEquals(listOf("send", "cancel"), pressed)
        val y1 = onNodeWithTag("s.send").fetchSemanticsNode().boundsInRoot.top
        val y2 = onNodeWithTag("s.cancel").fetchSemanticsNode().boundsInRoot.top
        assertTrue(y1 < y2, "primary action must come first: $y1 vs $y2")
    }

    @Test
    fun progressPendingHeroIsAPoliteLiveRegionAndKeepsFocusWhereItWas() = runComposeUiTest {
        // D7-semantics-preliminary, TPL-progress: the status text is a polite live region; no focus move on change.
        val focus = FocusRequester()
        var text by mutableStateOf("Please wait")
        setUx { ProgressUnderTest(text, focus) }
        onNodeWithTag("p.hero-block").assert(live(LiveRegionMode.Polite))
        onNodeWithTag("p.hero-block.title", useUnmergedTree = true).assert(isHeading)
        text = "Waiting for the authentication prompt"
        waitForIdle()
        onNodeWithTag("p.hero-block").assert(live(LiveRegionMode.Polite))
            .assert(hasText("Waiting for the authentication prompt", substring = true))
        onNodeWithTag("p.hero-block.title", useUnmergedTree = true).assert(isHeading).assertIsNotFocused()
        // The heading focus target works when the runtime does ask (screen entry).
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("p.hero-block.title", useUnmergedTree = true).assertIsFocused()
        onNodeWithTag("p.cancel").assertExists()
    }

    @Test
    fun progressWithoutActionHasNoActionRegion() = runComposeUiTest {
        setUx {
            TplProgress("p", hero = { UxStatusHero("p.hero-block", UxHeroTone.Pending, "Sending") })
        }
        onNodeWithTag("p.actions").assertDoesNotExist()
        onNodeWithTag("p.hero").assertExists()
    }

    @Test
    fun outcomeStateChangeFromPendingToResultMovesFocusToTheH1AndDropsTheLiveRegion() = runComposeUiTest {
        // D7-semantics-preliminary, TPL-outcome: pending is live; the result is not, and the h1 takes focus.
        val focus = FocusRequester()
        var tone by mutableStateOf(UxHeroTone.Pending)
        setUx { OutcomeUnderTest(tone, focus) }
        onNodeWithTag("o.hero-block").assert(live(LiveRegionMode.Polite))
        tone = UxHeroTone.Success
        waitForIdle()
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("o.hero-block").assert(noLiveRegion)
        onNodeWithTag("o.hero-block.title", useUnmergedTree = true).assert(isHeading).assertTextEquals("Shared").assertIsFocused()
        onAllNodes(isHeading, useUnmergedTree = true).assertCountEquals(1)
        onNodeWithTag("o.close").assertIsDisplayed()
        onNodeWithTag("o.note").assertExists()
    }

    @Test
    fun scanReadsTheInstructionFirstAndTheCameraIsAnImage() = runComposeUiTest {
        val focus = FocusRequester()
        var scanning by mutableStateOf(true)
        setUx { ScanUnderTest(scanning, focus) }
        onNodeWithTag("c.title").assert(isHeading)
        onNodeWithTag("c.camera-view").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
        val ty = onNodeWithTag("c.instruction").fetchSemanticsNode().boundsInRoot.top
        val cy = onNodeWithTag("c.camera").fetchSemanticsNode().boundsInRoot.top
        assertTrue(ty < cy, "instruction before camera")
        // D7-semantics-preliminary, TPL-scan: a state change (camera paused), then the h1 holds focus; no status text.
        scanning = false
        waitForIdle()
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("c.title").assert(isHeading).assertIsFocused()
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
        onNodeWithTag("c.cancel").assertIsDisplayed()
    }
}
