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

package eu.europa.ec.shared.resources

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the synchronous formatter behind [StringCatalog.get].
 *
 * The corpus move numbered every specifier positionally, so these cases mirror the shapes that
 * actually occur in `strings.xml`: single `%1$s`, repeated `%1$d/%2$d`, and the mixed
 * `%1$d`/`%2$s` of `quick_pin_locked_out`.
 *
 * (Resolution itself — `warm()` reading packaged resources — is not exercised here; compose-
 * resources' reader is not wired up in plain unit-test environments. See [SharedStringsTest].)
 */
class StringCatalogTest {

    @Test
    fun substitutes_a_single_positional_argument() {
        assertEquals(
            "Welcome back, Martin",
            formatPositional("Welcome back, %1\$s", arrayOf("Martin")),
        )
    }

    @Test
    fun substitutes_repeated_arguments_in_order() {
        assertEquals(
            "Instances remaining 2/5",
            formatPositional("Instances remaining %1\$d/%2\$d", arrayOf(2, 5)),
        )
    }

    @Test
    fun honours_explicit_argument_positions_regardless_of_reading_order() {
        assertEquals(
            "second first",
            formatPositional("%2\$s %1\$s", arrayOf("first", "second")),
        )
    }

    @Test
    fun mixes_string_and_integer_specifiers() {
        assertEquals(
            "You have entered the wrong PIN 3 times in a row. Please wait 05:00 before trying again.",
            formatPositional(
                "You have entered the wrong PIN %1\$d times in a row. " +
                    "Please wait %2\$s before trying again.",
                arrayOf<Any>(3, "05:00"),
            ),
        )
    }

    @Test
    fun unescapes_percent_literals() {
        assertEquals("100% complete", formatPositional("100%% complete", emptyArray()))
    }

    @Test
    fun leaves_a_specifier_verbatim_when_its_argument_is_missing() {
        // Visible defect beats a silently blank or shifted string.
        assertEquals("a %2\$s", formatPositional("%1\$s %2\$s", arrayOf("a")))
    }

    @Test
    fun leaves_text_without_specifiers_untouched() {
        assertEquals("Scan QR", formatPositional("Scan QR", arrayOf("unused")))
    }
}
