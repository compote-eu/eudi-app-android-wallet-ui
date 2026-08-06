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

package eu.europa.ec.uilogic.navigation.helper

import androidx.core.net.toUri

/**
 * Android [DeepLinkClassifier]: parses the link with `android.net.Uri` and answers with the shared
 * [DeepLinkKind], so it delegates to the same [hasDeepLink]/[DeepLinkType] logic the Android-side
 * navigation helpers use rather than duplicating the scheme tables.
 */
class DeepLinkClassifierImpl : DeepLinkClassifier {

    override fun classify(link: String): DeepLinkKind? =
        hasDeepLink(link.toUri())?.type?.toDeepLinkKind()
}

private fun DeepLinkType.toDeepLinkKind(): DeepLinkKind = when (this) {
    DeepLinkType.OPENID4VP -> DeepLinkKind.OPENID4VP
    DeepLinkType.CREDENTIAL_OFFER -> DeepLinkKind.CREDENTIAL_OFFER
    DeepLinkType.ISSUANCE -> DeepLinkKind.ISSUANCE
    DeepLinkType.EXTERNAL -> DeepLinkKind.EXTERNAL
    DeepLinkType.DYNAMIC_PRESENTATION -> DeepLinkKind.DYNAMIC_PRESENTATION
    DeepLinkType.RQES -> DeepLinkKind.RQES
    DeepLinkType.RQES_DOC_RETRIEVAL -> DeepLinkKind.RQES_DOC_RETRIEVAL
}
