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

// Turning an mdoc document's data elements into the shared screen's rows: one per namespace and data
// element, as Android keeps them, so the same name in two namespaces is two rows, each with its own
// value and name.
package eu.europa.ec.shared.ui.di

import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.ClaimItemId
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.shared.wallet.multipaz.StoredMdocClaim
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class MdocClaimDomainsTest {

    private val iso = "org.iso.18013.5.1"
    private val national = "org.example.national.1"

    @Test
    fun the_same_data_element_in_two_namespaces_is_two_rows_with_their_own_values_and_names() {
        val domains = listOf(
            StoredMdocClaim(nameSpace = iso, identifier = "family_name", value = "Iso"),
            StoredMdocClaim(nameSpace = national, identifier = "family_name", value = "National"),
        ).toMdocClaimDomains(
            formatType = "org.iso.18013.5.1.mDL",
            displayNamesByPath = mapOf(
                listOf(iso, "family_name") to "Family name",
                listOf(national, "family_name") to "Surname",
            ),
            displayNames = mapOf("family_name" to "Surname"),
        ).map { assertIs<ClaimDomain.Primitive>(it) }

        assertEquals(listOf("Iso", "National"), domains.map { it.value })
        assertEquals(listOf("Family name", "Surname"), domains.map { it.displayTitle })
        assertEquals(
            listOf(iso, national),
            domains.map { (it.path.type as ClaimType.MsoMdoc).namespace },
        )
        // The screen tells its rows apart by this id, which carries the namespace.
        val ids = domains.map { ClaimItemId.Claim(docId = "doc", queryId = null, path = it.path).encode() }
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun a_name_published_without_its_namespace_is_still_used() {
        val domain = listOf(StoredMdocClaim(nameSpace = iso, identifier = "given_name", value = "A"))
            .toMdocClaimDomains(
                formatType = "org.iso.18013.5.1.mDL",
                displayNamesByPath = emptyMap(),
                displayNames = mapOf("given_name" to "Given name"),
            ).single()

        assertEquals("Given name", domain.displayTitle)
    }

    @Test
    fun a_claim_without_a_published_name_shows_its_identifier() {
        val domain = listOf(StoredMdocClaim(nameSpace = null, identifier = "given_name", value = "A"))
            .toMdocClaimDomains(formatType = "eu.europa.ec.eudi.pid.1", displayNamesByPath = emptyMap(), displayNames = emptyMap())
            .single()

        assertEquals("given_name", domain.displayTitle)
        // With no namespace stored, the document type stands in, as before.
        assertEquals("eu.europa.ec.eudi.pid.1", (domain.path.type as ClaimType.MsoMdoc).namespace)
    }
}
