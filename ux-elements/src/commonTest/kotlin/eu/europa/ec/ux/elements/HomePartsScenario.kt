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
import androidx.compose.ui.test.ComposeUiTest
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
 *
 * M9r (spike rule T1): no brand, asset id, region or copy of the spec is written here. A test that depends on a brand's
 * selection runs for every exported brand and expects what that brand's selection and bindings say (`HomeSpec.part`,
 * `HomeSpec.selection`); the labels are test-owned stand-ins, not spec copy; the template's regions come from the library.
 */
@OptIn(ExperimentalTestApi::class)
abstract class HomePartsScenario {

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    /** Runs [block] once per exported brand, each in its own composition. */
    private fun forEachBrand(block: ComposeUiTest.(brand: String) -> Unit) {
        assertTrue(HomeSpec.brands.isNotEmpty())
        HomeSpec.brands.forEach { brand -> runComposeUiTest { block(brand) } }
    }

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
    fun theBrandsMarkStyleDecidesBetweenADotAndTheToneGlyphItBinds() = forEachBrand { brand ->
        val statuses = listOf(UxChipStatus.Expiring, UxChipStatus.Valid, UxChipStatus.Upcoming, UxChipStatus.Info)
        setHome(brand) { Column { statuses.forEach { UxStatusChip("c-${it.name}", it, "Label ${it.name}") } } }
        val dot = HomeSpec.selection(brand, "EL-status-chip.mark") == "dot"
        statuses.forEach { st ->
            val hasDot = dot && st.tone != UxChipTone.Neutral
            onAllNodesWithTag("c-${st.name}.dot", useUnmergedTree = true).assertCountEquals(if (hasDot) 1 else 0)
        }
        val glyphs = if (dot) emptyList() else statuses.filter { it.tone != UxChipTone.Neutral }.mapNotNull { HomeSpec.part(brand, "EL-status-chip", "tone glyph", it.name.lowercase()) }
        assertEquals(glyphs, drawnAssets().map { it.id }, "$brand: the dot mark draws no glyph; the icon mark draws only what the brand binds (DEC-080)")
        val config = onNodeWithTag("c-Info").fetchSemanticsNode().config
        assertNull(config.getOrNull(SemanticsProperties.Role), "inline text: no role")
        assertNull(config.getOrNull(SemanticsProperties.Heading))
        assertEquals(listOf("Label Info"), config[SemanticsProperties.Text].map { it.text })
    }

    @Test
    fun chipColoursComeFromTheExportedTable() = runComposeUiTest {
        var read = emptyList<Pair<Boolean, Boolean>>()
        setHome {
            val tokens = UxThemeAccess.tokens
            val bg = chipColors(UxChipTone.Success).bg
            val fb = tokens.colors.feedbackSuccess.bg
            val table = HomeSpec.tokens.color(HomeSpec.defaultBrand, UxMode.Light, "comp.status-chip.success-bg")
            read = listOf((bg == (table ?: fb)) to (bg == fb))
        }
        assertEquals(true, read.single().first, "the chip's colour is the table's (the shell's role when the table has none)")
    }

    // ---- EL-document-card

