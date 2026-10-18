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

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Semantics of the nine elements (SPIKE-PLAN §3 M5): role, name, heading, live region, focus target, events and the
 * 48 dp target, asserted through the semantics tree. Run on the iOS simulator and, under Robolectric, on the Android host.
 */
@OptIn(ExperimentalTestApi::class)
abstract class ElementsSemanticsScenario {

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun textTitleAndHeadingAreHeadingsBodyIsNot() = runComposeUiTest {
        setUx {
            Column {
                UxText("t1", "Title", UxTextVariant.Title)
                UxText("t2", "Section", UxTextVariant.Heading)
                UxText("t3", "Body")
                UxText("t4", "Small", UxTextVariant.Small, tone = UxTextTone.Secondary)
                UxText("t5", "ABC 123", UxTextVariant.Display)
                UxText("t6", "https://example.invalid", tone = UxTextTone.Link)
            }
        }
        onNodeWithTag("t1").assert(isHeading).assertTextEquals("Title")
        onNodeWithTag("t2").assert(isHeading)
        listOf("t3", "t4", "t5", "t6").forEach { onNodeWithTag(it).assert(noHeading) }
        // The display value is one node, read whole, and a link-toned text is not operable.
        onNodeWithTag("t5").assertTextEquals("ABC 123")
        onNodeWithTag("t6").assertHasNoClickAction().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
    }

    @Test
    fun textTitleTakesFocusFromItsRequester() = runComposeUiTest {
        val focus = FocusRequester()
        setUx { UxText("t1", "Title", UxTextVariant.Title, focusRequester = focus) }
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("t1").assertIsFocused()
    }

    @Test
    fun buttonIsAButtonNamedByContentWithA48dpTarget() = runComposeUiTest {
        var pressed = 0
        setUx { UxButton("b", "Send", UxButtonVariant.Primary, onPress = { pressed++ }) }
        onNodeWithTag("b").assert(role(Role.Button)).assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assert(hasText("Send"))
        onNodeWithTag("b").performClick()
        assertEquals(1, pressed)
    }

    @Test
    fun disabledButtonIsDisabledAndDoesNotFire() = runComposeUiTest {
        var pressed = 0
        setUx { UxButton("b", "Send", UxButtonVariant.Secondary, onPress = { pressed++ }, disabled = true) }
        onNodeWithTag("b").assertIsNotEnabled().performClick()
        assertEquals(0, pressed)
    }

    @Test
    fun iconButtonIsNamedByLabelAndPopupStateIsAnnounced() = runComposeUiTest {
        setUx {
            Column {
                UxButton("i1", "Actions", UxButtonVariant.Icon, {}, icon = "menu", popup = true, popupStateLabel = "collapsed")
                UxButton("i2", "Add document", UxButtonVariant.IconPrimary, {}, icon = "plus")
                UxButton("i3", "Search documents", UxButtonVariant.Search, {})
            }
        }
        onNodeWithTag("i1").assertContentDescriptionEquals("Actions").assert(role(Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "collapsed"))
        onNodeWithTag("i2").assertContentDescriptionEquals("Add document").assertHeightIsAtLeast(48.dp)
        // The search pill is a button named by its visible label, not a field.
        onNodeWithTag("i3").assert(role(Role.Button)).assert(hasText("Search documents"))
    }

    @Test
    fun buttonTakesFocusFromItsRequester() = runComposeUiTest {
        val focus = FocusRequester()
        setUx { UxButton("b", "Send", UxButtonVariant.Primary, {}, focusRequester = focus) }
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("b").assertIsFocused()
    }

    @Test
    fun linkIsOperableNamedWithTheExternalHintAndRendersNothingWithoutHref() = runComposeUiTest {
        var followed: String? = null
        setUx {
            Column {
                UxLink("l1", "Privacy policy", "https://example.invalid/p", { followed = it }, external = true, externalLabel = "opens in the browser")
                UxLink("l2", "Local", "https://example.invalid/l", {})
                UxLink("l3", "Empty", "", {})
            }
        }
        onNodeWithTag("l1").assertHasClickAction().assertContentDescriptionEquals("Privacy policy, opens in the browser")
            .assertHeightIsAtLeast(48.dp)
            // The name is spoken once: the visible text is not merged in a second time (M7 audit).
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        onNodeWithTag("l2").assertContentDescriptionEquals("Local")
        onNodeWithTag("l3").assertDoesNotExist()
        onNodeWithTag("l1").performClick()
        assertEquals("https://example.invalid/p", followed)
    }

