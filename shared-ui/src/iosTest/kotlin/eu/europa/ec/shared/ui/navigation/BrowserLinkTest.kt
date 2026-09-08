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

// Which links iOS may hand to a browser. This is the gate on following the verifier's
// post-presentation `redirect_uri` — the step that lets the browser session which asked for the
// presentation learn that it succeeded, and without which wallet-centric signing cannot finish.
package eu.europa.ec.shared.ui.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserLinkTest {

    @Test
    fun the_verifiers_redirect_is_a_browser_link() {
        // 🪤 This gate used to ask `IosDeepLinkClassifier`, which answers **null** for an `https` URL
        // by design — `IosDeepLinkClassifierTest` pins that. So the first version of the redirect fix
        // matched nothing at all. Anything reintroducing a classifier-based gate here should fail this.
        assertTrue(
            "https://dev.verifier-backend.eudiw.dev/wallet/redirect/abc".isBrowserLink(),
            "the verifier's redirect must be followed",
        )
        assertTrue("http://example.test/x".isBrowserLink())
        assertTrue("HTTPS://EXAMPLE.TEST/x".isBrowserLink(), "RFC 3986 makes the scheme case-insensitive")
    }

    @Test
    fun the_wallets_own_schemes_are_never_handed_to_a_browser() {
        // With no associated domains registered, sending one of these out would bounce it straight back
        // into the wallet — see the KDoc on `isBrowserLink` for why the classifier's `else -> EXTERNAL`
        // fallback was not adopted.
        assertFalse("rqes://oauth/callback?code=abc".isBrowserLink())
        assertFalse("haip-vp://?request_uri=x".isBrowserLink())
        assertFalse("openid4vp://?request_uri=x".isBrowserLink())
        assertFalse("openid-credential-offer://?credential_offer=%7B%7D".isBrowserLink())
        assertFalse("eu.europa.ec.euidi://authorization?code=abc".isBrowserLink())
    }

    @Test
    fun a_link_with_no_scheme_is_not_one() {
        assertFalse("no-scheme-at-all".isBrowserLink())
        assertFalse("".isBrowserLink())
    }
}
