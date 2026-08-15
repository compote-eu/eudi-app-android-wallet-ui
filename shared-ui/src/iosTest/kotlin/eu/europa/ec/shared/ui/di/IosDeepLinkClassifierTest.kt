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

// What a link opened on the app is taken to mean. The classifier is two lines of matching, but its answer
// is what the shared `DashboardViewModel` turns into a route — so a wrong answer here does not fail, it
// navigates somewhere that then cannot work.
package eu.europa.ec.shared.ui.di

import eu.europa.ec.shared.wallet.multipaz.IosDeepLinks
import eu.europa.ec.uilogic.navigation.helper.DeepLinkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosDeepLinkClassifierTest {

    private val classifier = IosDeepLinkClassifier()

    @Test
    fun every_scheme_the_app_registers_classifies_to_the_flow_that_handles_it() {
        // Both lists are also what `CFBundleURLTypes` declares, so this doubles as the check that the
        // app does not register a scheme it would then fail to route.
        IosDeepLinks.PRESENTATION_SCHEMES.forEach { scheme ->
            assertEquals(
                DeepLinkKind.OPENID4VP,
                classifier.classify("$scheme://?request_uri=https%3A%2F%2Fv.test%2Fr"),
                "misread $scheme",
            )
        }
        IosDeepLinks.OFFER_SCHEMES.forEach { scheme ->
            assertEquals(
                DeepLinkKind.CREDENTIAL_OFFER,
                classifier.classify("$scheme://?credential_offer=%7B%7D"),
                "misread $scheme",
            )
        }
    }

    @Test
    fun a_scheme_is_matched_case_insensitively() {
        assertEquals(DeepLinkKind.OPENID4VP, classifier.classify("OpenID4VP://?request_uri=x"))
    }

    @Test
    fun a_link_iOS_has_no_flow_for_is_left_unclassified() {
        // Null rather than a nearest kind: the shared view-model treats null as "not for us" and stays
        // put, whereas any kind it recognises would push a screen. The authorization redirect is the
        // case that matters — it belongs to `IosAuthorizationRedirects`, not to routing.
        assertNull(classifier.classify("eu.europa.ec.euidi://authorization?code=abc"))
        assertNull(classifier.classify("https://example.test/anything"))
        assertNull(classifier.classify("rqes://sign"))
        assertNull(classifier.classify("no-scheme-at-all"))
        assertNull(classifier.classify(""))
    }
}
