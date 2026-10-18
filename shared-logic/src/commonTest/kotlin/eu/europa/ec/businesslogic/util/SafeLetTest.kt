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

package eu.europa.ec.businesslogic.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Runs on both Android (JVM) and iOS (Kotlin/Native) from the same source. */
class SafeLetTest {

    @Test
    fun invokes_block_when_both_non_null() =
        assertEquals("ab", safeLet("a", "b") { a, b -> a + b })

    @Test
    fun returns_null_when_any_argument_is_null() =
        assertNull(safeLet<String, String, String>("a", null) { a, b -> a + b })

    @Test
    fun supports_the_five_arg_overload() =
        assertEquals(15, safeLet(1, 2, 3, 4, 5) { a, b, c, d, e -> a + b + c + d + e })
}
