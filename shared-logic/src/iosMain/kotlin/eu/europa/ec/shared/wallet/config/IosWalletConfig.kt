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

/**
 * Which build this is, mirroring Android's `AppFlavor`.
 *
 * The names match Android's product flavours exactly, because the whole point of this file is that a
 * reader comparing the two platforms should find the same two words.
 */
enum class IosAppFlavor {
    /** Talks to the EU **dev** deployments. Android suffixes its application id with `.dev`. */
    DEV,

    /** Talks to the EU **production-shaped** deployments. Android's un-suffixed flavour. */
    DEMO,
}

/**
 * Everything that differs between the two builds — and nothing that does not.
 *
 * The iOS counterpart of Android's per-flavour source sets. Android varies a build by shipping two
 * files with the same fully-qualified name, one in `src/dev` and one in `src/demo`, and letting AGP
 * put exactly one on the compile path. There are no product flavours in Kotlin/Native, so
 * :shared-logic's build script adds exactly one of `src/iosDevMain` / `src/iosDemoMain` according to
 * `-PappFlavor`, and [IosWalletConfigImpl] is the file with one name and two bodies.
 *
 * **Membership is deliberate and narrow.** A value belongs here only if Android's two
 * `WalletCoreConfigImpl.kt` / `ConfigLogicImpl.kt` files disagree about it — measured by diffing
 * them, which yields exactly these five. Everything else stays where it is: the client id, the
 * redirect URI and the trust configuration are **identical** in both Android flavours, so putting
 * them here would invite a divergence the Android side does not have.
 */
interface IosWalletConfig {

    /** Which build this is. Only for display and diagnostics; nothing branches on it. */
    val appFlavor: IosAppFlavor

    /**
     * The OpenID4VCI issuers, most-preferred first, as `WalletCoreConfigImpl.issuersConfig` lists
     * them.
     *
     * URLs only. The client id and redirect URI are the same in both Android flavours, so
     * `IosIssuerCatalog` supplies them and a flavour file cannot accidentally disagree about the one
     * value the authorization server matches on.
     */
    val issuerUrls: List<String>

    /** The wallet provider that attests this instance — Android's `walletProviderUrl`. */
    val walletProviderUrl: String

    /**
     * How many credentials to ask for per document, from Android's `RotatingBatch`/`OnceOnly`
     * `numberOfCredentials`.
     *
     * ⚠️ **Every credential is a Secure Enclave key on iOS**, which Android's numbers were not chosen
     * for. Android's values are mirrored here on purpose, but this is the one field in this interface
     * whose cost is platform-specific — if issuance becomes slow, this is why.
     */
    val credentialBatchSize: Int

    /** Where "what's new" points, or null when this build publishes no changelog. */
    val changelogUrl: String?
}

/**
 * The configuration this build was compiled with.
 *
 * A property rather than a constructor parameter threaded through the app: the flavour is fixed at
 * compile time, exactly as `BuildConfig` is on Android, so passing it around would only create the
 * possibility of two answers.
 */
val iosWalletConfig: IosWalletConfig get() = IosWalletConfigImpl
