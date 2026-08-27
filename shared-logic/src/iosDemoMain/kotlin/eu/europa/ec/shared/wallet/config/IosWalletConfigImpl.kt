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

// The **demo** flavour. Mirrors `core-logic/src/demo/.../WalletCoreConfigImpl.kt` and
// `business-logic/src/demo/.../ConfigLogicImpl.kt`. Its twin in `src/iosDevMain` has this same
// fully-qualified name; the build script puts exactly one of them on the compile path.
//
// Every value below is copied from the Android demo flavour rather than chosen. If the two ever
// disagree, the Android file is right and this one is a bug.
package eu.europa.ec.shared.wallet.config

import eu.europa.ec.businesslogic.config.AppFlavor
import eu.europa.ec.shared.wallet.revocation.StatusTrustPolicyDomain

internal object IosWalletConfigImpl : IosWalletConfig {

    override val appFlavor = AppFlavor.DEMO

    override val issuerUrls = listOf(
        "https://issuer.eudiw.dev",
        "https://issuer-backend.eudiw.dev",
    )

    override val walletProviderUrl = "https://wallet-provider.eudiw.dev"

    // Android's demo flavour asks for 10.
    override val credentialBatchSize = 10

    // Mirrors Android's `eudi-android-wallet-logs%g.txt`, `%g` being the generation counter that
    // Treessence substitutes there and `IosLogFile` substitutes here.
    override val logFileName = "eudi-ios-wallet-logs%g.txt"

    // Matches Android's demo flavour, which sets INFORM for the document status resolver.
    // See [IosWalletConfig.statusTrustPolicy] for what that does and does not buy.
    override val statusTrustPolicy = StatusTrustPolicyDomain.Inform

    override val changelogUrl: String? =
        "https://github.com/eu-digital-identity-wallet/eudi-app-android-wallet-ui/releases"
}
