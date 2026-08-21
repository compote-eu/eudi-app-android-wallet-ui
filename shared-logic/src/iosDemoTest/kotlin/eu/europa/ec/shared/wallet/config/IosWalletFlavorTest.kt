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

// Only on the **demo** compile path — reached by `-PappFlavor=demo`. Its twin in `src/iosDevTest`
// shares this name and asserts the other flavour.
package eu.europa.ec.shared.wallet.config

import kotlin.test.Test
import kotlin.test.assertEquals

class IosWalletFlavorTest {

    @Test
    fun a_demo_flagged_build_is_demo() {
        assertEquals(IosAppFlavor.DEMO, iosWalletConfig.appFlavor)
    }

    @Test
    fun the_demo_values_match_androids_demo_flavour_exactly() {
        // Copied from `core-logic/src/demo/.../WalletCoreConfigImpl.kt` and
        // `business-logic/src/demo/.../ConfigLogicImpl.kt`.
        assertEquals(
            listOf("https://issuer.eudiw.dev", "https://issuer-backend.eudiw.dev"),
            iosWalletConfig.issuerUrls,
        )
        assertEquals("https://wallet-provider.eudiw.dev", iosWalletConfig.walletProviderUrl)
        assertEquals(10, iosWalletConfig.credentialBatchSize)
        assertEquals(
            "https://github.com/eu-digital-identity-wallet/eudi-app-android-wallet-ui/releases",
            iosWalletConfig.changelogUrl,
        )
    }
}
