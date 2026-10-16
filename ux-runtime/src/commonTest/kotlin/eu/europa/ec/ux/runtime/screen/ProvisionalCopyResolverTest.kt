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

// PROVISIONAL (M5b): copy resolution over the boundary fixture's copy table; re-run on ux-spec/resources in M6.
package eu.europa.ec.ux.runtime.screen

import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.TestCopy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Boundary §1.8: brand override first, positional placeholders in `placeholders` order, a gap drawn as ⟦key⟧. */
class ProvisionalCopyResolverTest {
    private val lines = mutableListOf<String>()
    private val provider = TestCopy.provider(Fixture.root)
    private val copy = (Fixture.root["copy"] as JsonObject)

    private fun resolver(brand: String?) = CopyResolver(Fixture.bundle(), brand, provider, FlowLog { lines += it })

    @Test
    fun theBrandOverrideWinsOverTheBaseKey() {
        val (brand, overrides) = (copy["brand_overrides"] as JsonObject).entries.first()
        val key = (overrides as JsonObject).keys.first()
        val base = TestCopy.sk(Fixture.root, key)
        val branded = resolver(brand).text(key)
        assertEquals(TestCopy.table(Fixture.root).getValue("${TestCopy.table(Fixture.root).keys.first { it.endsWith("__$brand") }}"), branded)
        assertTrue(branded != base, "override differs from the base text")
        assertEquals(base, resolver("another-brand").text(key))
    }

    @Test
    fun placeholdersAreFilledByName() {
        val (key, def) = (copy["keys"] as JsonObject).entries.first { (it.value as JsonObject)["placeholders"] is JsonArray }
        val name = ((def as JsonObject)["placeholders"] as JsonArray).first().toString().trim('"')
        val text = resolver(null).text(key, mapOf(name to "XYZ"))
        assertTrue("XYZ" in text && "{" !in text && "%" !in text, text)
    }

    @Test
    fun aMissingKeyIsVisibleAndLoggedOnce() {
        val r = resolver(null)
        assertEquals("⟦CPY-not-there⟧", r.text("CPY-not-there"))
        r.text("CPY-not-there")
        assertEquals(1, lines.count { it.startsWith("copy missing: CPY-not-there") })
    }
}
