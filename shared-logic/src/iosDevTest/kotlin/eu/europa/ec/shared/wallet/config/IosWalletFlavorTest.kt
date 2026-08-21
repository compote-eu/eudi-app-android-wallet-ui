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

// Only on the **dev** compile path. Its twin in `src/iosDemoTest` shares this name and asserts the
// other flavour, so `assertEquals(DEV, …)` below is a real assertion: it fails if the build selected
// demo, which an unflagged build must never do.
package eu.europa.ec.shared.wallet.config

import kotlin.test.Test
import kotlin.test.assertEquals

class IosWalletFlavorTest {

    @Test
    fun an_unflagged_build_is_dev() {
        // Every documented probe run and `assembleDevDebug` assume it. A build that quietly defaulted
        // to demo would talk to the production-shaped issuers and wallet provider instead.
        assertEquals(IosAppFlavor.DEV, iosWalletConfig.appFlavor)
    }

    @Test
    fun the_dev_values_match_androids_dev_flavour_exactly() {
        // Copied from `core-logic/src/dev/.../WalletCoreConfigImpl.kt` and
        // `business-logic/src/dev/.../ConfigLogicImpl.kt`. If Android changes, this fails — the point
        // is that these are a mirror, not a choice.
        assertEquals(
            listOf("https://ec.dev.issuer.eudiw.dev", "https://dev.issuer-backend.eudiw.dev"),
            iosWalletConfig.issuerUrls,
        )
        assertEquals("https://dev.wallet-provider.eudiw.dev", iosWalletConfig.walletProviderUrl)
        assertEquals(60, iosWalletConfig.credentialBatchSize)
        assertEquals(null, iosWalletConfig.changelogUrl)
    }
}
