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

// The consent round trip on iOS: multipaz's request becomes the shared request screen's cards, and the
// cards the user leaves behind become the claims the wallet releases. Shared by proximity and remote
// presentation, so these cases cover both.
//
// Worth pinning rather than trusting to review, because the two directions agree only by construction —
// a row is matched back to its claim by *re-encoding* that claim's path into a row id. Any drift between
// them (a namespace dropped on the way out, a segment added on the way back) makes a ticked claim
// unmatchable, and the wallet would then share less than the user agreed to with nothing reporting a
// problem. The presenter's tests cover what happens after this point; these cover the seam under the
// screens.
package eu.europa.ec.shared.ui.di

import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.ClaimItemId
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentFormat
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentRequest
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.CheckboxDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PID_NAMESPACE = "eu.europa.ec.eudi.pid.1"

class IosPresentmentConsentMappingTest {

    /** Resolves to the resource's own key: these cases are about structure, not wording. */
    private val strings = object : StringCatalog {
        override fun get(resource: StringResource): String = resource.key
        override fun get(resource: StringResource, vararg args: Any): String = resource.key
        override suspend fun warm() = Unit
    }

    private fun mdocPath(identifier: String) = ClaimPathDomain(
        segments = listOf(ClaimPathSegment.Key(identifier)),
        type = ClaimType.MsoMdoc(namespace = PID_NAMESPACE),
    )

    private fun claim(
        identifier: String,
        value: String = identifier,
        intentToRetain: Boolean = false,
        path: ClaimPathDomain = mdocPath(identifier),
    ) = IosPresentmentRequest.RequestedClaimInfo(
        claim = path,
        displayName = identifier,
        value = value,
        intentToRetain = intentToRetain,
    )

    private fun pid(
        documentId: String = "doc-1",
        credentialId: String = "cred-1",
        name: String = "PID",
        queryId: String? = null,
        format: IosPresentmentFormat = IosPresentmentFormat.MsoMdoc,
        claims: List<IosPresentmentRequest.RequestedClaimInfo> = listOf(
            claim("given_name", "Tester"),
            claim("family_name", "Kotlin"),
        ),
    ) = IosPresentmentRequest.RequestedDocument(
        documentId = documentId,
        credentialId = credentialId,
        documentName = name,
        docType = PID_NAMESPACE,
        format = format,
        queryId = queryId,
        claims = claims,
    )

    /** One combination asking for all of [documents] at once. */
    private fun cardsFor(vararg documents: IosPresentmentRequest.RequestedDocument) =
        IosPresentmentRequest(
            requesterName = "Test Reader",
            requesterIsTrusted = false,
            combinations = listOf(IosPresentmentRequest.Combination(documents = documents.toList())),
        ).toCombinationsUi(strings).single()

    /**
     * What the screen does when a row is unticked: it addresses the row by the id the card carries, so
     * this finds that id the same way — through the claim's path.
     */
    private fun RequestCombinationUi.untick(identifier: String): RequestCombinationUi = copy(
        documents = documents.map { document ->
            val rowId = ClaimItemId.Claim(
                docId = document.domainPayload.docId,
                queryId = document.domainPayload.queryId,
                path = mdocPath(identifier),
            ).encode()

            document.copy(
                headerUi = document.headerUi.copy(
                    nestedItems = document.headerUi.nestedItems.map { item ->
                        val row = item as ExpandableListItemUi.SingleListItem
                        if (row.header.itemId == rowId) row.checked(false) else row
                    }
                )
            )
        }
    )

    private fun ExpandableListItemUi.SingleListItem.checked(isChecked: Boolean) = copy(
        header = header.copy(
            trailingContentData = ListItemTrailingContentDataUi.Checkbox(
                checkboxData = CheckboxDataUi(isChecked = isChecked, enabled = true),
            )
        )
    )

    private fun RequestCombinationUi.keptPaths() =
        keptDocuments().flatMap { it.payload.docClaimsDomain.map { claim -> claim.path } }.toSet()

    @Test
    fun every_requested_claim_becomes_a_row_the_user_can_untick() {
        val combination = cardsFor(pid())

        val document = combination.documents.single()
        assertEquals("PID", document.domainPayload.docName)
        assertEquals(
            listOf("given_name", "family_name"),
            document.domainPayload.docClaimsDomain.map { it.key },
        )
        // Values come through, because consent has to show what is about to leave the wallet.
        assertEquals(
            listOf("Tester", "Kotlin"),
            document.domainPayload.docClaimsDomain.filterIsInstance<ClaimDomain.Primitive>()
                .map { it.value },
        )
        // Every leaf is a checkbox and none is forced on.
        assertTrue(
            document.headerUi.nestedItems.all { item ->
                val checkbox = (item as ExpandableListItemUi.SingleListItem)
                    .header.trailingContentData as? ListItemTrailingContentDataUi.Checkbox
                checkbox?.checkboxData?.enabled == true
            }
        )
        // The credential is named on the match, which is what the answer is keyed by.
        assertEquals("cred-1", combination.matches.single().credentialId)
    }

