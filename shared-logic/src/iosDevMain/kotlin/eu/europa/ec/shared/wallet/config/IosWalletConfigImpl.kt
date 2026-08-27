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

// The **dev** flavour. Mirrors `core-logic/src/dev/.../WalletCoreConfigImpl.kt` and
// `business-logic/src/dev/.../ConfigLogicImpl.kt`. Its twin in `src/iosDemoMain` has this same
// fully-qualified name; the build script puts exactly one of them on the compile path.
//
// Every value below is copied from the Android dev flavour rather than chosen. If the two ever
// disagree, the Android file is right and this one is a bug.
package eu.europa.ec.shared.wallet.config

import eu.europa.ec.shared.wallet.revocation.StatusTrustPolicyDomain

internal object IosWalletConfigImpl : IosWalletConfig {

    override val appFlavor = IosAppFlavor.DEV

    override val issuerUrls = listOf(
        "https://ec.dev.issuer.eudiw.dev",
        "https://dev.issuer-backend.eudiw.dev",
    )

    override val walletProviderUrl = "https://dev.wallet-provider.eudiw.dev"

    // Android's dev flavour asks for 60. See [IosWalletConfig.credentialBatchSize] for why this number
    // costs more on iOS than it does there.
    override val credentialBatchSize = 60

    // Android names its rotating set `eudi-android-wallet-logs%g.txt`; multipaz writes one file, so
    // there is no `%g` counter to mirror.
    override val logFileName = "eudi-ios-wallet-logs.txt"

    // Matches Android's dev flavour, which sets INFORM for the document status resolver.
    // See [IosWalletConfig.statusTrustPolicy] for what that does and does not buy.
    override val statusTrustPolicy = StatusTrustPolicyDomain.Inform

    // Android's dev flavour publishes no changelog, so the settings screen has one row fewer.
    override val changelogUrl: String? = null
}
