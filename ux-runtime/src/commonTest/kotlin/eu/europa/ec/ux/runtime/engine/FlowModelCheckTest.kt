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

// M1b, re-run at M6a on the real export (was provisional on the boundary fixture): D5-engine. The bounded model check
// over the exported bundle (L58), and over a pre-fix variant.
package eu.europa.ec.ux.runtime.engine

import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.QR_URI
import eu.europa.ec.ux.runtime.support.Results
import eu.europa.ec.ux.runtime.support.int
import eu.europa.ec.ux.runtime.support.json
import eu.europa.ec.ux.runtime.support.with
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The L58 property (analysis repo `evaluation/kmp-ui-from-spec/spike-plan.md` §5.4): with event sequences up to
 * depth 8 and `selected_count` in {0, 1, 2}, no path reaches `auth` with `selected_count == 0`.
 */
class FlowModelCheckTest {

    private val launch = buildJsonObject { put("uri", QR_URI) }

    private fun check(root: JsonObject, property: (Config) -> Boolean): FlowModelChecker.Outcome {
        val bundle = Fixture.bundle(root)
        return FlowModelChecker(bundle, Results.byService, DEPTH).check(FlowModelChecker.starts(bundle, launch), property)
    }

    private val l58: (Config) -> Boolean = { cfg ->
        cfg.state != "auth" || FlowContext.get(cfg.context, "/request/selected_count").int() != 0
    }

    @Test
    fun theExportedFixHoldsTheL58Property() {
        val outcome = check(Fixture.root, l58)
        println("D5-engine fixed: explored ${outcome.explored} nodes, counterexample ${outcome.counterexample}")
        assertNull(outcome.counterexample, "L58 on the fixed bundle: ${outcome.counterexample?.trace}")
    }

    @Test
    fun thePreFixVariantFailsTheL58PropertyWithAPath() {
        val outcome = check(preFix(), l58)
        val cex = assertNotNull(outcome.counterexample, "the pre-fix bundle must fail L58")
        println("D5-engine pre-fix: explored ${outcome.explored} nodes, counterexample ${cex.trace}")
        assertEquals("auth", cex.state)
        assertEquals(0, FlowContext.get(cex.context, "/request/selected_count").int())
        assertTrue(cex.trace.any { it.startsWith("TOGGLE_ITEM") } && cex.trace.last().startsWith("CONTINUE"), "${cex.trace}")
        assertTrue(cex.trace.size - 1 <= DEPTH)
    }

    /** The check has reach: `auth` is reachable within the bound, with a selection. */
    @Test
    fun theBoundReachesAuth() {
        val outcome = check(Fixture.root) { it.state != "auth" }
        assertNotNull(outcome.counterexample)
    }

    /**
     * FLW-pres-cross-device.yaml before the fix (L58-63): ticking is "local screen state", so `request-optional-selected`
     * keeps the user's unticking without a transition, and its `CONTINUE -> auth` is unguarded; `request-optional-only`
     * moves on any tick, and `request-no-selection` has no `CONTINUE`. Built here from the fixed fixture.
     */
    private fun preFix(): JsonObject {
        val toggle = """[{"op":"toggle_item","list":"/request/attributes","key":"id","key_from":{"event":"item_id"},"field":"selected"}]"""
        val zero = """{"path":"/request/selected_count","op":"eq","value":0}"""
        return Fixture.root
            .with("/states/request-optional-selected/on/TOGGLE_ITEM", json("""[{"target":null,"kind":"alt","assign":$toggle}]"""))
            .with("/states/request-optional-selected/on/CONTINUE", json("""[{"target":"auth","kind":"main"}]"""))
            .with(
                "/states/request-optional-only/on/TOGGLE_ITEM",
                json("""[{"target":"request-optional-selected","kind":"alt","assign":$toggle}]"""),
            )
            .with(
                "/states/request-optional-only/on/CONTINUE",
                json(
                    """[{"target":"request-no-selection","kind":"alt","guard":{"when":$zero},
                    "assign":[{"op":"set","path":"/request/attempted","value":true}]}]""",
                ),
            )
            .with("/states/request-no-selection/on/CONTINUE", null)
            .with("/states/request/on/TOGGLE_ITEM", null)
            .with("/states/request-untrusted/on/TOGGLE_ITEM", null)
    }

    private companion object {
        const val DEPTH = 8
    }
}