    @Test
    fun a_row_left_ticked_comes_back_as_the_claim_it_was_built_from() {
        val combination = cardsFor(pid())

        val kept = combination.keptDocuments().single()

        assertEquals("doc-1", kept.match.documentId)
        assertEquals("cred-1", kept.match.credentialId)
        assertEquals(
            setOf(mdocPath("given_name"), mdocPath("family_name")),
            combination.keptPaths(),
        )
    }

    @Test
    fun unticking_a_row_drops_exactly_that_claim() {
        val combination = cardsFor(pid()).untick("family_name")

        assertEquals(setOf(mdocPath("given_name")), combination.keptPaths())
        // The success screen lists what went out, so its copy is narrowed too — not the whole card.
        assertEquals(
            listOf("given_name"),
            combination.keptDocuments().single().payload.docClaimsDomain.map { it.key },
        )
    }

    @Test
    fun a_document_with_every_row_unticked_is_not_shared_at_all() {
        val combination = cardsFor(pid()).untick("given_name").untick("family_name")

        // Not an empty document: the presenter is told nothing about this credential, and with no
        // documents left that is a refusal rather than an empty response.
        assertTrue(combination.keptDocuments().isEmpty())
    }

    @Test
    fun two_documents_asking_for_the_same_claims_stay_apart() {
        // The failure this guards against is a row id built from the claim alone: both documents offer
        // `given_name`, so unticking one would untick the other.
        val combination = cardsFor(
            pid(documentId = "doc-1", credentialId = "cred-1"),
            pid(documentId = "doc-2", credentialId = "cred-2", name = "Other PID"),
        )

        val rowIds = combination.documents
            .flatMap { it.headerUi.nestedItems }
            .map { (it as ExpandableListItemUi.SingleListItem).header.itemId }
        assertEquals(rowIds.size, rowIds.toSet().size, "row ids collided: $rowIds")

        val kept = combination.keptDocuments()
        assertEquals(listOf("cred-1", "cred-2"), kept.map { it.match.credentialId })
        assertEquals(listOf(2, 2), kept.map { it.payload.docClaimsDomain.size })
    }

    @Test
    fun the_same_document_under_two_dcql_queries_stays_apart() {
        // OpenID4VP only: one DCQL query may ask for the same document type twice — "your PID's name"
        // and "your PID's date of birth" as separate credential queries. The two cards are then built
        // from the *same* document id, so the query id is the only thing keeping their rows apart, and
        // without it unticking a row on one card would untick its twin on the other.
        val combination = cardsFor(
            pid(queryId = "pid_names", claims = listOf(claim("given_name", "Tester"))),
            pid(queryId = "pid_birth", claims = listOf(claim("given_name", "Tester"))),
        )

        val rowIds = combination.documents
            .flatMap { it.headerUi.nestedItems }
            .map { (it as ExpandableListItemUi.SingleListItem).header.itemId }
        assertEquals(rowIds.size, rowIds.toSet().size, "row ids collided: $rowIds")
        assertEquals(listOf("pid_names", "pid_birth"), combination.matches.map { it.queryId })
    }

    @Test
    fun a_nested_sd_jwt_claim_keeps_its_whole_path() {
        // `address.locality` and `address.country` share their first segment, so a mapping that kept
        // only one segment — as the mdoc case needs — would make the two rows indistinguishable and
        // the wallet would release the wrong one.
        val locality = ClaimPathDomain(
            segments = listOf(ClaimPathSegment.Key("address"), ClaimPathSegment.Key("locality")),
            type = ClaimType.SdJwtVc,
        )
        val country = ClaimPathDomain(
            segments = listOf(ClaimPathSegment.Key("address"), ClaimPathSegment.Key("country")),
            type = ClaimType.SdJwtVc,
        )

        val combination = cardsFor(
            pid(
                format = IosPresentmentFormat.SdJwtVc,
                claims = listOf(
                    claim("locality", "Brussels", path = locality),
                    claim("country", "BE", path = country),
                ),
            )
        )

        assertEquals(setOf(locality, country), combination.keptPaths())
        val rowIds = combination.documents.single().headerUi.nestedItems
            .map { (it as ExpandableListItemUi.SingleListItem).header.itemId }
        assertEquals(rowIds.size, rowIds.toSet().size, "row ids collided: $rowIds")
    }

    @Test
    fun a_claim_the_reader_will_retain_is_still_the_users_to_refuse() {
        val combination = cardsFor(pid(claims = listOf(claim("portrait", intentToRetain = true))))

        val row = combination.documents.single().headerUi.nestedItems
            .single() as ExpandableListItemUi.SingleListItem
        val checkbox = row.header.trailingContentData as ListItemTrailingContentDataUi.Checkbox

        // Enabled, i.e. untickable: retention is what the reader intends to do with the data, not
        // consent the wallet has already given on the user's behalf.
        assertTrue(checkbox.checkboxData.enabled)
        assertTrue(combination.untick("portrait").keptDocuments().isEmpty())
    }
}
