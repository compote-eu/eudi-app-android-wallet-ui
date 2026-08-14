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

package eu.europa.ec.shared.wallet.multipaz

/**
 * One issuer this wallet is willing to talk OpenID4VCI to.
 *
 * The iOS counterpart of `VciConfig` in :core-logic, narrowed to what iOS actually uses: Android's
 * version also carries an `OpenId4VciManager.Config` full of wallet-core knobs (PAR usage, DPoP, reuse
 * policies) that multipaz either decides for itself or does not offer.
 *
 * @property issuerUrl the credential issuer identifier — also the base for its `.well-known` metadata.
 * @property clientId the OAuth client this wallet registers as, per issuer.
 * @property redirectUri where authorization comes back to; must match what the issuer has registered
 *   *and* what the app declares in `CFBundleURLTypes`.
 * @property order display order on the add-document screen; lower comes first, as on Android.
 */
data class IosVciIssuer(
    val issuerUrl: String,
    val clientId: String,
    val redirectUri: String,
    val order: Int,
)

/**
 * Which issuers iOS offers documents from — the piece that was missing before iOS could show an
 * add-document list at all.
 *
 * **A constant, and that is the current honest state.** Android reads this from
 * `WalletCoreConfig.issuersConfig`, which is per build flavour (`dev`, `demo`, …) and generated partly
 * from `BuildConfig`. iOS has no flavour system yet — one target, one `project.yml` — so rather than
 * invent half a configuration layer, this states the same two issuers the `dev` flavour uses, with the
 * same client id and the same redirect. When iOS grows flavours (or reads a plist), this object is the
 * single place that changes; nothing above it names an issuer.
 *
 * Kept beside the multipaz code rather than in a DI file because it *is* wallet configuration, and DI
 * files should wire things, not decide them.
 */
object IosIssuerCatalog {

    /**
     * The client id both EU dev issuers know this wallet by. Shared with Android's `dev` flavour, which
     * registers the same value — the wallet-provider attestation is issued against it.
     */
    const val CLIENT_ID: String = "eudiw-abca"

    /**
     * The registered redirect. Deliberately the same constant the app delegate filters on, so the two
     * halves of the OAuth hand-off cannot drift apart.
     */
    val REDIRECT_URI: String = IosAuthorizationRedirects.REDIRECT_PREFIX

    val issuers: List<IosVciIssuer> = listOf(
        IosVciIssuer(
            issuerUrl = "https://ec.dev.issuer.eudiw.dev",
            clientId = CLIENT_ID,
            redirectUri = REDIRECT_URI,
            order = 0,
        ),
        IosVciIssuer(
            issuerUrl = "https://dev.issuer-backend.eudiw.dev",
            clientId = CLIENT_ID,
            redirectUri = REDIRECT_URI,
            order = 1,
        ),
    )
}
