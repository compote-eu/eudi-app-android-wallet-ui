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

package eu.europa.ec.shared.navigation

import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.model.PinFlow
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Proves the Nav3 type-safe routing model on both Android (JVM) and iOS (Kotlin/Native): routes
 * carry typed arguments and round-trip via kotlinx-serialization — no Base64, no Class<M>
 * reflection, no UiSerializer. Also shows the back stack as plain state.
 */
class AppRouteTest {

    @Test
    fun a_typed_route_round_trips_via_kotlinx_serialization() {
        val route = DocumentDetailsRoute(documentId = "doc-123")

        val json = Json.encodeToString(DocumentDetailsRoute.serializer(), route)
        val restored = Json.decodeFromString(DocumentDetailsRoute.serializer(), json)

        assertEquals(route, restored)
        assertEquals("doc-123", restored.documentId)
    }

    @Test
    fun the_back_stack_is_plain_typed_state() {
        val backStack: MutableList<AppRoute> = mutableListOf(SplashRoute)

        backStack.add(DashboardRoute)
        backStack.add(DocumentDetailsRoute(documentId = "doc-9"))

        assertEquals(3, backStack.size)
        assertEquals(DocumentDetailsRoute("doc-9"), backStack.last())
        backStack.removeAt(backStack.lastIndex) // "pop"
        assertEquals(DashboardRoute, backStack.last())
    }

    @Test
    fun scoped_routes_carry_their_scope_id() {
        val route = ProximityLoadingRoute(scopeId = "scope-42")
        val restored = Json.decodeFromString(
            ProximityLoadingRoute.serializer(),
            Json.encodeToString(ProximityLoadingRoute.serializer(), route),
        )
        assertEquals("scope-42", restored.scopeId)
    }

    /**
     * The sealed base is `@Serializable`, so an `AppRoute`-typed field serializes polymorphically —
     * this is what lets a config carry its own next destination (Stage 2).
     */
    @Test
    fun an_app_route_typed_field_round_trips_polymorphically() {
        val nav = ConfigNavigation(
            navigationType = NavigationType.PushRoute(
                route = AddDocumentRoute(IssuanceUiConfig(flowType = IssuanceFlowType.NoDocument)),
                popUpTo = DashboardRoute,
            )
        )

        val restored = Json.decodeFromString(
            ConfigNavigation.serializer(),
            Json.encodeToString(ConfigNavigation.serializer(), nav),
        )

        assertEquals(nav, restored)
        val push = assertIs<NavigationType.PushRoute>(restored.navigationType)
        assertEquals(DashboardRoute, push.popUpTo)
        assertEquals(IssuanceFlowType.NoDocument, assertIs<AddDocumentRoute>(push.route).config.flowType)
    }

    /** A config-carrying route nests a whole config — including a nested destination — as typed data. */
    @Test
    fun a_config_carrying_route_round_trips_its_nested_navigation() {
        val route = DocumentOfferRoute(
            OfferUiConfig(
                offerUri = "openid-credential-offer://issuer",
                onSuccessNavigation = ConfigNavigation(NavigationType.PopTo(DashboardRoute)),
                onCancelNavigation = ConfigNavigation(NavigationType.Pop),
            )
        )

        val restored = Json.decodeFromString(
            DocumentOfferRoute.serializer(),
            Json.encodeToString(DocumentOfferRoute.serializer(), route),
        )

        assertEquals(route, restored)
        assertEquals(
            DashboardRoute,
            assertIs<NavigationType.PopTo>(restored.config.onSuccessNavigation.navigationType).route,
        )
    }

    /** The presentation initiator is a typed route now, not a legacy route string. */
    @Test
    fun presentation_mode_carries_a_typed_initiator_route() {
        val route = PresentationRequestRoute(
            RequestUriConfig(
                PresentationMode.OpenId4Vp(uri = "vp://request", initiatorRoute = DashboardRoute)
            )
        )

        val restored = Json.decodeFromString(
            PresentationRequestRoute.serializer(),
            Json.encodeToString(PresentationRequestRoute.serializer(), route),
        )

        assertEquals(route, restored)
        assertEquals(DashboardRoute, restored.config.mode.initiatorRoute)
        assertEquals("vp_presentation_scope_id", restored.config.presentationScopeId)
    }

    @Test
    fun enum_arguments_round_trip() {
        val route = QuickPinRoute(pinFlow = PinFlow.CREATE_WITH_ACTIVATION)
        val restored = Json.decodeFromString(
            QuickPinRoute.serializer(),
            Json.encodeToString(QuickPinRoute.serializer(), route),
        )
        assertEquals(PinFlow.CREATE_WITH_ACTIVATION, restored.pinFlow)
    }
}
