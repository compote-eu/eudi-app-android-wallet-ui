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

// Turning an SD-JWT VC document's claims into the shared screen's claim tree.
//
// multipaz hands back only the top-level claims, with any nesting still inside each value, so the tree
// is built here — and the shape decisions (what becomes a group, what stays one line, which nodes get
// the issuer's name) are what these pin.
package eu.europa.ec.shared.ui.di

import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.shared.wallet.multipaz.StoredJsonClaim
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SdJwtClaimDomainsTest {

    private fun claim(name: String, json: String): StoredJsonClaim =
        StoredJsonClaim(name = name, value = Json.parseToJsonElement(json))

    private fun value(json: String): JsonElement = Json.parseToJsonElement(json)

    @Test
    fun a_scalar_claim_becomes_one_line_carrying_the_issuers_name() {
        val domains = listOf(claim("family_name", "\"Tester\""))
            .toSdJwtClaimDomains(mapOf("family_name" to "Family Name(s)"))

        val primitive = assertIs<ClaimDomain.Primitive>(domains.single())
        assertEquals("family_name", primitive.key)
        assertEquals("Family Name(s)", primitive.displayTitle)
        assertEquals("Tester", primitive.value)
        // SdJwtVc, not MsoMdoc — which is what makes `nameSpace` read null for this format.
        assertEquals(ClaimType.SdJwtVc, primitive.path.type)
        assertEquals(null, primitive.nameSpace)
    }

    @Test
    fun a_nested_object_becomes_a_group_whose_children_carry_the_full_path() {
        val domains = listOf(claim("place_of_birth", """{"country":"SK","locality":"Košice"}"""))
            .toSdJwtClaimDomains(emptyMap())

        val group = assertIs<ClaimDomain.Group>(domains.single())
        assertEquals("place_of_birth", group.key)
        assertEquals(2, group.items.size)
        val country = assertIs<ClaimDomain.Primitive>(group.items.first { it.key == "country" })
        assertEquals("SK", country.value)
        // The path is what the shared screen groups on, so it must carry both segments.
        assertEquals(
            listOf("place_of_birth", "country"),
            country.path.segments.map { it.toString() },
        )
    }

    @Test
    fun an_array_of_scalars_stays_one_line_rather_than_a_group_of_numbers() {
        // `nationalities: ["SK","CZ"]` reads better as a single line than as children named 0 and 1.
        val domains = listOf(claim("nationalities", """["SK","CZ"]""")).toSdJwtClaimDomains(emptyMap())

        val primitive = assertIs<ClaimDomain.Primitive>(domains.single())
        assertTrue(primitive.value.contains("SK"))
        assertTrue(primitive.value.contains("CZ"))
    }

    @Test
    fun an_array_of_objects_does_become_a_group_indexed_by_position() {
        val domains = listOf(claim("addresses", """[{"city":"Košice"},{"city":"Praha"}]"""))
            .toSdJwtClaimDomains(emptyMap())

        val group = assertIs<ClaimDomain.Group>(domains.single())
        assertEquals(2, group.items.size)
        assertEquals(
            listOf("addresses", "0", "city"),
            assertIs<ClaimDomain.Group>(group.items.first()).items.single().path.segments
                .map { it.toString() },
        )
    }

    @Test
    fun nested_claims_are_named_from_the_issuer_metadata_too() {
        // Not just top-level ones: this issuer declares `["place_of_birth","locality"]` and its
        // siblings explicitly, so a child that the issuer named must show that name. An earlier version
        // restricted the lookup to depth 1 on the wrong assumption and hid them.
        val domains = listOf(claim("place_of_birth", """{"country":"SK"}"""))
            .toSdJwtClaimDomains(mapOf("place_of_birth" to "Place of Birth", "country" to "Country"))

        val group = assertIs<ClaimDomain.Group>(domains.single())
        assertEquals("Place of Birth", group.displayTitle)
        assertEquals("Country", group.items.single().displayTitle)
    }

    @Test
    fun a_claim_with_no_issuer_name_falls_back_to_its_key() {
        val domains = listOf(claim("given_name", "\"A\"")).toSdJwtClaimDomains(emptyMap())

        assertEquals("given_name", domains.single().displayTitle)
    }

    @Test
    fun a_json_null_reads_as_empty_rather_than_the_word_null() {
        val domains = listOf(StoredJsonClaim("middle_name", value("null"))).toSdJwtClaimDomains(emptyMap())

        assertEquals("", assertIs<ClaimDomain.Primitive>(domains.single()).value)
    }
}
