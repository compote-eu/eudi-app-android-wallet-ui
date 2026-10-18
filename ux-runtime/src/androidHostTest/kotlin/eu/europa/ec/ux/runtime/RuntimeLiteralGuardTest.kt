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

// M1b, re-run at M6a on the real export (was provisional on the boundary fixture): reads the names from the exported
// bundle.
package eu.europa.ec.ux.runtime

import eu.europa.ec.ux.runtime.engine.ServiceResult
import eu.europa.ec.ux.runtime.support.Fixture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Brief §6.2 and KC1 for the engine: no tracked :ux-runtime source names a screen, a state, an event, a service or a
 * result of the bundle. The repo-wide KC1 grep (SPIKE-PLAN §3) covers the other modules; this one also catches
 * state and event names, which that grep does not look for. Host only: it reads the source tree.
 */
class RuntimeLiteralGuardTest {

    private val main: File = listOf(File("src/commonMain/kotlin"), File("ux-runtime/src/commonMain/kotlin")).first { it.isDirectory }

    @Test
    fun mainSourcesNameNothingFromTheBundle() {
        val b = Fixture.bundle()
        // RULING-2 makes one result name an engine convention (ServiceResult.UNEXPECTED_ERROR_TYPE); it is the only exemption.
        val names = b.states.keys + b.events.keys + b.services.keys + b.screens.keys +
            (b.services.values.flatMap { it.results } - setOf(ServiceResult.UNEXPECTED_ERROR_TYPE))
        val sources = main.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size >= 5, "found ${sources.size} sources under ${main.absolutePath}")
        val hits = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val literal = names.firstOrNull { "\"$it\"" in line } ?: KC1_PATTERN.find(line)?.value
                literal?.let { "${f.name}:${i + 1}: $it" }
            }
        }
        assertEquals(emptyList(), hits)
    }

    private companion object {
        /** The KC1 grep's pattern, spelled with character classes so this file does not match the grep itself. */
        val KC1_PATTERN = Regex("S[C]R-|\"p[r]es-")
    }
}
