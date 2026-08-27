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

import eu.europa.ec.shared.wallet.log.IosLogFile
import eu.europa.ec.shared.wallet.revocation.StatusSignerTrustDomain
import eu.europa.ec.shared.wallet.revocation.StatusTrustPolicyDomain

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

    /**
     * What a status list whose signer cannot be trusted costs, mirroring the
     * `configureDocumentStatusResolver { configureTrust { policy { … } } }` knob Android sets in
     * `WalletCoreConfigImpl`.
     *
     * **[StatusTrustPolicyDomain.Inform], matching Android and the official iOS wallet.** The
     * tempting alternative is `Enforce`, on the grounds that iOS has no trust anchors and so cannot
     * check a signer at all. That argument does not survive contact with what `INFORM` actually does
     * upstream: wallet-core evaluates the chain and then **discards the result**, acting on the
     * status either way. So its evaluation buys no protection under `INFORM`, and `Enforce` here
     * would not be matching Android's guarantee — it would be inventing a stricter posture than
     * either reference wallet has.
     *
     * The cost of that stricter posture is the deciding argument against it: on iOS every reading is
     * [StatusSignerTrustDomain.NoAnchorsAvailable], so `Enforce` makes revocation **permanently
     * inert** — a credential its issuer revoked would keep displaying as valid, which is the exact
     * harm the whole check exists to prevent. The un-revoke risk it would trade that for requires
     * control of the status list's HTTPS endpoint, and an attacker with that can equally serve
     * Android a list saying "valid".
     *
     * Set this to [StatusTrustPolicyDomain.Enforce] for a deployment that ships its own anchors, or
     * once eudi-lib-kmp-etsi-1196x2#130 makes the ETSI lists usable on iOS. It is one line, on
     * purpose.
     */
    val statusTrustPolicy: StatusTrustPolicyDomain

    /**
     * The file multipaz's logger writes to, inside the app's own container.
     *
     * Mirrors `walletKitConfig.logFileName` in the official iOS wallet, which hands the same kind of
     * name to `EudiWallet` and later asks it for the URL. The engine owns the file on both sides —
     * ours is `org.multipaz.util.Logger`, theirs is Wallet Kit — so this is a name, not a path: where
     * it lands is [IosLogFile]'s business.
     *
     * Android's equivalent rotates (`eudi-android-wallet-logs%g.txt`, 10 files of 5 MB, via
     * Treessence). multipaz's file logger has **no rotation and no size cap**, and truncates on every
     * `startLoggingToFile`, so this is one session's log — see [IosLogFile].
     */
    val logFileName: String
}

/**
 * The configuration this build was compiled with.
 *
 * A property rather than a constructor parameter threaded through the app: the flavour is fixed at
 * compile time, exactly as `BuildConfig` is on Android, so passing it around would only create the
 * possibility of two answers.
 */
val iosWalletConfig: IosWalletConfig get() = IosWalletConfigImpl
