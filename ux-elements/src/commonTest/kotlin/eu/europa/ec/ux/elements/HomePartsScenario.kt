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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import eu.europa.ec.ux.elements.templates.TplHome
import eu.europa.ec.ux.theme.UxMode
import eu.europa.ec.ux.theme.UxThemeAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5e: the Home parts (OR-8), both targets: EL-status-chip, EL-document-card, EL-bottom-bar, EL-segmented-switch,
 * EL-trust-mark, TPL-home, the app-bar gaps, and the avatar and lock icons. Each part is checked against the export's own
 * `ux-tokens.json` and `ux-library.json` (embedded by the build): its semantics (role, name, selected state, heading),
 * the brand selections it follows, the assets it draws, and one measured spacing taken from a token.
 */
@OptIn(ExperimentalTestApi::class)
abstract class HomePartsScenario {

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    // ---- EL-status-chip

    @Test
    fun everyChipStatusHasTheLibrariesTone() {
        val expected = mapOf(
            UxChipTone.Success to listOf("Valid", "Success"),
            UxChipTone.Error to listOf("Invalid", "Revoked", "Failed", "Refused"),
            UxChipTone.Warning to listOf("Expiring", "Expired", "Suspended", "Stale", "Unverified"),
            UxChipTone.Info to listOf("Info"),
            UxChipTone.Neutral to listOf("Upcoming"),
        )
        assertEquals(UxChipStatus.entries.map { it.name }.sorted(), expected.values.flatten().sorted())
        expected.forEach { (tone, names) -> names.forEach { assertEquals(tone, UxChipStatus.valueOf(it).tone, it) } }
    }

    @Test
    fun theBrandsMarkStyleDecidesBetweenADotAndAToneGlyph() = runComposeUiTest {
        setHome(HomeSpec.EUDIW) { UxStatusChip("c", UxChipStatus.Valid, "Platný") }
        onNodeWithTag("c.dot", useUnmergedTree = true).assertExists()
        assertEquals(emptyList(), drawnAssets(), "the dot mark draws no glyph")
    }

