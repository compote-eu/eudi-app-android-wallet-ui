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


package eu.europa.ec.ux.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.europa.ec.ux.theme.generated.UxBrandConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** M5c (OR-2), re-run at M6a on the generated eudiw_v1: the shell holds elevation, fonts, `comp.*` and the modes. */
class UxTokensShellTest {
    private val brand = UxBrandConfig.eudiwV1

    @Test
    fun theExportedModesAreBoundAndDarkFallsBack() {
        // V19 (M6a): the export binds light and high-contrast only (R11); the M5c provisional set also had both dark
        // modes, which now fall back as the resolver says.
        val pages = UxMode.entries.map { brand.tokens(it).colors.surfacePage }
        assertEquals(
            listOf(Color(0xFFFAFAFA), Color(0xFFFFFFFF), Color(0xFFFAFAFA), Color(0xFFFFFFFF)),
            pages,
            "light, high-contrast, dark (-> light), dark-high-contrast (-> high-contrast) page grounds",
        )
        assertSame(brand.light.tokens, brand.tokens(UxMode.Dark))
        assertSame(brand.highContrast.tokens, brand.tokens(UxMode.DarkHighContrast))
        assertEquals(4, UxMode.entries.size)
    }

    @Test
    fun highContrastIsBlackOnWhiteWhereTheSpecSaysSo() {
        val hc = brand.tokens(UxMode.HighContrast).colors
        assertEquals(Color(0xFF000000), hc.textSecondary)
        assertEquals(Color(0xFF000000), hc.borderDefault)
        assertNotEquals(brand.tokens(UxMode.Light).colors.textSecondary, hc.textSecondary)
    }

    @Test
    fun anUnboundOptionalModeFallsBackAsTheResolverSays() {
        val bare = UxBrand("bare", light = brand.light, highContrast = brand.highContrast)
        assertSame(bare.light.tokens, bare.tokens(UxMode.Dark))
        assertSame(bare.highContrast.tokens, bare.tokens(UxMode.DarkHighContrast))
    }

    @Test
    fun elevationIsPerModeAndMatchesTheSpecShadows() {
        val light = brand.tokens(UxMode.Light).elevation
        assertEquals(1, light.sheet.layers.size)
        assertEquals(40.dp, light.sheet.layers.single().blur)
        assertEquals(-10.dp, light.sheet.layers.single().offsetY)
        assertEquals(Color(0x40000000), light.sheet.layers.single().color)
        assertTrue(light.card.layers.single().inset, "the card ring is inset in light")
        assertEquals(0.5.dp, light.bar.layers.single().spread)
        assertEquals(2, light.floating.layers.size)
        val hcCard = brand.tokens(UxMode.HighContrast).elevation.card.layers.single()
        assertEquals(Color(0xFF000000), hcCard.color)
        assertEquals(false, hcCard.inset, "the high-contrast card ring is an outer black ring")
        assertEquals(20.dp, light.sheet.layers.single().approximateElevation)
    }

    @Test
    fun fontFamilyAndWeightsFollowTheSysTokensAndTheModes() {
        val light = brand.tokens(UxMode.Light)
        assertEquals(FontWeight.Bold, light.font.bold)
        assertEquals(FontWeight.SemiBold, light.font.status)
        assertEquals(FontWeight.Bold, brand.tokens(UxMode.HighContrast).font.status, "sys.font.weight.status is 700 in high contrast")
        assertEquals(light.font.heading, light.type.title.fontFamily)
        assertEquals(light.font.base, light.type.body.fontFamily)
        assertEquals(light.font.bold, light.type.title.fontWeight)
        assertEquals(light.font.regular, light.type.body.fontWeight)
    }

    @Test
    fun componentTokensCarryTheSpecValuesAndAliasTheSysElevations() {
        val t = brand.tokens(UxMode.Light)
        assertEquals(64.dp, t.comp.appBar.minHeight)
        assertEquals(8.dp, t.comp.appBar.badgeSize)
        assertEquals(t.elevation.bar, t.comp.appBar.shadow)
        assertEquals(t.elevation.sheet, t.comp.sheet.shadow)
        assertEquals(t.elevation.floating, t.comp.button.iconShadow)
        assertEquals(t.elevation.floatingAction, t.comp.button.floatingShadow)
        assertEquals(FontWeight.Bold, t.comp.rpIdentity.nameWeight)
        assertEquals(FontWeight.SemiBold, t.comp.attributeList.valueWeight)
        assertEquals(5.dp, t.comp.link.gap)
    }
}
