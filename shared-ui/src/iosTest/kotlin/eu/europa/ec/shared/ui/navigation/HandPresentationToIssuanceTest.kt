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

// A verifier's request that arrives in the middle of an issuance: the issuer is authenticating the user by
// asking for their PID. Handed to the issuance screen, the issuance survives; popped to the dashboard, it
// would end, and the authorization it waits for would arrive to nobody.
package eu.europa.ec.shared.ui.navigation

import androidx.navigation3.runtime.NavKey
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentDetailsRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.SettingsRoute
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.shared.platform.platformAction
import eu.europa.ec.shared.platform.platformStringExtra
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandPresentationToIssuanceTest {

    private val request = "haip-vp://verifier-backend.eudiw.dev?client_id=x509_hash%3Aabc&request_uri=https%3A%2F%2Fv.test%2Fr"

    private val addDocument = AddDocumentRoute(IssuanceUiConfig(flowType = IssuanceFlowType.ExtraDocument(formatType = null)))

    private fun navigatorOn(vararg stack: NavKey) = AppNavigator(stack.toMutableList())

    /** What the call took and sent, so a test can tell "handed over" from "left alone". */
    private class Bus(private val pending: String?) {
        var taken = false
        val sent = mutableListOf<PlatformIntent>()
        val take: () -> String? = { taken = true; pending }
        val send: (PlatformIntent) -> Boolean = { sent += it; true }
    }

    @Test
    fun a_request_during_an_issuance_goes_to_the_issuance_screen_instead_of_the_dashboard() {
        val navigator = navigatorOn(DashboardRoute, addDocument)
        val bus = Bus(pending = request)

        assertTrue(handPresentationToIssuance(navigator, bus.take, bus.send))

        val intent = bus.sent.single()
        assertEquals(CoreActions.VCI_DYNAMIC_PRESENTATION, intent.platformAction())
        assertEquals(request, intent.platformStringExtra("uri"))
        // Nothing popped: the issuance screen stays, and with it the issuance that is waiting.
        assertEquals(listOf(DashboardRoute, addDocument), navigator.entries)
    }

    @Test
    fun the_offer_and_details_screens_are_issuances_too() {
        val offer = DocumentOfferRoute(
            OfferUiConfig(
                offerUri = "openid-credential-offer://?credential_offer_uri=x",
                onSuccessNavigation = ConfigNavigation(NavigationType.Pop),
                onCancelNavigation = ConfigNavigation(NavigationType.Pop),
            )
        )
        for (screen in listOf(offer, DocumentDetailsRoute(documentId = "doc-1"))) {
            val bus = Bus(pending = request)

            assertTrue(handPresentationToIssuance(navigatorOn(DashboardRoute, screen), bus.take, bus.send), "$screen")
            assertEquals(1, bus.sent.size)
        }
    }

    @Test
    fun without_an_issuance_on_the_stack_the_link_is_left_for_the_dashboard() {
        val bus = Bus(pending = request)

        assertFalse(handPresentationToIssuance(navigatorOn(DashboardRoute, SettingsRoute), bus.take, bus.send))

        // Not taken, so the dashboard still reads it after the pop.
        assertFalse(bus.taken)
        assertTrue(bus.sent.isEmpty())
    }

    @Test
    fun an_issuance_before_there_is_a_dashboard_is_not_handed_anything() {
        // The first-document issuance of onboarding, which Android does not treat this way either: its gate,
        // `userIsLoggedInWithDocuments()`, is the dashboard being on the stack.
        val bus = Bus(pending = request)

        assertFalse(handPresentationToIssuance(navigatorOn(addDocument), bus.take, bus.send))

        assertFalse(bus.taken)
    }

    @Test
    fun a_link_that_is_not_a_presentation_request_is_not_handed_over() {
        // `takePendingPresentation` answers null for an offer and leaves it pending.
        val bus = Bus(pending = null)

        assertFalse(handPresentationToIssuance(navigatorOn(DashboardRoute, addDocument), bus.take, bus.send))

        assertTrue(bus.sent.isEmpty())
    }
}