    @Test
    fun theIconMarkDrawsTheToneGlyphTheBrandBindsAndNothingForAGlyphItDoesNot() = runComposeUiTest {
        setHome(HomeSpec.IDSK) {
            Column {
                UxStatusChip("w", UxChipStatus.Expiring, "Platnosť končí")
                UxStatusChip("v", UxChipStatus.Valid, "Platný")
            }
        }
        onAllNodesWithTag("w.dot", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(listOf("icon.error-outline"), drawnAssets().map { it.id }, "idsk binds no icon.check-circle: nothing is invented (DEC-080)")
    }

    @Test
    fun anUpcomingChipDrawsNoMarkAndAChipIsPlainTextWithItsLabel() = runComposeUiTest {
        setHome(HomeSpec.IDSK) {
            Column {
                UxStatusChip("up", UxChipStatus.Upcoming, "Pripravujeme")
                UxStatusChip("ok", UxChipStatus.Info, "Informácia")
            }
        }
        assertEquals(listOf("icon.info"), drawnAssets().map { it.id }, "only the info chip has a glyph")
        val config = onNodeWithTag("ok").fetchSemanticsNode().config
        assertNull(config.getOrNull(SemanticsProperties.Role), "inline text: no role")
        assertNull(config.getOrNull(SemanticsProperties.Heading))
        assertEquals(listOf("Informácia"), config[SemanticsProperties.Text].map { it.text })
    }

    @Test
    fun chipColoursComeFromTheExportedTable() = runComposeUiTest {
        var read = emptyList<Pair<Boolean, Boolean>>()
        setHome(HomeSpec.EUDIW) {
            val tokens = UxThemeAccess.tokens
            val bg = chipColors(UxChipTone.Success).bg
            val fb = tokens.colors.feedbackSuccess.bg
            val table = HomeSpec.tokens.color(HomeSpec.EUDIW, UxMode.Light, "comp.status-chip.success-bg")
            read = listOf((bg == table) to (bg == fb))
        }
        assertEquals(true, read.single().first, "the chip's colour is the table's")
    }

    // ---- EL-document-card

    @Test
    fun theHolderCardIsOneButtonNamedByHolderAndStatusWithDecorativeAvatarDotAndChevron() = runComposeUiTest {
        var opened = 0
        setHome {
            UxDocumentCard(
                "h", UxCardVariant.Holder, UxDocType.Pid, "Identifikačné údaje", UxDocStatus.Valid,
                "Identifikačné údaje · platné", holder = "Ján Vzorový", initials = "JV", onOpen = { opened++ },
            )
        }
        val config = onNodeWithTag("h").fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals(listOf("Ján Vzorový, Identifikačné údaje · platné"), config[SemanticsProperties.ContentDescription])
        assertEquals("JV", drawnText("h.initials"))
        onNodeWithTag("h.dot", useUnmergedTree = true).assertExists()
        assertEquals(listOf("icon.arrow-forward-ios"), drawnAssets().map { it.id }, "the chevron is the only asset of a holder row")
        onNodeWithTag("h").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aCenteredFullCardDrawsTitleEnglishNameAndNumberOnlyAndKeepsTheArtworksProportion() = runComposeUiTest {
        setHome(HomeSpec.EUDIW) {
            UxDocumentCard(
                "c", UxCardVariant.Full, UxDocType.IdCard, "Občiansky preukaz", UxDocStatus.Valid, "Platný do 1. 3. 2034",
                titleEn = "ID CARD", holder = "Ján Vzorový", number = "EA 000000", storage = UxStorage.Phone, storageLabel = "V telefóne",
            )
        }
        assertEquals("Občiansky preukaz", drawnText("c.title"))
        assertEquals("ID CARD", drawnText("c.title-en"))
        assertEquals("EA 000000", drawnText("c.number"))
        assertEquals("Platný do 1. 3. 2034", drawnText("c.status"))
        listOf("c.holder", "c.storage").forEach { onAllNodesWithTag(it, useUnmergedTree = true).assertCountEquals(0) }
        val config = onNodeWithTag("c").fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals(listOf("Občiansky preukaz, Platný do 1. 3. 2034, EA 000000"), config[SemanticsProperties.ContentDescription])
        assertTrue("document-art.id-card" in drawnAssets().map { it.id }, "the brand's artwork is drawn")
        val box = onNodeWithTag("c").getUnclippedBoundsInRoot()
        assertTrue(box.height.value + 1f >= box.width.value * HomeSpec.ART.height / HomeSpec.ART.width, "the card keeps the artwork's proportion: $box")
    }

    @Test
    fun aBandFullCardDrawsHolderNumberAndStorageAndNamesThemAfterTheStatus() = runComposeUiTest {
        setHome(HomeSpec.IDSK) {
            UxDocumentCard(
                "b", UxCardVariant.Full, UxDocType.DrivingLicence, "Vodičský preukaz", UxDocStatus.Expiring, "Platnosť končí 12. 11. 2026",
                titleEn = "DRIVING LICENCE", holder = "Ján Vzorový", number = "SK 0000000", storage = UxStorage.Cloud, storageLabel = "V cloude",
            )
        }
        assertEquals("Ján Vzorový", drawnText("b.holder"))
        assertTrue(onNodeWithTag("b.holder", useUnmergedTree = true).getUnclippedBoundsInRoot().height.value > 0f, "without artwork the body is not collapsed")
        assertEquals("SK 0000000", drawnText("b.number"))
        assertEquals("V cloude", drawnText("b.storage-label"))
        onAllNodesWithTag("b.title-en", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(
            listOf("Vodičský preukaz, Platnosť končí 12. 11. 2026, Ján Vzorový, SK 0000000, V cloude"),
            onNodeWithTag("b").fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        assertEquals(listOf("icon.error-outline"), drawnAssets().map { it.id }, "idsk binds no card art and no icon.cloud: only the tone glyph is drawn")
    }

    @Test
    fun theHeroCardIsDecorativeAndNeverInteractiveAndAMiniCardIsARowWithAChevron() = runComposeUiTest {
        var opened = 0
        setHome {
            Column {
                UxDocumentCard("hero", UxCardVariant.Hero, UxDocType.IdCard, "Občiansky preukaz", UxDocStatus.Valid, "Platný", onOpen = { opened++ })
                UxDocumentCard("mini", UxCardVariant.Mini, UxDocType.IdCard, "Občiansky preukaz", UxDocStatus.Revoked, "Odvolaný", onOpen = { opened++ })
                UxDocumentCard("still", UxCardVariant.Full, UxDocType.IdCard, "Občiansky preukaz", UxDocStatus.Valid, "Platný", interactive = false)
            }
        }
        onNodeWithTag("hero").assertHasNoClickAction()
        onNodeWithTag("still").assertHasNoClickAction()
        assertNull(onNodeWithTag("hero").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        assertEquals(HomeSpec.tokens.dp(HomeSpec.EUDIW, UxMode.Light, "comp.document-card.hero-width"), onNodeWithTag("hero").getUnclippedBoundsInRoot().width)
        onNodeWithTag("mini").assert(role(Role.Button))
        onNodeWithTag("mini").performClick()
        onNodeWithTag("hero").performClick()
        assertEquals(1, opened, "only the mini card opens")
        assertEquals("Odvolaný", drawnText("mini.status"))
    }

    // ---- EL-bottom-bar

    @Test
    fun theBottomBarIsANamedGroupOfButtonsWithTheCurrentOneSelectedAndANamedActionButton() = runComposeUiTest {
        val events = mutableListOf<String>()
        val tabs = listOf(UxBarTab("documents", "eDoklady", "icon-id-card", true), UxBarTab("services", "eSlužby", "icon-case", false))
        setHome {
            UxBottomBar("bar", "Hlavná navigácia", tabs, action = UxBarAction.Menu, actionLabel = "Akcie", onSelect = { events += "select $it" }, onAction = { events += "action" })
        }
        assertEquals(listOf("Hlavná navigácia"), onNodeWithTag("bar.tabs").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        onNodeWithTag("bar.tab.documents").assertIsSelected().assert(role(Role.Button))
        onNodeWithTag("bar.tab.services").assertIsNotSelected().assert(role(Role.Button))
        assertEquals(listOf("eSlužby"), onNodeWithTag("bar.tab.services").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        onNodeWithTag("bar.tab.documents").performClick()
        onNodeWithTag("bar.tab.services").performClick()
        onNodeWithTag("bar.action").performClick()
        assertEquals(listOf("select services", "action"), events, "pressing the current destination does nothing")
        val action = onNodeWithTag("bar.action").fetchSemanticsNode().config
        assertEquals(listOf("Akcie"), action[SemanticsProperties.ContentDescription])
        assertTrue(SemanticsActions.Expand in action, "the action advertises the menu it opens")
        assertEquals(
            listOf("icon.add", "icon.icon-case", "icon.icon-id-card"),
            drawnAssets().map { it.id }.sorted(),
            "a tab's icon is the library's `icon.<name>` of its icon field",
        )
    }

    @Test
    fun theBarTellsItsTemplateItIsPresentWhileItIsDrawn() = runComposeUiTest {
        val presence = androidx.compose.runtime.mutableStateOf(false)
        var shown by mutableStateOf(true)
        setHome {
            androidx.compose.runtime.CompositionLocalProvider(LocalUxBottomBarPresence provides presence) {
                if (shown) UxBottomBar("bar", "Nav", listOf(UxBarTab("a", "A", "icon-case", true)))
            }
        }
        assertTrue(presence.value)
        shown = false
        waitForIdle()
        assertEquals(false, presence.value)
    }

    // ---- EL-segmented-switch

    @Test
    fun theSwitchIsARadioGroupWithTheSelectedOptionMarkedAndAnUnavailableOptionDisabledButVisible() = runComposeUiTest {
        val changes = mutableListOf<String>()
        val options = listOf(UxSwitchOption("phone", "Telefón", true, false), UxSwitchOption("cloud", "Cloud – nedostupné offline", false, true))
        setHome(HomeSpec.IDSK) { UxSegmentedSwitch("sw", "Zobrazovať doklady z úložiska", options, labelVisible = true, onChange = { changes += it }) }
        assertEquals(listOf("Zobrazovať doklady z úložiska"), onNodeWithTag("sw").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals("Zobrazovať doklady z úložiska", drawnText("sw.label"))
        onNodeWithTag("sw.option.phone").assertIsSelected().assert(role(Role.RadioButton)).assertIsEnabled()
        onNodeWithTag("sw.option.cloud").assertIsNotSelected().assertIsNotEnabled()
        assertEquals("Cloud – nedostupné offline", drawnText("sw.option.cloud.label"), "the unavailable option stays and says why")
        onNodeWithTag("sw.option.cloud").performClick()
        assertEquals(emptyList(), changes)
        assertEquals(listOf("icon.check"), drawnAssets().map { it.id }, "idsk: the accented selected style draws the check")
    }

    @Test
    fun aChangeRaisesTheOptionIdAndThePlainStyleAndMoreThanTwoOptionsFollowTheLibrary() = runComposeUiTest {
        val changes = mutableListOf<String>()
        val two = listOf(UxSwitchOption("phone", "Telefón", true, false), UxSwitchOption("cloud", "Cloud", false, false))
        val three = two + UxSwitchOption("all", "Všetko", false, false)
        setHome(HomeSpec.EUDIW) {
            Column {
                UxSegmentedSwitch("two", "Úložisko", two, onChange = { changes += it })
                UxSegmentedSwitch("three", "Filter", three, onChange = { changes += "f-$it" })
            }
        }
        onNodeWithTag("two.option.cloud").performClick()
        onNodeWithTag("two.option.phone").performClick()
        onNodeWithTag("three.option.all").performClick()
        assertEquals(listOf("cloud", "f-all"), changes, "the selected option is not raised again")
        assertEquals(listOf("icon.check"), drawnAssets().map { it.id }, "plain: no check on the segmented thumb; the chips (auto, three options) check the selected one")
        onAllNodesWithTag("two.label", useUnmergedTree = true).assertCountEquals(0)
    }

    // ---- EL-trust-mark

    @Test
    fun aTrustMarkWithAnHrefIsALinkNamedByItsLabelAndAStaticOneIsNot() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setHome {
            Column {
                UxTrustMark("tm", UxTrustMarkVariant.Badge, "Certifikovaná peňaženka EÚ", href = "#cert", onActivate = { opened += it })
                UxTrustMark("static", UxTrustMarkVariant.Full, "Certifikovaná peňaženka EÚ")
            }
        }
        val link = onNodeWithTag("tm").fetchSemanticsNode().config
        assertEquals(Role.Button, link[SemanticsProperties.Role])
        assertEquals(listOf("Certifikovaná peňaženka EÚ"), link[SemanticsProperties.ContentDescription])
        onNodeWithTag("tm").performClick()
        assertEquals(listOf("#cert"), opened)
        onNodeWithTag("static").assertHasNoClickAction()
        assertEquals(listOf("icon.chevron-right"), drawnAssets().map { it.id }, "the export binds no trust-mark.colour: the placeholder is drawn, the link has its chevron")
        val full = HomeSpec.tokens.dp(HomeSpec.EUDIW, UxMode.Light, "comp.trust-mark.full-art-height")!!
        assertEquals(full.value, onNodeWithTag("static.art", useUnmergedTree = true).getUnclippedBoundsInRoot().height.value, 0.5f)
    }

    @Test
    fun theOfficialArtworkKeepsItsOwnColoursWhereABrandBindsIt() = runComposeUiTest {
        val assets = eu.europa.ec.ux.theme.UxAssets.parse(
            """{"brands": {"eudiw_v1": {"trust-mark.colour": {"resource": "tm", "format": "png", "paint": "image"}}}}""",
        )
        setHome(resources = HomeSpec.resources(assets = assets)) { UxTrustMark("tm", UxTrustMarkVariant.Badge, "EU", href = "#cert") }
        assertEquals(listOf(UxAssetDrawn("trust-mark.colour", "tm", null)), drawnAssets(), "an image is drawn as is, never tinted")
    }

    // ---- TPL-home

    @Test
    fun theHomeTemplateDrawsOnlyTheRegionsItIsGivenAndTheTitleSitsInTheBarsBand() = runComposeUiTest {
        setHome { HomeUnderTest(withBar = true, withCards = true, withSwitch = false) }
        listOf("home", "home.appbar", "home.header", "home.notice", "home.documents-heading", "home.cards", "home.footer", "home.actions").forEach {
            onNodeWithTag(it, useUnmergedTree = true).assertExists()
        }
        listOf("home.switch", "home.identity", "home.body").forEach { onAllNodesWithTag(it, useUnmergedTree = true).assertCountEquals(0) }
        onNodeWithTag("home.title").assert(isHeading)
        val bar = onNodeWithTag("home.appbar").getUnclippedBoundsInRoot()
        val title = onNodeWithTag("home.title").getUnclippedBoundsInRoot()
        assertTrue(title.top >= bar.top && title.bottom <= bar.bottom, "the h1 is drawn in the band: $title in $bar")
        val centre = (title.left + title.right) / 2
        assertTrue(kotlin.math.abs((centre - (bar.left + bar.right) / 2).value) <= 1f, "and centred between the controls")
    }

    @Test
    fun homeSpacingComesFromTheScreenTokens() = runComposeUiTest {
        setHome { HomeUnderTest(withBar = false, withCards = true, withSwitch = true) }
        fun t(key: String) = HomeSpec.tokens.dp(HomeSpec.EUDIW, UxMode.Light, "comp.screen.$key")!!
        val cards = onNodeWithTag("home.cards").getUnclippedBoundsInRoot()
        val first = onNodeWithTag("home.card1").getUnclippedBoundsInRoot()
        assertEquals(t("gutter").value, first.left.value, 0.5f, "the gutter is comp.screen.gutter")
        val second = onNodeWithTag("home.card2").getUnclippedBoundsInRoot()
        assertEquals(t("cards-gap").value, (second.top - first.bottom).value, 0.5f, "cards are comp.screen.cards-gap apart")
        onNodeWithTag("home.header").assertExists()
        assertTrue(onNodeWithTag("home.switch").getUnclippedBoundsInRoot().top < cards.top)
        val any = onAllNodesWithTag("home", useUnmergedTree = true).fetchSemanticsNodes().single().config
        assertNotNull(any.getOrNull(SemanticsProperties.TestTag))
    }

    @Test
    fun aBottomBarFloatsInItsInsetAndAButtonSitsInTheRaisedBand() = runComposeUiTest {
        setHome { HomeUnderTest(withBar = true, withCards = true, withSwitch = false, bottomBar = true) }
        val root = onNodeWithTag("home").getUnclippedBoundsInRoot()
        val bar = onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val inset = HomeSpec.tokens.dp(HomeSpec.EUDIW, UxMode.Light, "comp.bottom-bar.inset")!!
        assertEquals(inset.value, (root.bottom - bar.bottom).value, 0.5f, "the floating bar keeps comp.bottom-bar.inset below it")
        assertEquals(inset.value, (bar.left - root.left).value, 0.5f)
    }

    // ---- app bar gaps, avatar, lock

    @Test
    fun theAppBarKeepsItsPaddingColumnGapAndCentresItsTitleFromTheTokens() = runComposeUiTest {
        setHome { UxAppBar("ab", UxBarLeading.Menu, leadingLabel = "Menu", title = "eDoklady", trailing = UxBarTrailing.Notifications, trailingLabel = "Bell", badge = true) }
        val bar = onNodeWithTag("ab").getUnclippedBoundsInRoot()
        val lead = onNodeWithTag("ab.leading").getUnclippedBoundsInRoot()
        val padding = HomeSpec.tokens.dp(HomeSpec.EUDIW, UxMode.Light, "comp.app-bar.padding-inline")!!
        assertEquals(padding.value, (lead.left - bar.left).value, 0.5f)
        val title = onNodeWithTag("ab.title").getUnclippedBoundsInRoot()
        assertTrue(kotlin.math.abs((((title.left + title.right) / 2) - ((bar.left + bar.right) / 2)).value) <= 1f, "the title is centred")
        assertEquals(HomeSpec.tokens.dp(HomeSpec.EUDIW, UxMode.Light, "comp.app-bar.min-height")!!.value, bar.height.value, 0.5f)
    }

    @Test
    fun anOrganisationWithNoLogoGetsTheApartmentAvatarAndAMandatoryItemTheLock() = runComposeUiTest {
        setHome {
            Column {
                UxRpIdentity("rp", "Acme", UxTrust.Trusted, "Overený")
                UxAttributeList(
                    "al",
                    listOf(UxAttributeItem("a", "Meno", UxRequirement.Mandatory, "Ján"), UxAttributeItem("b", "Telefón", UxRequirement.Optional, "0900")),
                    mandatoryLabel = "Povinné",
                    optionalLabel = "Voliteľné",
                )
            }
        }
        onNodeWithTag("rp.avatar", useUnmergedTree = true).assertExists()
        val ids = drawnAssets().map { it.id }
        assertTrue("icon.apartment" in ids, "$ids")
        assertEquals(1, ids.count { it == "icon.lock" }, "only the mandatory item is locked: $ids")
    }
}

/** A Home screen of neutral stand-ins (no screen id, no flow). */
@androidx.compose.runtime.Composable
private fun HomeUnderTest(withBar: Boolean, withCards: Boolean, withSwitch: Boolean, bottomBar: Boolean = false) {
    TplHome(
        id = "home",
        header = { UxText("home.title", "eDoklady", UxTextVariant.Title) },
        appbar = if (withBar) ({ UxAppBar("home.bar", UxBarLeading.Menu, leadingLabel = "Menu", trailing = UxBarTrailing.Notifications, trailingLabel = "Bell") }) else null,
        switch = if (withSwitch) ({ UxSegmentedSwitch("home.sw", "Úložisko", listOf(UxSwitchOption("a", "A", true, false), UxSwitchOption("b", "B", false, false))) }) else null,
        notice = { UxNotice("home.n", UxNoticeTone.Info, "Offline") },
        documentsHeading = {
            UxText("home.h2", "Moje doklady", UxTextVariant.Heading)
            UxLink("home.all", "Všetky", "#all", {})
        },
        cards = if (withCards) {
            {
                UxDocumentCard("home.card1", UxCardVariant.Full, UxDocType.IdCard, "Občiansky preukaz", UxDocStatus.Valid, "Platný")
                UxDocumentCard("home.card2", UxCardVariant.Full, UxDocType.DrivingLicence, "Vodičský preukaz", UxDocStatus.Valid, "Platný")
            }
        } else {
            null
        },
        footer = { UxLink("home.history", "História", "#log", {}) },
        actions = {
            if (bottomBar) {
                UxBottomBar("bar", "Nav", listOf(UxBarTab("a", "A", "icon-case", true)), action = UxBarAction.Menu, actionLabel = "Akcie")
            } else {
                UxButton("home.present", "Predložiť", UxButtonVariant.Primary, {})
            }
        },
    )
}
