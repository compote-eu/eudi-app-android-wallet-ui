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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.shared.wallet.multipaz.IosDeepLinks
import eu.europa.ec.uilogic.navigation.helper.DeepLinkClassifier
import eu.europa.ec.uilogic.navigation.helper.DeepLinkKind

/**
 * Classifies the links iOS can actually be opened with, by scheme.
 *
 * Only the two kinds iOS has a flow for. Android's `DeepLinkClassifierImpl` also names issuance, RQES
 * and document retrieval; those are deliberately absent rather than mapped to something plausible,
 * because returning a kind the app cannot honour would navigate to a screen that then fails. Issuance
 * redirects do reach iOS, but through [eu.europa.ec.shared.wallet.multipaz.IosAuthorizationRedirects] —
 * a flow already waiting for them, not a link to be classified and routed.
 *
 * Scheme comparison only, and case-insensitively as RFC 3986 requires. Android parses with
 * `androidx.core.net.toUri` and reads scheme *and* host, but host only distinguishes the kinds iOS does
 * not have; nothing here needs a URL parser, and hand-rolling one would be the drift the earlier stub
 * warned about.
 */
internal class IosDeepLinkClassifier : DeepLinkClassifier {

    override fun classify(link: String): DeepLinkKind? {
        val scheme = link.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme.isEmpty()) return null

        return when (scheme) {
            in IosDeepLinks.PRESENTATION_SCHEMES -> DeepLinkKind.OPENID4VP
            in IosDeepLinks.OFFER_SCHEMES -> DeepLinkKind.CREDENTIAL_OFFER
            else -> null
        }
    }
}
