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

// The slot a deep link waits in between the app delegate and the first screen to resume — a credential
// offer or a verifier's presentation request. Small, but its two rules both matter: reading it consumes it
// (or the link would reopen on every resume), and a URL the app has no flow for must be declined rather
// than kept for the wrong screen to find.
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
    fun a_url_the_app_has_no_flow_for_is_declined() {
        // The authorization redirect belongs to `IosAuthorizationRedirects`: a flow is already waiting
        // for it, so keeping a copy here would leave it to be reopened as a fresh link later.
        assertFalse(IosDeepLinks.deliver("eu.europa.ec.euidi://authorization?code=abc"))
        assertFalse(IosDeepLinks.deliver("https://example.test/offer"))
        assertFalse(IosDeepLinks.deliver("no-scheme-at-all"))

        // Nothing was kept, so no screen can pick up someone else's link.
        assertNull(IosDeepLinks.takePending())
    }

    @Test
    fun every_presentation_scheme_the_verifier_may_use_is_accepted() {
        // The wallet does not choose the scheme — the verifier builds the link. The EUDI dev verifier
        // emits `haip-vp://` by default and `openid4vp://` under its OpenID4VP profile, so a wallet
        // registering only one of them simply would not open for the other.
        IosDeepLinks.PRESENTATION_SCHEMES.forEach { scheme ->
            val link = "$scheme://?client_id=x509_hash%3Aabc&request_uri=https%3A%2F%2Fv.test%2Fr"
            assertTrue(IosDeepLinks.deliver(link), "declined $scheme")
            assertEquals(link, IosDeepLinks.takePending())
        }
    }

    @Test
    fun a_scheme_is_matched_case_insensitively() {
        // RFC 3986 says schemes are case-insensitive, and iOS hands the URL over as the sender wrote it.
        assertTrue(IosDeepLinks.deliver("OpenID4VP://?request_uri=https%3A%2F%2Fv.test%2Fr"))
        assertTrue(IosDeepLinks.deliver("OPENID-CREDENTIAL-OFFER://?credential_offer=%7B%7D"))
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
        // The same four Android registers, as `OPENID4VP_SCHEME` and its three siblings.
        assertEquals(
            listOf("openid4vp", "eudi-openid4vp", "mdoc-openid4vp", "haip-vp"),
            IosDeepLinks.PRESENTATION_SCHEMES,
        )
    }
}
