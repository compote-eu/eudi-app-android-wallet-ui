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
 * Everything that differs between the two builds — and nothing that does not.
 *
 * The iOS counterpart of Android's per-flavour source sets. Android varies a build by shipping two
 * files with the same fully-qualified name, one in `src/dev` and one in `src/demo`, and letting AGP
 * put exactly one on the compile path. There are no product flavours in Kotlin/Native, so
 * :shared-logic's build script adds exactly one of `src/iosDevMain` / `src/iosDemoMain` according to
 * `-PappFlavor`, and [IosWalletConfigImpl] is the file with one name and two bodies.
 *
 * **Membership is deliberate and narrow.** A value belongs here only if Android's two
 * `WalletCoreConfigImpl.kt` / `ConfigLogicImpl.kt` files disagree about it — measured by diffing them.
 * Everything else stays where it is: the client id, the redirect URI and the trust configuration are
 * **identical** in both Android flavours, so putting them here would invite a divergence the Android
 * side does not have.
 *
 * **What is left here is what only iOS has.** The settings both platforms answer moved to
 * [SharedAppConfig] and [SharedWalletConfig] in commonMain, which this extends — so `appFlavor`,
 * `changelogUrl`, `walletProviderUrl` and `issuerUrls` are now declared once for both platforms and
 * still supplied per flavour here. The three below remain iOS-only because Android hard-codes their
 * equivalents rather than exposing them; [SharedWalletConfig] records what hoisting them would cost.
 */
interface IosWalletConfig : SharedAppConfig, SharedWalletConfig {

    /**
     * How many credentials to ask for per document, from Android's `RotatingBatch`/`OnceOnly`
     * `numberOfCredentials`.
     *
     * ⚠️ **Every credential is a Secure Enclave key on iOS**, which Android's numbers were not chosen
     * for. Android's values are mirrored here on purpose, but this is the one field in this interface
     * whose cost is platform-specific — if issuance becomes slow, this is why.
     */
    val credentialBatchSize: Int

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
     * A pattern, not a plain filename: `%g` is the generation counter, exactly as in Android's
     * `eudi-android-wallet-logs%g.txt`. [IosLogFile] substitutes it and keeps the same 10 files of
     * 5 MB that Treessence keeps there, because multipaz's own file writer has no rotation and
     * truncates on every `startLoggingToFile`.
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