    @Test
    fun theHolderCardIsOneButtonNamedByHolderAndStatusWithDecorativeAvatarDotAndChevron() = runComposeUiTest {
        var opened = 0
        setHome {
            UxDocumentCard(
                "h", UxCardVariant.Holder, UxDocType.Pid, "Doc title", UxDocStatus.Valid,
                "Doc title, valid", holder = "Holder Name", initials = "HN", onOpen = { opened++ },
            )
        }
        val config = onNodeWithTag("h").fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals(listOf("Holder Name, Doc title, valid"), config[SemanticsProperties.ContentDescription])
        assertEquals("HN", drawnText("h.initials"))
        onNodeWithTag("h.dot", useUnmergedTree = true).assertExists()
        assertEquals(
            listOfNotNull(HomeSpec.part(HomeSpec.defaultBrand, "EL-document-card", "chevron")),
            drawnAssets().map { it.id },
            "the chevron (when the brand binds it) is the only asset of a holder row",
        )
        onNodeWithTag("h").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aFullCardFollowsTheBrandsCardFace() = forEachBrand { brand ->
        val centered = HomeSpec.selection(brand, "card-face") == "centered"
        setHome(brand) {
            UxDocumentCard(
                "c", UxCardVariant.Full, UxDocType.DrivingLicence, "Doc title", UxDocStatus.Expiring, "Status text",
                titleEn = "DOC EN", holder = "Holder Name", number = "NO 0000", storage = UxStorage.Cloud, storageLabel = "Storage label",
            )
        }
        assertEquals("Doc title", drawnText("c.title"))
        assertEquals("NO 0000", drawnText("c.number"))
        assertEquals("Status text", drawnText("c.status"))
        val config = onNodeWithTag("c").fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        val art = HomeSpec.part(brand, "EL-document-card", "card art", UxDocType.DrivingLicence.wire)
        val glyph = if (HomeSpec.selection(brand, "EL-status-chip.mark") == "dot") null else HomeSpec.part(brand, "EL-status-chip", "tone glyph", "expiring")
        if (centered) {
            // Title, English name and number centred on the artwork; no holder, no storage.
            assertEquals("DOC EN", drawnText("c.title-en"))
            listOf("c.holder", "c.storage").forEach { onAllNodesWithTag(it, useUnmergedTree = true).assertCountEquals(0) }
            assertEquals(listOf("Doc title, Status text, NO 0000"), config[SemanticsProperties.ContentDescription])
            assertEquals(listOfNotNull(art, glyph).sorted(), drawnAssets().map { it.id }.sorted(), brand)
            if (art != null) {
                val box = onNodeWithTag("c").getUnclippedBoundsInRoot()
                assertTrue(box.height.value + 1f >= box.width.value * HomeSpec.ART.height / HomeSpec.ART.width, "the card keeps the artwork's proportion: $box")
            }
        } else {
            // Band: holder, number and storage, named after the status; no English name.
            assertEquals("Holder Name", drawnText("c.holder"))
            assertTrue(onNodeWithTag("c.holder", useUnmergedTree = true).getUnclippedBoundsInRoot().height.value > 0f, "the body is not collapsed")
            assertEquals("Storage label", drawnText("c.storage-label"))
            onAllNodesWithTag("c.title-en", useUnmergedTree = true).assertCountEquals(0)
            assertEquals(listOf("Doc title, Status text, Holder Name, NO 0000, Storage label"), config[SemanticsProperties.ContentDescription])
            val storage = HomeSpec.part(brand, "EL-document-card", "storage glyph", UxStorage.Cloud.name.lowercase())
            assertEquals(listOfNotNull(art, glyph, storage).sorted(), drawnAssets().map { it.id }.sorted(), "$brand: only what the brand binds is drawn")
        }
    }

    @Test
    fun theHeroCardIsDecorativeAndNeverInteractiveAndAMiniCardIsARowWithAChevron() = runComposeUiTest {
        var opened = 0
        setHome {
            Column {
                UxDocumentCard("hero", UxCardVariant.Hero, UxDocType.IdCard, "Doc title", UxDocStatus.Valid, "Valid", onOpen = { opened++ })
                UxDocumentCard("mini", UxCardVariant.Mini, UxDocType.IdCard, "Doc title", UxDocStatus.Revoked, "Revoked", onOpen = { opened++ })
                UxDocumentCard("still", UxCardVariant.Full, UxDocType.IdCard, "Doc title", UxDocStatus.Valid, "Valid", interactive = false)
            }
        }
        onNodeWithTag("hero").assertHasNoClickAction()
        onNodeWithTag("still").assertHasNoClickAction()
        assertNull(onNodeWithTag("hero").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        HomeSpec.tokens.dp(HomeSpec.defaultBrand, UxMode.Light, "comp.document-card.hero-width")?.let {
            assertEquals(it, onNodeWithTag("hero").getUnclippedBoundsInRoot().width)
        }
        onNodeWithTag("mini").assert(role(Role.Button))
        onNodeWithTag("mini").performClick()
        onNodeWithTag("hero").performClick()
        assertEquals(1, opened, "only the mini card opens")
        assertEquals("Revoked", drawnText("mini.status"))
    }

    // ---- EL-bottom-bar

    @Test
    fun theBottomBarIsANamedGroupOfButtonsWithTheCurrentOneSelectedAndANamedActionButton() = runComposeUiTest {
        val events = mutableListOf<String>()
        val tabs = listOf(UxBarTab("documents", "Tab A", "icon-id-card", true), UxBarTab("services", "Tab B", "icon-case", false))
        setHome {
            UxBottomBar("bar", "Main navigation", tabs, action = UxBarAction.Menu, actionLabel = "Actions", onSelect = { events += "select $it" }, onAction = { events += "action" })
        }
        assertEquals(listOf("Main navigation"), onNodeWithTag("bar.tabs").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        onNodeWithTag("bar.tab.documents").assertIsSelected().assert(role(Role.Button))
        onNodeWithTag("bar.tab.services").assertIsNotSelected().assert(role(Role.Button))
        assertEquals(listOf("Tab B"), onNodeWithTag("bar.tab.services").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        onNodeWithTag("bar.tab.documents").performClick()
        onNodeWithTag("bar.tab.services").performClick()
        onNodeWithTag("bar.action").performClick()
        assertEquals(listOf("select services", "action"), events, "pressing the current destination does nothing")
        val action = onNodeWithTag("bar.action").fetchSemanticsNode().config
        assertEquals(listOf("Actions"), action[SemanticsProperties.ContentDescription])
        assertTrue(SemanticsActions.Expand in action, "the action advertises the menu it opens")
        val b = HomeSpec.defaultBrand
        assertEquals(
            (listOfNotNull(HomeSpec.part(b, "EL-bottom-bar", "action button")) + tabs.mapNotNull { HomeSpec.part(b, "EL-bottom-bar", "destination icon", it.icon) }).sorted(),
            drawnAssets().map { it.id }.sorted(),
            "a tab's icon is the library's asset of its icon field, when the brand binds it",
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
        val options = listOf(UxSwitchOption("phone", "Option A", true, false), UxSwitchOption("cloud", "Option B, unavailable", false, true))
        setHome { UxSegmentedSwitch("sw", "Switch label", options, labelVisible = true, onChange = { changes += it }) }
        assertEquals(listOf("Switch label"), onNodeWithTag("sw").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals("Switch label", drawnText("sw.label"))
        onNodeWithTag("sw.option.phone").assertIsSelected().assert(role(Role.RadioButton)).assertIsEnabled()
        onNodeWithTag("sw.option.cloud").assertIsNotSelected().assertIsNotEnabled()
        assertEquals("Option B, unavailable", drawnText("sw.option.cloud.label"), "the unavailable option stays and says why")
        onNodeWithTag("sw.option.cloud").performClick()
        assertEquals(emptyList(), changes)
    }

    @Test
    fun aChangeRaisesTheOptionIdAndTheSelectedStyleIsTheBrands() = forEachBrand { brand ->
        val changes = mutableListOf<String>()
        val two = listOf(UxSwitchOption("phone", "A", true, false), UxSwitchOption("cloud", "B", false, false))
        val three = two + UxSwitchOption("all", "C", false, false)
        setHome(brand) {
            Column {
                UxSegmentedSwitch("two", "Store", two, onChange = { changes += it })
                UxSegmentedSwitch("three", "Filter", three, onChange = { changes += "f-$it" })
            }
        }
        onNodeWithTag("two.option.cloud").performClick()
        onNodeWithTag("two.option.phone").performClick()
        onNodeWithTag("three.option.all").performClick()
        assertEquals(listOf("cloud", "f-all"), changes, "the selected option is not raised again")
        // The segmented thumb draws the check only in the accented style; the chips (three options) always check the selected one.
        val check = HomeSpec.part(brand, "EL-segmented-switch", "selected check")
        val accented = HomeSpec.selection(brand, "EL-segmented-switch.selected") != "plain"
        val expected = listOfNotNull(check.takeIf { accented }, check)
        assertEquals(expected, drawnAssets().map { it.id }, "$brand: ${if (accented) "accented" else "plain"} style")
        onAllNodesWithTag("two.label", useUnmergedTree = true).assertCountEquals(0)
    }

    // ---- EL-trust-mark

    @Test
    fun aTrustMarkWithAnHrefIsALinkNamedByItsLabelAndAStaticOneIsNot() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setHome {
            Column {
                UxTrustMark("tm", UxTrustMarkVariant.Badge, "Trust label", href = "#cert", onActivate = { opened += it })
                UxTrustMark("static", UxTrustMarkVariant.Full, "Trust label")
            }
        }
        val link = onNodeWithTag("tm").fetchSemanticsNode().config
        assertEquals(Role.Button, link[SemanticsProperties.Role])
        assertEquals(listOf("Trust label"), link[SemanticsProperties.ContentDescription])
        onNodeWithTag("tm").performClick()
        assertEquals(listOf("#cert"), opened)
        onNodeWithTag("static").assertHasNoClickAction()
        val b = HomeSpec.defaultBrand
        val mark = HomeSpec.part(b, "EL-trust-mark", "mark")
        assertEquals(
            listOfNotNull(mark, HomeSpec.part(b, "EL-trust-mark", "chevron"), mark),
            drawnAssets().map { it.id },
            "the mark where the brand binds it (else the placeholder), and the link's chevron",
        )
        HomeSpec.tokens.dp(b, UxMode.Light, "comp.trust-mark.full-art-height")?.takeIf { mark == null }?.let { full ->
            assertEquals(full.value, onNodeWithTag("static.art", useUnmergedTree = true).getUnclippedBoundsInRoot().height.value, 0.5f)
        }
    }

    @Test
    fun theOfficialArtworkKeepsItsOwnColoursWhereABrandBindsIt() = runComposeUiTest {
        val assets = eu.europa.ec.ux.theme.UxAssets.parse(
            """{"brands": {"${HomeSpec.defaultBrand}": {"trust-mark.colour": {"resource": "tm", "format": "png", "paint": "image"}}}}""",
        )
        val id = HomeSpec.library.assetId("EL-trust-mark", "mark") ?: return@runComposeUiTest
        setHome(resources = HomeSpec.resources(assets = assets)) { UxTrustMark("tm", UxTrustMarkVariant.Badge, "EU", href = "#cert") }
        if (id == "trust-mark.colour") {
            assertEquals(listOf(UxAssetDrawn(id, "tm", null)), drawnAssets(), "an image is drawn as is, never tinted")
        }
    }

    // ---- TPL-home

    /** The regions of TPL-home the library lists (`ux-library.json` `templates`), read at test time. */
    private val homeRegions: List<String> by lazy {
        val t = kotlinx.serialization.json.Json.parseToJsonElement(eu.europa.ec.ux.elements.fixture.UxLibrarySnapshot.JSON)
        ((t as kotlinx.serialization.json.JsonObject)["templates"] as? kotlinx.serialization.json.JsonObject)
            ?.get("TPL-home")?.let { (it as kotlinx.serialization.json.JsonObject)["regions"] as? kotlinx.serialization.json.JsonArray }
            ?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty()
    }

    @Test
    fun theHomeTemplateDrawsOnlyTheRegionsItIsGivenAndTheTitleSitsInTheBarsBand() = runComposeUiTest {
        setHome { HomeUnderTest(withBar = true, withCards = true, withSwitch = false) }
        val given = setOf("appbar", "header", "notice", "documents-heading", "cards", "footer", "actions")
        onNodeWithTag("home", useUnmergedTree = true).assertExists()
        homeRegions.forEach { region ->
            onAllNodesWithTag("home.$region", useUnmergedTree = true).assertCountEquals(if (region in given) 1 else 0)
        }
        onNodeWithTag("home.title").assert(isHeading)
        if ("appbar" in homeRegions) {
            val bar = onNodeWithTag("home.appbar").getUnclippedBoundsInRoot()
            val title = onNodeWithTag("home.title").getUnclippedBoundsInRoot()
            assertTrue(title.top >= bar.top && title.bottom <= bar.bottom, "the h1 is drawn in the band: $title in $bar")
            val centre = (title.left + title.right) / 2
            assertTrue(kotlin.math.abs((centre - (bar.left + bar.right) / 2).value) <= 1f, "and centred between the controls")
        }
    }

    @Test
    fun homeSpacingComesFromTheScreenTokens() = runComposeUiTest {
        setHome { HomeUnderTest(withBar = false, withCards = true, withSwitch = true) }
        fun t(key: String) = HomeSpec.tokens.dp(HomeSpec.defaultBrand, UxMode.Light, "comp.screen.$key")
        val cards = onNodeWithTag("home.cards").getUnclippedBoundsInRoot()
        val first = onNodeWithTag("home.card1").getUnclippedBoundsInRoot()
        t("gutter")?.let { assertEquals(it.value, first.left.value, 0.5f, "the gutter is comp.screen.gutter") }
        val second = onNodeWithTag("home.card2").getUnclippedBoundsInRoot()
        t("cards-gap")?.let { assertEquals(it.value, (second.top - first.bottom).value, 0.5f, "cards are comp.screen.cards-gap apart") }
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
        val inset = HomeSpec.tokens.dp(HomeSpec.defaultBrand, UxMode.Light, "comp.bottom-bar.inset") ?: return@runComposeUiTest
        assertEquals(inset.value, (root.bottom - bar.bottom).value, 0.5f, "the floating bar keeps comp.bottom-bar.inset below it")
        assertEquals(inset.value, (bar.left - root.left).value, 0.5f)
    }

    // ---- app bar gaps, avatar, lock

    @Test
    fun theAppBarKeepsItsPaddingColumnGapAndCentresItsTitleFromTheTokens() = runComposeUiTest {
        setHome { UxAppBar("ab", UxBarLeading.Menu, leadingLabel = "Menu", title = "Title", trailing = UxBarTrailing.Notifications, trailingLabel = "Bell", badge = true) }
        val bar = onNodeWithTag("ab").getUnclippedBoundsInRoot()
        val lead = onNodeWithTag("ab.leading").getUnclippedBoundsInRoot()
        HomeSpec.tokens.dp(HomeSpec.defaultBrand, UxMode.Light, "comp.app-bar.padding-inline")?.let { assertEquals(it.value, (lead.left - bar.left).value, 0.5f) }
        val title = onNodeWithTag("ab.title").getUnclippedBoundsInRoot()
        assertTrue(kotlin.math.abs((((title.left + title.right) / 2) - ((bar.left + bar.right) / 2)).value) <= 1f, "the title is centred")
        HomeSpec.tokens.dp(HomeSpec.defaultBrand, UxMode.Light, "comp.app-bar.min-height")?.let { assertEquals(it.value, bar.height.value, 0.5f) }
    }

    @Test
    fun anOrganisationWithNoLogoGetsItsAvatarAndAMandatoryItemItsLock() = runComposeUiTest {
        setHome {
            Column {
                UxRpIdentity("rp", "Org", UxTrust.Trusted, "Trusted")
                UxAttributeList(
                    "al",
                    listOf(UxAttributeItem("a", "Name", UxRequirement.Mandatory, "Value"), UxAttributeItem("b", "Phone", UxRequirement.Optional, "0000")),
                    mandatoryLabel = "Mandatory",
                    optionalLabel = "Optional",
                )
            }
        }
        onNodeWithTag("rp.avatar", useUnmergedTree = true).assertExists()
        val ids = drawnAssets().map { it.id }
        val b = HomeSpec.defaultBrand
        HomeSpec.part(b, "EL-rp-identity", "organisation avatar")?.let { assertTrue(it in ids, "$ids") }
        val lock = HomeSpec.part(b, "EL-attribute-item", "mandatory lock")
        assertEquals(if (lock == null) 0 else 1, ids.count { it == lock }, "only the mandatory item is locked: $ids")
    }
}

/** A Home screen of neutral stand-ins (no screen id, no flow, no spec copy). */
@androidx.compose.runtime.Composable
private fun HomeUnderTest(withBar: Boolean, withCards: Boolean, withSwitch: Boolean, bottomBar: Boolean = false) {
    TplHome(
        id = "home",
        header = { UxText("home.title", "Home title", UxTextVariant.Title) },
        appbar = if (withBar) ({ UxAppBar("home.bar", UxBarLeading.Menu, leadingLabel = "Menu", trailing = UxBarTrailing.Notifications, trailingLabel = "Bell") }) else null,
        switch = if (withSwitch) ({ UxSegmentedSwitch("home.sw", "Store", listOf(UxSwitchOption("a", "A", true, false), UxSwitchOption("b", "B", false, false))) }) else null,
        notice = { UxNotice("home.n", UxNoticeTone.Info, "Offline") },
        documentsHeading = {
            UxText("home.h2", "Documents", UxTextVariant.Heading)
            UxLink("home.all", "All", "#all", {})
        },
        cards = if (withCards) {
            {
                UxDocumentCard("home.card1", UxCardVariant.Full, UxDocType.IdCard, "Card one", UxDocStatus.Valid, "Valid")
                UxDocumentCard("home.card2", UxCardVariant.Full, UxDocType.DrivingLicence, "Card two", UxDocStatus.Valid, "Valid")
            }
        } else {
            null
        },
        footer = { UxLink("home.history", "History", "#log", {}) },
        actions = {
            if (bottomBar) {
                UxBottomBar("bar", "Nav", listOf(UxBarTab("a", "A", "icon-case", true)), action = UxBarAction.Menu, actionLabel = "Actions")
            } else {
                UxButton("home.present", "Present", UxButtonVariant.Primary, {})
            }
        },
    )
}
