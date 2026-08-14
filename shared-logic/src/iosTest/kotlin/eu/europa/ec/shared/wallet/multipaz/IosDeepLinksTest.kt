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

// The slot a credential offer waits in between the app delegate and the first screen to resume. Small, but
// its two rules both matter: reading it consumes it (or the offer would reopen on every resume), and a URL
// that is not an offer must be declined rather than kept for the wrong screen to find.
package eu.europa.ec.shared.wallet.multipaz

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosDeepLinksTest {

    private val offer = "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.test%2Foffer"

    @BeforeTest
    fun setUp() = IosDeepLinks.clear()

    @AfterTest
    fun tearDown() = IosDeepLinks.clear()

    @Test
    fun an_offer_waits_until_a_screen_takes_it() {
        assertTrue(IosDeepLinks.deliver(offer))

        assertEquals(offer, IosDeepLinks.takePending())
    }

    @Test
    fun taking_it_consumes_it() {
        IosDeepLinks.deliver(offer)
        IosDeepLinks.takePending()

        // Otherwise the offer screen would reopen on every resume — the exact bug Android's one-shot
        // cached intent avoids.
        assertNull(IosDeepLinks.takePending())
    }

    @Test
    fun both_offer_schemes_are_accepted() {
        assertTrue(IosDeepLinks.deliver("openid-credential-offer://?credential_offer=%7B%7D"))
        IosDeepLinks.takePending()

        // The HAIP scheme is registered alongside it on both platforms.
        assertTrue(IosDeepLinks.deliver("haip-vci://?credential_offer=%7B%7D"))
    }

    @Test
    fun a_url_that_is_not_an_offer_is_declined() {
        assertFalse(IosDeepLinks.deliver("eu.europa.ec.euidi://authorization?code=abc"))
        assertFalse(IosDeepLinks.deliver("openid4vp://request?x=1"))
        assertFalse(IosDeepLinks.deliver("https://example.test/offer"))

        // Nothing was kept, so no screen can pick up someone else's link.
        assertNull(IosDeepLinks.takePending())
    }

    @Test
    fun the_newest_offer_replaces_an_unread_one() {
        IosDeepLinks.deliver("openid-credential-offer://?credential_offer=%7B%22a%22%3A1%7D")
        IosDeepLinks.deliver(offer)

        // A user who opened two offers meant the second one.
        assertEquals(offer, IosDeepLinks.takePending())
    }

    @Test
    fun the_registered_schemes_are_the_ones_the_app_declares() {
        // These must match `CFBundleURLTypes` in iosApp/project.yml, or iOS never delivers the link at all.
        assertEquals(listOf("openid-credential-offer", "haip-vci"), IosDeepLinks.OFFER_SCHEMES)
    }
}
