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
import kotlin.test.assertNotEquals

/**
 * Guards the two properties [UiText] exists to provide, both of which a `vararg val args` would
 * have silently broken:
 *
 *  - **structural equality**, so view-model tests can assert on the resource key and arguments
 *    instead of on resolved characters;
 *  - **usable as `StateFlow` state**, which conflates by `equals` — array identity would make every
 *    formatted string compare unequal and emit spuriously on each update.
 *
 * Also pins the overload resolution between the `List` constructor and the companion's `vararg`
 * `invoke`, which is what makes the ergonomic call form safe.
 */
class UiTextTest {

    private val key = Res.string.generic_error_message
    private val other = Res.string.generic_network_error_message

    @Test
    fun vararg_and_list_forms_produce_equal_values() {
        assertEquals(
            UiText.Resource(key, listOf("Martin")),
            UiText.Resource(key, "Martin"),
        )
    }

    @Test
    fun formatted_values_compare_structurally() {
        assertEquals(
            UiText.Resource(key, "Martin"),
            UiText.Resource(key, "Martin"),
        )
        assertEquals(
            UiText.Resource(key, "Martin").hashCode(),
            UiText.Resource(key, "Martin").hashCode(),
        )
    }

    @Test
    fun differing_arguments_or_keys_are_unequal() {
        assertNotEquals(UiText.Resource(key, "Martin"), UiText.Resource(key, "Someone"))
        assertNotEquals(UiText.Resource(key, "Martin"), UiText.Resource(other, "Martin"))
        assertNotEquals<UiText>(UiText.Resource(key), UiText.Raw(key.key))
    }

    @Test
    fun multiple_arguments_keep_their_order() {
        val two = UiText.Resource(key, 3, "05:00")
        assertEquals(listOf("3", "05:00"), two.args)
    }

    @Test
    fun no_argument_form_binds_to_the_constructor_with_an_empty_list() {
        assertEquals(emptyList(), UiText.Resource(key).args)
        assertEquals(UiText.Resource(key, emptyList()), UiText.Resource(key))
    }

    @Test
    fun raw_wraps_runtime_text() {
        assertEquals(UiText.Raw("boom"), "boom".asUiText())
        assertEquals(UiText.Raw(""), UiText.Empty)
    }
}
