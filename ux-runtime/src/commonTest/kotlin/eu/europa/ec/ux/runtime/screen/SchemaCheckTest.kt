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

// M5b, re-run at M6a on the real export (was provisional on the boundary fixture): the interpreter on the exported
// bundle and its offline-loading test copy.
package eu.europa.ec.ux.runtime.screen

import eu.europa.ec.ux.elements.registry.UxElementRegistry
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.OfflineFixture
import eu.europa.ec.ux.runtime.support.with
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M5c (OR-2 (b)): the bundle's node trees against what the registry accepts, on the exported bundle and a planted copy. */
class SchemaCheckTest {

    private fun problems(root: JsonObject): List<String> =
        ScreenCatalog(Fixture.bundle(root)).screens.values.flatMap {
            SchemaCheck.problems(it, UxElementRegistry.elementSchemas, UxElementRegistry.templateSchemas)
        }

    @Test
    fun theExportedBundleAndTheAddedStateCopyHaveNoSchemaProblems() {
        assertEquals(emptyList(), problems(Fixture.root))
        assertEquals(emptyList(), problems(OfflineFixture.root))
    }

    @Test
    fun plantedUnknownVariantPropAndElementAreReportedWithTheirNode() {
        val screen = Fixture.bundle().state("connecting").screen!!
        val root = Fixture.root
            .with("/screens/$screen/regions/hero/0/props/tone", JsonPrimitive("bogus"))
            .with("/screens/$screen/regions/actions/0/props/colour", JsonPrimitive("red"))
            .with("/screens/$screen/regions/actions/0/element", JsonPrimitive("EL-not-there"))
        val found = problems(root)
        assertTrue(found.any { it.endsWith("/hero: prop 'tone' has unknown variant 'bogus' (allowed: error, info, pending, success, warning)") }, "$found")
        assertTrue(found.any { it.endsWith("/cancel: unknown element 'EL-not-there'") }, "$found")
        assertEquals(2, found.size, "$found")
    }
}
