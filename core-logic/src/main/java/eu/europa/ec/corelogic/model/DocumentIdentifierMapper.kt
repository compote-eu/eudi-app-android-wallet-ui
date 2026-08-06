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

package eu.europa.ec.corelogic.model

import eu.europa.ec.eudi.wallet.document.Document
import eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcFormat

/**
 * The wallet-core side of [DocumentIdentifier]. The model itself is platform-neutral and lives in
 * shared-ui/commonMain; this file keeps the Android-only mapping from the SDK's `Document` behind,
 * in the *same package*, so existing `import eu.europa.ec.corelogic.model.toDocumentIdentifier`
 * call sites resolve unchanged.
 */
fun Document.toDocumentIdentifier(): DocumentIdentifier {
    val formatType = when (val f = format) {
        is MsoMdocFormat -> f.docType
        is SdJwtVcFormat -> f.vct
    }
    return formatType.toDocumentIdentifier()
}
