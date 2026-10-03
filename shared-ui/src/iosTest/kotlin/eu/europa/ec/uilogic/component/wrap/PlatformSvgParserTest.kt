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

// What the iOS SVG path actually draws. Skia ignores `<style>`, so a class-coloured drawing parsed as it is
// renders black; through `platformSvgParser` it renders in its colour. The first case pins the renderer's
// limitation itself, so if a Skia update starts applying stylesheets this test says the inliner can go.
package eu.europa.ec.uilogic.component.wrap

import coil3.annotation.ExperimentalCoilApi
import coil3.svg.Svg
import coil3.toBitmap
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoilApi::class)
class PlatformSvgParserTest {

    private val classStyledSquare =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10" width="10" height="10">""" +
            """<style type="text/css">.st1{fill:#034DA1;}</style><rect class="st1" width="10" height="10"/></svg>"""

    private fun centreColourOf(parser: Svg.Parser): Int {
        val svg = parser.parse(Buffer().writeUtf8(classStyledSquare))
        return svg.asImage(10, 10).toBitmap().getColor(5, 5)
    }

    @Test
    fun skia_alone_draws_a_class_styled_shape_black() {
        assertEquals(OPAQUE_BLACK, centreColourOf(Svg.Parser.DEFAULT))
    }

    @Test
    fun the_platform_parser_draws_it_in_its_class_colour() {
        assertEquals(EU_BLUE, centreColourOf(platformSvgParser))
    }

    private companion object {
        const val OPAQUE_BLACK = 0xFF000000.toInt()
        const val EU_BLUE = 0xFF034DA1.toInt()
    }
}
