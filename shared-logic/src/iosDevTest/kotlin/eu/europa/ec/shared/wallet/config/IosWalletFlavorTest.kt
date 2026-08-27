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

import eu.europa.ec.businesslogic.config.AppFlavor
import kotlin.test.Test
import kotlin.test.assertEquals

class IosWalletFlavorTest {

    @Test
    fun an_unflagged_build_is_dev() {
        // Every documented probe run and `assembleDevDebug` assume it. A build that quietly defaulted
        // to demo would talk to the production-shaped issuers and wallet provider instead.
        assertEquals(AppFlavor.DEV, iosWalletConfig.appFlavor)
    }

    @Test
    fun the_dev_values_match_androids_dev_flavour_exactly() {
        // Copied from `core-logic/src/dev/.../WalletCoreConfigImpl.kt` and
        // `business-logic/src/dev/.../ConfigLogicImpl.kt`.
        //
        // ⚠️ Read what this can and cannot catch. It said "if Android changes, this fails", and that
        // was never true: both sides of every assertion below are literals on the *iOS* compile path,
        // and Android's config lives in Android source sets of other modules that this test cannot
        // reference. So it pins iOS against a copy taken by hand, and is blind to Android moving.
        //
        // What it does catch: an accidental edit to `IosWalletConfigImpl`, and the wrong flavour being
        // selected. What catches Android drift is the configuration-parity audit, by hand. The
        // structural fix is to stop copying — `appFlavor` and `walletProviderUrl` are shared or
        // identically named as of 2026-08-27; the rest still are not.
        assertEquals(
            listOf("https://ec.dev.issuer.eudiw.dev", "https://dev.issuer-backend.eudiw.dev"),
            iosWalletConfig.issuerUrls,
        )
        assertEquals("https://dev.wallet-provider.eudiw.dev", iosWalletConfig.walletProviderUrl)
        assertEquals(60, iosWalletConfig.credentialBatchSize)
        assertEquals(null, iosWalletConfig.changelogUrl)
    }
}
