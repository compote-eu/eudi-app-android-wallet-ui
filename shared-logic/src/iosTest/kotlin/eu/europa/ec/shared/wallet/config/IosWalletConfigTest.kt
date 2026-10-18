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

// What holds of the build flavour *whichever* one is compiled in. The per-flavour values live in
// `src/iosDevTest` / `src/iosDemoTest`, one file per flavour with the same name — deliberately, because
// a test that read `appFlavor` and then branched on it would accept either answer and so could not
// catch the build selecting the wrong flavour. The verify set therefore runs the suite **twice**:
//
//   ./gradlew :shared-logic:iosSimulatorArm64Test
//   ./gradlew :shared-logic:iosSimulatorArm64Test -PappFlavor=demo
//
// That is the only way to cover both bodies of `IosWalletConfigImpl`: exactly one is ever on the
// compile path, which is the same reason Android's `src/dev` and `src/demo` need `testDevDebug` and
// `testDemoDebug`.
package eu.europa.ec.shared.wallet.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosWalletConfigTest {

    @Test
    fun whichever_flavour_is_active_is_internally_coherent() {
        // Holds for both, so it is the part that cannot rot when a flavour is added.
        assertEquals(2, iosWalletConfig.issuerUrls.size)
        assertTrue(iosWalletConfig.issuerUrls.all { it.startsWith("https://") })
        assertTrue(iosWalletConfig.walletProviderUrl.startsWith("https://"))
        assertTrue(iosWalletConfig.credentialBatchSize > 0)
        assertEquals(iosWalletConfig.issuerUrls.distinct(), iosWalletConfig.issuerUrls)
        assertTrue(iosWalletConfig.changelogUrl?.startsWith("https://") ?: true)
    }
}
