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

package eu.europa.ec.shared.wallet.config

import eu.europa.ec.businesslogic.config.AppFlavor

/**
 * The settings whose *meaning* is the same on both platforms, declared once.
 *
 * ## What this does and does not buy — read before extending it
 *
 * **It does not share values.** Each platform still supplies its own literals: Android in
 * `core-logic/src/{dev,demo}` and `business-logic/src/{dev,demo}`, iOS in `shared-logic/src/ios{Dev,Demo}Main`.
 * `:shared-logic` cannot hold the values for both, because it is a
 * `com.android.kotlin.multiplatform.library` and never gets `configureFlavors`, so its Android side has
 * no `dev`/`demo` source sets at all. **Value drift between the platforms therefore remains possible,
 * and catching it is still the configuration-parity audit's job.**
 *
 * What it does buy is narrower but real:
 *  - **one name and one type.** `walletProviderUrl` was `walletProviderHost` on Android until
 *    2026-08-27 — the same value under two names, which no audit had flagged.
 *  - **compiler-enforced presence.** A setting added here must be answered by both platforms; it cannot
 *    be added to one and forgotten on the other, which is how [issuerUrls] and [walletProviderUrl]
 *    came to be described in two places.
 *  - **one place for the documentation** of what a setting means, rather than a definition on one
 *    platform and a "mirrors Android's …" comment on the other.
 *
 * ## Why two interfaces rather than one
 *
 * Android splits these across `ConfigLogic` (`:business-logic`) and `WalletCoreConfig`
 * (`:core-logic`), and neither can answer for the other's members. So the split is mirrored here:
 * [SharedAppConfig] is what `ConfigLogic` extends, this is what `WalletCoreConfig` extends, and iOS's
 * single `IosWalletConfig` extends both. Moving a member onto a supertype is source-compatible, so no
 * consumer of either Android interface changes.
 *
 * ## What deliberately stays out
 *
 *  - `config: EudiWalletConfig` and `rqesConfig: EudiRQESUiConfig` — SDK types, Android-only forever.
 *  - `revocationInterval` — iOS cannot honour a cadence. It once could not because `BGTaskScheduler`
 *    decided when the sweep ran; since 2026-09-04 there is no background sweep at all and revocation
 *    runs once per launch, so an interval has nothing to control. A documented divergence, not an
 *    omission.
 *  - `credentialBatchSize`, `statusTrustPolicy`, `logFileName` — iOS exposes these, Android hard-codes
 *    them (`numberOfCredentials` inside the `EudiWalletConfig` DSL, the status resolver's trust policy
 *    likewise, and `LOG_FILE_NAME` as a private const in `LogControllerImpl`). Hoisting them means
 *    rewiring those three Android call sites to read config instead of literals — worth doing, but it
 *    changes Android behaviour rather than only its declarations, so it is not part of this change.
 *  - `isRegistrationCheckEnabled` — Android answers it from `WalletCoreConfig`, iOS from
 *    `SettingsPlatformBridge`. Same question, two different homes; unifying the homes comes first.
 */
interface SharedWalletConfig {

    /**
     * The Wallet Provider that attests this instance.
     *
     * A full URL including the scheme, not a bare host: Android's `WalletCoreAttestationProvider`
     * passes it straight through as `baseUrl`.
     */
    val walletProviderUrl: String

    /**
     * The credential issuers this build offers, in the order they should be shown.
     *
     * Plain URLs because that is the platform-neutral part. Android's `WalletCoreConfig` also keeps an
     * `issuersConfig: List<VciConfig>` carrying wallet-core's `OpenId4VciManager.Config` per issuer,
     * and derives this from it — so the two cannot disagree about *which* issuers exist even though
     * only Android knows how to talk to them.
     */
    val issuerUrls: List<String>
}

/**
 * The build's own identity, as the app reports it. See [SharedWalletConfig] for why these are split in
 * two and what sharing them does and does not guarantee.
 */
interface SharedAppConfig {

    /** Which product flavour this build is. */
    val appFlavor: AppFlavor

    /**
     * The URL to the changelog for this specific version of the application, where users can find the
     * changes, new features and fixes in this release.
     *
     * Only the **DEMO** flavour publishes one ([AppFlavor.DEMO]); for **DEV** ([AppFlavor.DEV]) this is
     * always `null`, as no public changelog is maintained for development builds. A platform whose
     * settings screen has no changelog row is showing exactly that.
     */
    val changelogUrl: String?

    /**
     * Whether the wallet requires PID activation before other documents may be used.
     *
     * `false` on both platforms. Three comments once described this as an iOS/Android divergence; it
     * never was, which is why it is declared once here rather than asserted twice.
     */
    val forcePidActivation: Boolean get() = false
}
