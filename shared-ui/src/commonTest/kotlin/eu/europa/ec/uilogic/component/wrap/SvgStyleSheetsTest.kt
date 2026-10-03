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

package eu.europa.ec.uilogic.component.wrap

import kotlin.test.Test
import kotlin.test.assertEquals

class SvgStyleSheetsTest {

    @Test
    fun a_class_rule_becomes_the_elements_style() {
        val svg = """<svg><style type="text/css">.st1{fill:#034DA1;}</style><path class="st1" d="M0,0"/></svg>"""

        assertEquals(
            """<svg><style type="text/css">.st1{fill:#034DA1;}</style><path style="fill:#034DA1;" class="st1" d="M0,0"/></svg>""",
            inlineSvgClassStyles(svg),
        )
    }

    @Test
    fun the_ec_logo_shape_colours_every_classed_element() {
        val svg = """
            <svg><style type="text/css">	.st0{fill:#BBBDBF;}	.st1{fill:#034DA1;}	.st2{fill:#FFF100;}	.st3{enable-background:new    ;}</style>
            <path id="Fill-40" class="st0" d="M0,24"/><rect class="st1" width="2"/><polygon class="st2" points="1,1"/><g class="st3"></g></svg>
        """.trimIndent()

        val inlined = inlineSvgClassStyles(svg)

        listOf(
            """<path id="Fill-40" style="fill:#BBBDBF;" class="st0"""",
            """<rect style="fill:#034DA1;" class="st1"""",
            """<polygon style="fill:#FFF100;" class="st2"""",
            """<g style="enable-background:new;" class="st3">""",
        ).forEach { expected -> assertEquals(true, expected in inlined, "missing: $expected") }
    }

    @Test
    fun of_two_matching_rules_the_later_wins_whatever_the_class_order() {
        val svg = """<svg><style>.a{fill:red;}.b{fill:blue;stroke:green}</style><rect class="b a"/></svg>"""

        assertEquals(
            """<svg><style>.a{fill:red;}.b{fill:blue;stroke:green}</style><rect style="fill:red;fill:blue;stroke:green;" class="b a"/></svg>""",
            inlineSvgClassStyles(svg),
        )
    }

    @Test
    fun the_elements_own_style_stays_last_so_it_still_wins() {
        val svg = """<svg><style>.a{fill:red;}</style><rect style="fill:black" class="a"/></svg>"""

        assertEquals(
            """<svg><style>.a{fill:red;}</style><rect style="fill:red;fill:black" class="a"/></svg>""",
            inlineSvgClassStyles(svg),
        )
    }

    @Test
    fun grouped_class_selectors_cdata_and_comments_are_understood() {
        val svg = """<svg><style><![CDATA[ /* brand */ .a, .b { fill: #fff } ]]></style><rect class="a"/><circle class='b'/></svg>"""

        val inlined = inlineSvgClassStyles(svg)

        assertEquals(true, """<rect style="fill: #fff;" class="a"/>""" in inlined, inlined)
        assertEquals(true, """<circle style="fill: #fff;" class='b'/>""" in inlined, inlined)
    }

    @Test
    fun anything_but_plain_class_rules_is_left_to_the_renderer() {
        val svgs = listOf(
            """<svg><rect class="a"/></svg>""",
            """<svg><style>rect{fill:red}#x{fill:red}.a .b{fill:red}.a:hover{fill:red}</style><rect id="x" class="a b"/></svg>""",
            """<svg><style>.a{font-family:"Open Sans"}</style><text class="a">x</text></svg>""",
            """<svg><style>.a{fill:red}</style><rect data-class="a"/></svg>""",
        )

        svgs.forEach { svg -> assertEquals(svg, inlineSvgClassStyles(svg)) }
    }
}
