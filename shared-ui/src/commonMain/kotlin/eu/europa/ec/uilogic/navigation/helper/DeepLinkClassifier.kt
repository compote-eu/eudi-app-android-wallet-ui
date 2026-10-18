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

/**
 * What an incoming deep link is *for*, as far as a view-model needs to care.
 *
 * Mirrors :ui-logic's `DeepLinkType`, which cannot be shared: it parses an `android.net.Uri` and its
 * scheme/host lists come from the per-flavour `BuildConfig`. Both of those are genuinely platform
 * configuration, so the classification stays on the platform side and only its *answer* crosses here.
 */
enum class DeepLinkKind {
    OPENID4VP,
    CREDENTIAL_OFFER,
    ISSUANCE,
    EXTERNAL,
    DYNAMIC_PRESENTATION,
    RQES,
    RQES_DOC_RETRIEVAL,
}

/**
 * Classifies a deep link for view-models in commonMain.
 *
 * The link crosses as a `String` rather than a platform URI type — the same choice
 * `NavigationType.Deeplink.link` and `SuccessViewModel.Effect.Navigation.DeepLink` already make.
 *
 * @return the link's [DeepLinkKind], or null when it is not a deep link at all (no scheme).
 */
interface DeepLinkClassifier {
    fun classify(link: String): DeepLinkKind?
}
