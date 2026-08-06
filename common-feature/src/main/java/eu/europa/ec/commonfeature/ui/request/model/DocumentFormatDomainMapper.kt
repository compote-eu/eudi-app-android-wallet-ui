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

// The wallet-core-facing half of [DocumentFormatDomain], split out when the model moved to
// :shared-ui's commonMain. Same split as :core-logic's `PresentationMatchMapper.kt`: the model is
// plain data, only the mapping from the SDK's `DocumentFormat` needs Android-only types.
//
// An extension on the (now empty) companion, so `DocumentFormatDomain.getFormat(...)` in
// RequestTransformer reads exactly as before and only needs this file's import.
package eu.europa.ec.commonfeature.ui.request.model

import eu.europa.ec.eudi.wallet.document.format.DocumentFormat
import eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcFormat

fun DocumentFormatDomain.Companion.getFormat(format: DocumentFormat): DocumentFormatDomain =
    when (format) {
        is SdJwtVcFormat -> DocumentFormatDomain.SdJwtVc
        is MsoMdocFormat -> DocumentFormatDomain.MsoMdoc
    }
