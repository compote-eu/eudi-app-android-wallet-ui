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

// The domain -> UI half of DocumentDetailsTransformer, which is pure: it only expands an already-built
// claim tree into list rows. The other half needs an `IssuedDocument` and a `ResourceProvider` and
// stays in :dashboard-feature. Split rather than moved because `DocumentDetailsViewModel` calls only
// this one; package unchanged, so its import is untouched.
package eu.europa.ec.dashboardfeature.ui.documents.detail.transformer

import eu.europa.ec.commonfeature.extension.toExpandableListItems
import eu.europa.ec.dashboardfeature.ui.documents.detail.model.DocumentDetailsDomain
import eu.europa.ec.dashboardfeature.ui.documents.detail.model.DocumentDetailsUi

fun DocumentDetailsDomain.transformToDocumentDetailsUi(): DocumentDetailsUi {
    val documentClaims = this.documentClaims.map { domainClaim ->
        domainClaim.toExpandableListItems(
            docId = this.docId,
            queryId = null,
        )
    }
    return DocumentDetailsUi(
        documentId = this.docId,
        documentName = this.docName,
        issuerId = this.issuerId,
        documentConfigId = this.documentConfigId,
        documentIdentifier = this.documentIdentifier,
        documentClaims = documentClaims,
    )
}
