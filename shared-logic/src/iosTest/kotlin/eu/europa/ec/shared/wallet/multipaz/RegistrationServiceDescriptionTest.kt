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

package eu.europa.ec.shared.wallet.multipaz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A registration certificate's `srv_description`, in the shape ETSI TS 119 475 V1.1.1 specifies (an array of
 * arrays, one localised list per service) and in the flat one earlier certificates use. Read flat only, a
 * conformant certificate showed no description; `purpose` stays a flat list, as the spec has it.
 */
class RegistrationServiceDescriptionTest {

    private fun registration(srvDescription: String) = issuerRegistrationFrom(
        Json.parseToJsonElement("""{"sub":"rp","srv_description":$srvDescription}""").jsonObject,
    )

    @Test
    fun the_specified_array_of_arrays_is_read() {
        // ETSI TS 119 475 V1.1.1 Annex C's example shape, and a registrar-issued certificate's.
        val description = registration(
            """[[{"lang":"en-US","value":"Club membership"},{"lang":"de-DE","value":"Vereinsmitgliedschaft"}]]""",
        ).serviceDescription

        assertEquals(
            listOf(LocalizedText("en-US", "Club membership"), LocalizedText("de-DE", "Vereinsmitgliedschaft")),
            description,
        )
    }

    @Test
    fun several_services_are_flattened_in_order() {
        val description = registration(
            """[[{"lang":"en","value":"Tickets"}],[{"lang":"en","value":"Refunds"}]]""",
        ).serviceDescription

        assertEquals(listOf("Tickets", "Refunds"), description.map { it.value })
    }

    @Test
    fun a_flat_list_is_still_read() {
        val description = registration("""[{"lang":"en","value":"Club"}]""").serviceDescription

        assertEquals(listOf(LocalizedText("en", "Club")), description)
    }

    @Test
    fun an_entry_that_is_not_a_localised_text_is_skipped_without_losing_the_rest() {
        val description = registration("""[[{"lang":"en","value":"Club"}, "stray", {"lang":"sk"}]]""").serviceDescription

        assertEquals(listOf(LocalizedText("en", "Club")), description)
    }
}
