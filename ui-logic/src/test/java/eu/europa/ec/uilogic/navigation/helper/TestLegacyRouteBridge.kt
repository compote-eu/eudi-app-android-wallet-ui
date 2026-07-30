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

package eu.europa.ec.uilogic.navigation.helper

import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.commonfeature.model.PinFlow
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentDetailsRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.ProximityLoadingRoute
import eu.europa.ec.shared.navigation.QuickPinRoute
import eu.europa.ec.shared.navigation.SplashRoute
import eu.europa.ec.shared.navigation.SuccessRoute
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.navigation.CommonScreens
import eu.europa.ec.uilogic.navigation.DashboardScreens
import eu.europa.ec.uilogic.navigation.IssuanceScreens
import eu.europa.ec.uilogic.navigation.StartupScreens
import eu.europa.ec.uilogic.serializer.UiSerializer
import eu.europa.ec.uilogic.serializer.UiSerializerImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the Nav3 Stage-2 bridge that keeps the app running on navigation-compose while
 * view-models, configs and effects go typed.
 *
 * Robolectric is required: the bridge Base64-encodes config payloads through `UiSerializerImpl`,
 * which uses `android.util.Base64`. On a plain JVM that throws, `UiSerializerImpl` swallows it and
 * returns null, and a config-carrying route degrades to an empty `?key=` argument — which is exactly
 * why the interactor tests that build routes on a bare JVM assert an empty payload.
 */
@RunWith(RobolectricTestRunner::class)
class TestLegacyRouteBridge {

    private val serializer: UiSerializer = UiSerializerImpl()

    @Test
    fun `argument-free routes map to their legacy screen route`() {
        assertEquals(StartupScreens.Splash.screenRoute, SplashRoute.toLegacyRoute())
        assertEquals(DashboardScreens.Dashboard.screenRoute, DashboardRoute.toLegacyRoute())
    }

    @Test
    fun `primitive arguments are appended as query parameters`() {
        assertEquals(
            "${DashboardScreens.DocumentDetails.screenName}?documentId=doc-1",
            DocumentDetailsRoute(documentId = "doc-1").toLegacyRoute()
        )
        assertEquals(
            "${CommonScreens.QuickPin.screenName}?pinFlow=UPDATE",
            QuickPinRoute(pinFlow = PinFlow.UPDATE).toLegacyRoute()
        )
        assertEquals(
            "${eu.europa.ec.uilogic.navigation.ProximityScreens.Loading.screenName}?scopeId=s-1",
            ProximityLoadingRoute(scopeId = "s-1").toLegacyRoute()
        )
    }

    /**
     * The load-bearing case: a config-carrying route must produce a real Base64 payload under the
     * config's own `serializedKeyName`, and that payload must decode back to an equal config — this
     * is what the `*Screen.kt` graph entries do on the receiving side.
     */
    @Test
    fun `a config-carrying route round-trips its config through the legacy argument`() {
        val config = IssuanceUiConfig(flowType = IssuanceFlowType.ExtraDocument(formatType = "mso"))

        val route = AddDocumentRoute(config).toLegacyRoute()

        val prefix = "${IssuanceScreens.AddDocument.screenName}?issuanceConfig="
        assertTrue("unexpected route: $route", route.startsWith(prefix))
        val payload = route.removePrefix(prefix)
        assertTrue("payload should not be empty", payload.isNotEmpty())
        assertEquals(
            config,
            serializer.fromBase64(payload, IssuanceUiConfig::class.java, IssuanceUiConfig.Parser)
        )
    }

    /** A config that embeds a typed next destination must survive the round trip intact. */
    @Test
    fun `a nested typed ConfigNavigation survives the legacy round trip`() {
        val config = OfferUiConfig(
            offerUri = "openid-credential-offer://issuer",
            onSuccessNavigation = ConfigNavigation(
                NavigationType.PushRoute(
                    route = DashboardRoute,
                    popUpTo = AddDocumentRoute(IssuanceUiConfig(IssuanceFlowType.NoDocument)),
                )
            ),
            onCancelNavigation = ConfigNavigation(NavigationType.Pop),
        )

        val payload = DocumentOfferRoute(config)
            .toLegacyRoute()
            .removePrefix("${IssuanceScreens.DocumentOffer.screenName}?offerConfig=")

        assertEquals(
            config,
            serializer.fromBase64(payload, OfferUiConfig::class.java, OfferUiConfig.Parser)
        )
    }

    /** `PresentationMode.initiatorRoute` is a typed `AppRoute` now — it must survive too. */
    @Test
    fun `a typed initiator route survives the legacy round trip`() {
        val config = RequestUriConfig(
            PresentationMode.OpenId4Vp(uri = "vp://request", initiatorRoute = DashboardRoute)
        )

        val payload = PresentationRequestRoute(config)
            .toLegacyRoute()
            .removePrefix(
                "${eu.europa.ec.uilogic.navigation.PresentationScreens.PresentationRequest.screenName}?requestUriConfig="
            )

        val decoded =
            serializer.fromBase64(payload, RequestUriConfig::class.java, RequestUriConfig.Parser)
        assertEquals(config, decoded)
        assertEquals(DashboardRoute, decoded?.mode?.initiatorRoute)
    }

    /**
     * `toLegacyScreen()` is what pop targets use, and it deliberately discards the config so a
     * rebuilt config still matches the back-stack entry's route *pattern* on Nav2.
     */
    @Test
    fun `toLegacyScreen yields the route pattern and ignores the config`() {
        val a = AddDocumentRoute(IssuanceUiConfig(IssuanceFlowType.NoDocument))
        val b = AddDocumentRoute(IssuanceUiConfig(IssuanceFlowType.ExtraDocument(formatType = "x")))

        assertEquals(IssuanceScreens.AddDocument.screenRoute, a.toLegacyScreen().screenRoute)
        assertEquals(a.toLegacyScreen().screenRoute, b.toLegacyScreen().screenRoute)
    }

    /** `toLegacyArguments()` is the argument tail only — the deep-link helpers add the screen. */
    @Test
    fun `toLegacyArguments returns only the query tail`() {
        val route = DocumentDetailsRoute(documentId = "doc-2")

        assertEquals("?documentId=doc-2", route.toLegacyArguments())
        assertEquals(
            "${DashboardScreens.DocumentDetails.screenName}${route.toLegacyArguments()}",
            route.toLegacyRoute()
        )
    }

    @Test
    fun `every legacy screen route the bridge emits is non-empty`() {
        val successConfig = SuccessUIConfig(
            textElementsConfig = SuccessUIConfig.TextElementsConfig(
                text = "t",
                description = "d",
            ),
            headerConfig = ContentHeaderConfig(description = null),
            imageConfig = SuccessUIConfig.ImageConfig(),
            buttonConfig = emptyList(),
            onBackScreenToNavigate = ConfigNavigation(NavigationType.PopTo(DashboardRoute)),
        )

        val route = SuccessRoute(successConfig).toLegacyRoute()

        val prefix = "${CommonScreens.Success.screenName}?successConfig="
        assertTrue("unexpected route: $route", route.startsWith(prefix))
        assertEquals(
            successConfig,
            serializer.fromBase64(
                route.removePrefix(prefix),
                SuccessUIConfig::class.java,
                SuccessUIConfig.Parser
            )
        )
    }
}
