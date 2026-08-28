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

// The two translations between multipaz's request and the shared consent model.
//
// Shared between proximity and remote presentation, because the model they translate
// ([IosPresentmentRequest]) already is: multipaz reduces ISO 18013-5 and OpenID4VP to the same
// presentment data, so the consent screen's cards are built the same way from either. Kept at file
// level and out of both coordinators because neither translation touches a coordinator's state — and
// because out here they can be tested, which matters most exactly here: the round trip is where a
// silent mismatch costs the most, since a claim whose row id no longer matches its path is a claim the
// user ticks and the wallet never sends.
package eu.europa.ec.shared.ui.di

import eu.europa.ec.commonfeature.extension.toSelectiveExpandableListItems
import eu.europa.ec.commonfeature.ui.request.model.DocumentFormatDomain
import eu.europa.ec.commonfeature.ui.request.model.DocumentPayloadDomain
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.commonfeature.ui.request.model.RequestDocumentItemUi
import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.ClaimItemId
import eu.europa.ec.corelogic.model.PresentationMatchDomain
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.request_collapsed_supporting_text
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentFormat
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentRequest
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemSupportingContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi

/** What the request screen renders: one card per document, in one card set per alternative. */
internal fun IosPresentmentRequest.toCombinationsUi(strings: StringCatalog): List<RequestCombinationUi> =
    combinations
        .map { combination ->
            RequestCombinationUi(
                documents = combination.documents.map { it.toItemUi(strings) },
                matches = combination.documents.map { document ->
                    PresentationMatchDomain(
                        documentId = document.documentId,
                        credentialId = document.credentialId,
                        // DCQL only; null under ISO 18013-5, which has no query ids. It is carried
                        // rather than dropped because one DCQL query may name the same document type
                        // twice, and the id is what keeps those two cards — and their row ids — apart.
                        queryId = document.queryId,
                        requestedClaims = document.claims.map { it.claim },
                    )
                },
            )
        }
        .filter { it.documents.isNotEmpty() }

/**
 * The inverse: the cards as the user left them, back to the credentials and claims they kept.
 *
 * A document with nothing ticked is dropped rather than sent empty, which is also how the presenter
 * reads an empty answer. Ticked rows are matched to stored claims by re-encoding each claim's own path,
 * never by parsing the row id — the same rule `RequestTransformer` follows on Android, and for the same
 * reason: the id format is [ClaimItemId]'s business.
 */
internal fun RequestCombinationUi.keptDocuments(): List<KeptDocument> {
    val matchesByDocument = matches.associateBy { it.documentId }

    return documents.mapNotNull { document ->
        val match = matchesByDocument[document.domainPayload.docId] ?: return@mapNotNull null
        val claims = document.keptClaims()
        if (claims.isEmpty()) {
            null
        } else {
            KeptDocument(
                match = match,
                // Narrowed here rather than by the caller: a "kept document" carrying every claim the
                // card offered would be a trap for whoever reads it next.
                payload = document.domainPayload.copy(docClaimsDomain = claims),
            )
        }
    }
}

/** One document the user is about to share, holding only the claims they left ticked. */
internal data class KeptDocument(
    val match: PresentationMatchDomain,
    val payload: DocumentPayloadDomain,
)

private fun RequestDocumentItemUi.keptClaims(): List<ClaimDomain.Primitive> {
    val checkedIds = headerUi.nestedItems.flatMap { it.checkedItemIds() }.toSet()

    return domainPayload.docClaimsDomain.filterIsInstance<ClaimDomain.Primitive>()
        .filter { claim ->
            ClaimItemId.Claim(
                docId = domainPayload.docId,
                queryId = domainPayload.queryId,
                path = claim.path,
            ).encode() in checkedIds
        }
}

private fun ExpandableListItemUi.checkedItemIds(): List<String> = when (this) {
    is ExpandableListItemUi.SingleListItem -> {
        val checkbox = header.trailingContentData as? ListItemTrailingContentDataUi.Checkbox
        if (checkbox?.checkboxData?.isChecked == true) listOf(header.itemId) else emptyList()
    }

    is ExpandableListItemUi.NestedListItem -> nestedItems.flatMap { it.checkedItemIds() }
}

private fun IosPresentmentRequest.RequestedDocument.toItemUi(
    strings: StringCatalog,
): RequestDocumentItemUi {
    val payload = DocumentPayloadDomain(
        docName = documentName,
        docId = documentId,
        docFormatDomain = when (format) {
            IosPresentmentFormat.SdJwtVc -> DocumentFormatDomain.SdJwtVc
            IosPresentmentFormat.MsoMdoc -> DocumentFormatDomain.MsoMdoc
        },
        docClaimsDomain = claims.map { it.toClaimDomain() },
        queryId = queryId,
    )

    return RequestDocumentItemUi(
        domainPayload = payload,
        headerUi = ExpandableListItemUi.NestedListItem(
            header = ListItemDataUi(
                itemId = ClaimItemId.DocumentHeader(docId = documentId, queryId = queryId).encode(),
                mainContentData = ListItemMainContentDataUi.Text(text = documentName),
                supportingContentData = ListItemSupportingContentDataUi.Text(
                    text = strings[Res.string.request_collapsed_supporting_text],
                ),
                trailingContentData = ListItemTrailingContentDataUi.Icon(
                    iconData = AppIcons.KeyboardArrowDown,
                ),
            ),
            nestedItems = payload.toSelectiveExpandableListItems(),
            isExpanded = false,
        ),
    )
}

/**
 * One row per requested data element.
 *
 * `isRequired` is false even when the reader set `intentToRetain`: retention is what the reader intends
 * to do with the data, not permission the wallet has already given. Marking it required would disable
 * the checkbox and force-share exactly the claims a user is most likely to refuse.
 */
private fun IosPresentmentRequest.RequestedClaimInfo.toClaimDomain() = ClaimDomain.Primitive(
    key = claim.segments.lastOrNull()?.toString().orEmpty(),
    displayTitle = displayName,
    path = claim,
    value = value,
    isRequired = false,
)
