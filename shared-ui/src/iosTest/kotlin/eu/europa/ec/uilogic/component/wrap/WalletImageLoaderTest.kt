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

// Whether iOS can load a remote image at all. Issuer and relying-party logos are https URLs, and Coil
// only fetches them through a network artifact that registers itself — so a missing dependency shows
// up nowhere but as the fallback icon. This asks the loader `WrapAsyncImage` uses whether anything
// would fetch such a URL; creating a fetcher does no I/O, so the test needs no network.
package eu.europa.ec.uilogic.component.wrap

import coil3.PlatformContext
import coil3.request.Options
import kotlin.test.Test
import kotlin.test.assertNotNull

class WalletImageLoaderTest {

    @Test
    fun an_https_logo_has_a_fetcher() {
        val context = PlatformContext.INSTANCE
        val loader = walletImageLoader(context)
        val options = Options(context)
        val data = loader.components.map("https://issuer.example/logo.svg", options)

        assertNotNull(
            loader.components.newFetcher(data, options, loader),
            "no fetcher handles an https URL, so every remote logo falls back to its error icon",
        )
    }
}
