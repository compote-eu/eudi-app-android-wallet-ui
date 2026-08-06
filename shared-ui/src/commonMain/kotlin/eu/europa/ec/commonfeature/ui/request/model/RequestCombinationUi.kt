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

package eu.europa.ec.commonfeature.ui.request.model

import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.PresentationMatchDomain
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi

/**
 * One selectable combination card on the request screen — the UI projection of a single
 * [PresentationCombinationDomain][eu.europa.ec.corelogic.model.PresentationCombinationDomain].
 */
data class RequestCombinationUi(
    val documents: List<RequestDocumentItemUi>,
    val matches: List<PresentationMatchDomain>,
)

data class RequestDocumentItemUi(
    val domainPayload: DocumentPayloadDomain,
    val headerUi: ExpandableListItemUi.NestedListItem,
)

data class DocumentPayloadDomain(
    val docName: String,
    /** Was typed `DocumentId`, which is `typealias DocumentId = String` in wallet-document-manager. */
    val docId: String,
    val docFormatDomain: DocumentFormatDomain,
    val docClaimsDomain: List<ClaimDomain>,
    /**
     * The originating DCQL query id (null for non-DCQL flows like proximity); distinguishes two
     * payloads for the same document under different queries.
     */
    val queryId: String? = null,
)

sealed class DocumentFormatDomain {
    data object SdJwtVc : DocumentFormatDomain()
    data object MsoMdoc : DocumentFormatDomain()

    /**
     * Empty, but kept so :common-feature's `DocumentFormatDomainMapper.kt` can extend it with
     * `getFormat(DocumentFormat)` — that mapper names wallet-core format types, so it stays
     * Android-side while this model lives in commonMain.
     */
    companion object
}