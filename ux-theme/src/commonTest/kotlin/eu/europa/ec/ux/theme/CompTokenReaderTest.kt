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
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M5e: the `comp.*` readers beside the dimension reader (colour, font size, weight, shadow) and the brand selections. */
class CompTokenReaderTest {
    private val table = UxComponentTokenTable.parse(
        """{"values": {"b": {
          "light": {
            "comp.x.bg": {"type": "color", "value": {"colorSpace": "srgb", "components": [1.0, 0.0, 0.0], "alpha": 0.5, "hex": "#ff0000"}},
            "comp.x.size": {"type": "dimension", "value": {"unit": "px", "value": 18}},
            "comp.x.weight": {"type": "fontWeight", "value": 700},
            "comp.x.shadow": {"type": "shadow", "value": [{"blur": {"unit": "px", "value": 8}, "spread": {"unit": "px", "value": 1},
              "offsetX": {"unit": "px", "value": 0}, "offsetY": {"unit": "px", "value": 4}, "inset": true,
              "color": {"colorSpace": "srgb", "components": [0.0, 0.0, 1.0], "alpha": 1}}]}
          },
          "high-contrast": {"comp.x.bg": {"type": "color", "value": {"colorSpace": "srgb", "components": [0.0, 0.0, 0.0], "hex": "#000000"}}}
        }}}""",
    )

    @Test
    fun readsEachTokenTypeAndNothingOfAnotherType() {
        assertEquals(Color(1f, 0f, 0f, 0.5f), table.color("b", UxMode.Light, "comp.x.bg"))
        assertEquals(18.sp, table.sp("b", UxMode.Light, "comp.x.size"))
        assertEquals(18.dp, table.dp("b", UxMode.Light, "comp.x.size"))
        assertEquals(FontWeight(700), table.weight("b", UxMode.Light, "comp.x.weight"))
        val layer = table.shadow("b", UxMode.Light, "comp.x.shadow")!!.layers.single()
        assertEquals(listOf(8.dp, 1.dp, 4.dp), listOf(layer.blur, layer.spread, layer.offsetY))
        assertEquals(true, layer.inset)
        assertEquals(Color(0f, 0f, 1f, 1f), layer.color)
        assertNull(table.color("b", UxMode.Light, "comp.x.size"))
        assertNull(table.weight("b", UxMode.Light, "comp.x.bg"))
        assertNull(table.color("other", UxMode.Light, "comp.x.bg"))
    }

    @Test
    fun aModeTheExportDoesNotBindFallsBackAsTheResolverSays() {
        assertEquals(Color(0f, 0f, 0f, 1f), table.color("b", UxMode.DarkHighContrast, "comp.x.bg"))
        assertEquals(Color(1f, 0f, 0f, 0.5f), table.color("b", UxMode.Dark, "comp.x.bg"))
    }

    @Test
    fun brandSelectionsAnswerByBrandAndKey() {
        val selections = UxBrandSelections.parse("""{"brand_selections": {"b": {"EL-status-chip.mark": "dot"}}}""")
        assertEquals("dot", selections.of("b", "EL-status-chip.mark"))
        assertNull(selections.of("b", "card-face"))
        assertNull(selections.of("c", "EL-status-chip.mark"))
    }
}