    @Test
    fun noticeIsAPlainNoteWithTitleTextAndASeparateSlot() = runComposeUiTest {
        var opened = 0
        setUx {
            UxNotice("n", UxNoticeTone.Warning, "Check the address.", title = "Careful", extra = {
                UxLink("n-link", "Help", "https://example.invalid/h", { opened++ })
            })
        }
        onNodeWithTag("n").assert(noLiveRegion).assert(noHeading)
        onNodeWithTag("n.title", useUnmergedTree = true).assertTextEquals("Careful")
        onNodeWithTag("n.text", useUnmergedTree = true).assertTextEquals("Check the address.")
        onNodeWithTag("n-link").assertHasClickAction().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun rpIdentityShowsTrustAsTextAndOnlyTrustedIsProminent() = runComposeUiTest {
        setUx {
            Column {
                UxRpIdentity("rp1", "Acme", UxTrust.Trusted, "Verified", service = "acme.example", reason = "Age check", caption = "Asked by")
                UxRpIdentity("rp2", "Claimed Ltd", UxTrust.Untrusted, "Not verified")
            }
        }
        onNodeWithTag("rp1.trust").assert(hasText("Verified", substring = true))
        onNodeWithTag("rp2.trust").assert(hasText("Not verified", substring = true))
        onNodeWithTag("rp1.name", useUnmergedTree = true).assertTextEquals("Acme").assert(noHeading)
        onNodeWithTag("rp1.service").assert(hasText("acme.example", substring = true))
        onNodeWithTag("rp1.reason").assert(hasText("Age check", substring = true))
        // No role and no operable part: the badge is not interactive.
        onNodeWithTag("rp1.trust").assertHasNoClickAction()
        // Only a trusted name is drawn prominent (bold); an unverified name keeps the regular body weight.
        assertEquals(FontWeight.Bold, weightOf("rp1.name"))
        assertEquals(FontWeight.Normal, weightOf("rp2.name"))
    }

    @Test
    fun rpIdentityLogoIsADecorativeImageHiddenFromAccessibilityAndAbsentWhenEmpty() = runComposeUiTest {
        // EL-rp-identity `logo` (RULING-W1, M8r): an image node hidden from accessibility, with the name still naming
        // the party; an empty or absent logo draws nothing. The URL is not fetched in the test (no network fetcher).
        setUx {
            Column {
                UxRpIdentity("rp1", "Acme", UxTrust.Trusted, "Verified", logo = "https://www.example.sk/rp/logo")
                UxRpIdentity("rp2", "Beta", UxTrust.Trusted, "Verified", logo = "")
                UxRpIdentity("rp3", "Gamma", UxTrust.Untrusted, "Not verified")
            }
        }
        val logo = onNodeWithTag("rp1.logo", useUnmergedTree = true).assertExists().fetchSemanticsNode().config
        assertTrue(SemanticsProperties.HideFromAccessibility in logo, "the logo is hidden from accessibility")
        assertEquals(null, logo.getOrNull(SemanticsProperties.ContentDescription), "decorative: no alt text")
        assertEquals(null, logo.getOrNull(SemanticsProperties.Text), "decorative: no text")
        onNodeWithTag("rp1.name", useUnmergedTree = true).assertTextEquals("Acme")
        onAllNodesWithTag("rp2.logo", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithTag("rp3.logo", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun statusHeroPendingIsAPoliteLiveRegionAndOutcomesAreNot() = runComposeUiTest {
        setUx {
            Column {
                UxStatusHero("h-pending", UxHeroTone.Pending, "Sending", text = "Please wait")
                UxStatusHero("h-ok", UxHeroTone.Success, "Done", text = "Shared")
                UxStatusHero("h-err", UxHeroTone.Error, "Failed")
                UxStatusHero("h-warn", UxHeroTone.Warning, "Expired")
                UxStatusHero("h-info", UxHeroTone.Info, "Note")
            }
        }
        onNodeWithTag("h-pending").assert(live(LiveRegionMode.Polite))
        listOf("h-ok", "h-err", "h-warn", "h-info").forEach { onNodeWithTag(it).assert(noLiveRegion) }
        listOf("h-pending", "h-ok", "h-err", "h-warn", "h-info").forEach {
            onNodeWithTag("$it.title", useUnmergedTree = true).assert(isHeading)
        }
        // A pending hero reads as one announcement: title and text merged into the live region.
        onNodeWithTag("h-pending").assert(hasText("Sending")).assert(hasText("Please wait"))
    }

    @Test
    fun statusHeroTitleTakesFocusFromItsRequester() = runComposeUiTest {
        val focus = FocusRequester()
        setUx { UxStatusHero("h", UxHeroTone.Success, "Done", focusRequester = focus) }
        runOnIdle { focus.requestFocus() }
        onNodeWithTag("h.title", useUnmergedTree = true).assertIsFocused()
    }

    @Test
    fun appBarControlsAreNamedButtonsAndTheTitleIsNoHeading() = runComposeUiTest {
        var leading = 0
        var trailing = 0
        setUx {
            Column {
                UxAppBar(
                    "bar", UxBarLeading.Back, leadingLabel = "Back", title = "Wallet", trailing = UxBarTrailing.Notifications,
                    trailingLabel = "Notifications, 2 unread", badge = true, onLeading = { leading++ }, onTrailing = { trailing++ },
                )
                UxAppBar("bar2", UxBarLeading.None, title = "Plain")
            }
        }
        onNodeWithTag("bar.leading").assertContentDescriptionEquals("Back").assert(role(Role.Button)).assertHeightIsAtLeast(48.dp)
        onNodeWithTag("bar.trailing").assertContentDescriptionEquals("Notifications, 2 unread")
        onNodeWithTag("bar.title", useUnmergedTree = true).assertTextEquals("Wallet").assert(noHeading)
        onNodeWithTag("bar2.leading").assertDoesNotExist()
        onNodeWithTag("bar2.trailing").assertDoesNotExist()
        onNodeWithTag("bar.leading").performClick()
        onNodeWithTag("bar.trailing").performClick()
        assertEquals(1 to 1, leading to trailing)
    }

    @Test
    fun attributeListMandatoryIsLockedCheckedAndTaggedOptionalToggles() = runComposeUiTest {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        val items = listOf(
            UxAttributeItem("name", "Name", UxRequirement.Mandatory, value = "Eva"),
            UxAttributeItem("photo", "Photo", UxRequirement.Optional, selected = false),
            UxAttributeItem("age", "Age", UxRequirement.Optional, selected = true),
        )
        setUx {
            UxAttributeList(
                "attrs", items, onToggle = { id, v -> toggled += id to v },
                mandatoryHeading = "Required", optionalHeading = "Optional", mandatoryLabel = "Mandatory",
            )
        }
        // Locked but enabled (no "dimmed" on iOS): checked, a checkbox, no toggle action.
        onNodeWithTag("attrs.item.name").assertIsEnabled().assertIsOn().assert(role(Role.Checkbox))
            .assert(hasText("Mandatory", substring = true)).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        onNodeWithTag("attrs.item.photo").assertIsOff().assert(role(Role.Checkbox)).performClick()
        onNodeWithTag("attrs.item.age").assertIsOn()
        assertEquals(listOf("photo" to true), toggled)
        // Mandatory items are never toggled.
        onNodeWithTag("attrs.item.name").performClick()
        assertEquals(1, toggled.size)
        // The two groups are headed lists: "list, 1 item" and "list, 2 items"; each row has its position.
        onAllNodes(isHeading, useUnmergedTree = true).assertCountEquals(2)
        onNodeWithTag("attrs.mandatory").assert(SemanticsMatcher.expectValue(SemanticsProperties.CollectionInfo, CollectionInfo(1, 1)))
        onNodeWithTag("attrs.optional").assert(SemanticsMatcher.expectValue(SemanticsProperties.CollectionInfo, CollectionInfo(2, 1)))
        onNodeWithTag("attrs.item.age").assert(
            SemanticsMatcher("second row") { it.config.getOrNull(SemanticsProperties.CollectionItemInfo)?.rowIndex == 1 },
        )
    }

    @Test
    fun attributeListReadonlyHasNoControlsCopyAndPhotoAreNamed() = runComposeUiTest {
        val copied = mutableListOf<String>()
        val items = listOf(
            UxAttributeItem("a", "Name", UxRequirement.Mandatory, value = "Eva", copyable = true),
            UxAttributeItem("b", "Portrait", UxRequirement.Optional, value = "Portrait of Eva", photo = true),
        )
        setUx { UxAttributeList("ro", items, readonly = true, onCopy = { copied += it }, copyLabel = "Copy") }
        onNodeWithTag("ro.item.a").assert(noRole())
        onNodeWithTag("ro.copy.a").assertContentDescriptionEquals("Copy Name").assert(role(Role.Button)).performClick()
        assertEquals(listOf("a"), copied)
        // The portrait is an image named by its value.
        onNode(hasContentDescription("Portrait of Eva"), useUnmergedTree = true).assert(role(Role.Image))
    }

    private fun ComposeUiTest.weightOf(tag: String): FontWeight? {
        val results = mutableListOf<TextLayoutResult>()
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            .action?.invoke(results)
        return results.firstOrNull()?.layoutInput?.style?.fontWeight
    }

    private fun noRole() = SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)

    @Test
    fun qrViewfinderIsOneNamedImageThatIsNotOperable() = runComposeUiTest {
        setUx { UxQrViewfinder("qr", "Point the camera at the QR code") }
        onNodeWithTag("qr").assert(role(Role.Image)).assertContentDescriptionEquals("Point the camera at the QR code")
            .assertHasNoClickAction()
    }
}
