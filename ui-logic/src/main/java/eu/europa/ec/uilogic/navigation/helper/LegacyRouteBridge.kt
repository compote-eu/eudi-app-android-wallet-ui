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

// Nav3 Stage 2 — TEMPORARY BRIDGE, deleted at Stage 5/6.
//
// `NavHost` and `NavDisplay` cannot coexist, so the host swap has to be one atomic change. This
// bridge is what lets everything *upstream* of the host go typed first: view-models, configs and
// effects now speak [AppRoute], and this file maps each key back to the legacy
// `Screen.screenRoute` + Base64 argument string that navigation-compose still consumes. Once
// `NavDisplay` drives the back stack from `AppRoute` keys directly, both functions and the whole
// `UiSerializer` apparatus they depend on go away.
package eu.europa.ec.uilogic.navigation.helper

import eu.europa.ec.commonfeature.config.BiometricUiConfig
import eu.europa.ec.commonfeature.config.IssuanceSuccessUiConfig
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OfferCodeUiConfig
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.config.QrScanUiConfig
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.BiometricRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentDetailsRoute
import eu.europa.ec.shared.navigation.DocumentIssuanceSuccessRoute
import eu.europa.ec.shared.navigation.DocumentOfferCodeRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.DocumentSignRoute
import eu.europa.ec.shared.navigation.PresentationLoadingRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.PresentationSuccessRoute
import eu.europa.ec.shared.navigation.ProximityLoadingRoute
import eu.europa.ec.shared.navigation.ProximityQrRoute
import eu.europa.ec.shared.navigation.ProximityRequestRoute
import eu.europa.ec.shared.navigation.ProximitySuccessRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.shared.navigation.QuickPinRoute
import eu.europa.ec.shared.navigation.SettingsRoute
import eu.europa.ec.shared.navigation.SplashRoute
import eu.europa.ec.shared.navigation.SuccessRoute
import eu.europa.ec.shared.navigation.TransactionDetailsRoute
import eu.europa.ec.uilogic.navigation.CommonScreens
import eu.europa.ec.uilogic.navigation.DashboardScreens
import eu.europa.ec.uilogic.navigation.IssuanceScreens
import eu.europa.ec.uilogic.navigation.PresentationScreens
import eu.europa.ec.uilogic.navigation.ProximityScreens
import eu.europa.ec.uilogic.navigation.Screen
import eu.europa.ec.uilogic.navigation.StartupScreens
import eu.europa.ec.uilogic.serializer.UiSerializable
import eu.europa.ec.uilogic.serializer.UiSerializableParser
import eu.europa.ec.uilogic.serializer.UiSerializer
import eu.europa.ec.uilogic.serializer.UiSerializerImpl

/**
 * [UiSerializerImpl] is stateless, so the bridge keeps its own instance rather than threading a
 * DI-injected [UiSerializer] through ~80 call sites that are about to stop needing one.
 */
private val bridgeSerializer: UiSerializer = UiSerializerImpl()

/**
 * The legacy [Screen] this route corresponds to. Use `.screenRoute` on the result wherever
 * navigation-compose wants a route *pattern* — `popUpTo`, `popBackStack`, `getBackStackEntry`.
 */
fun AppRoute.toLegacyScreen(): Screen = when (this) {
    SplashRoute -> StartupScreens.Splash

    DashboardRoute -> DashboardScreens.Dashboard
    SettingsRoute -> DashboardScreens.Settings
    DocumentSignRoute -> DashboardScreens.DocumentSign
    is DocumentDetailsRoute -> DashboardScreens.DocumentDetails
    is TransactionDetailsRoute -> DashboardScreens.TransactionDetails

    is SuccessRoute -> CommonScreens.Success
    is BiometricRoute -> CommonScreens.Biometric
    is QuickPinRoute -> CommonScreens.QuickPin
    is QrScanRoute -> CommonScreens.QrScan

    is PresentationRequestRoute -> PresentationScreens.PresentationRequest
    is PresentationLoadingRoute -> PresentationScreens.PresentationLoading
    is PresentationSuccessRoute -> PresentationScreens.PresentationSuccess

    is ProximityQrRoute -> ProximityScreens.QR
    is ProximityRequestRoute -> ProximityScreens.Request
    is ProximityLoadingRoute -> ProximityScreens.Loading
    is ProximitySuccessRoute -> ProximityScreens.Success

    is AddDocumentRoute -> IssuanceScreens.AddDocument
    is DocumentOfferRoute -> IssuanceScreens.DocumentOffer
    is DocumentOfferCodeRoute -> IssuanceScreens.DocumentOfferCode
    is DocumentIssuanceSuccessRoute -> IssuanceScreens.DocumentIssuanceSuccess
}

/**
 * A navigable legacy route string for this key: the screen name plus its arguments, with
 * config-carrying routes Base64-encoded exactly as the hand-written call sites used to do.
 */
fun AppRoute.toLegacyRoute(): String =
    generateComposableNavigationLink(toLegacyScreen(), legacyArguments())

/**
 * Just the `?key=value` argument tail of [toLegacyRoute].
 *
 * `handleDeepLinkAction` / `handleIntentAction` take the screen and its arguments separately —
 * they resolve the screen themselves from the deep-link type — so those callers can't use
 * [toLegacyRoute]. They still get to build the payload from a typed route instead of hand-rolling
 * the `UiSerializer` call. Both helpers move onto `AppRoute` outright in Stage 5.
 */
fun AppRoute.toLegacyArguments(): String = legacyArguments()

private fun AppRoute.legacyArguments(): String = when (this) {
    is DocumentDetailsRoute -> generateComposableArguments(mapOf("documentId" to documentId))
    is TransactionDetailsRoute ->
        generateComposableArguments(mapOf("transactionId" to transactionId))

    is QuickPinRoute -> generateComposableArguments(mapOf("pinFlow" to pinFlow))

    is PresentationLoadingRoute -> generateComposableArguments(mapOf("scopeId" to scopeId))
    is PresentationSuccessRoute -> generateComposableArguments(mapOf("scopeId" to scopeId))
    is ProximityRequestRoute -> generateComposableArguments(mapOf("scopeId" to scopeId))
    is ProximityLoadingRoute -> generateComposableArguments(mapOf("scopeId" to scopeId))
    is ProximitySuccessRoute -> generateComposableArguments(mapOf("scopeId" to scopeId))

    is SuccessRoute -> config.asLegacyArgument(SuccessUIConfig.Parser)
    is BiometricRoute -> config.asLegacyArgument(BiometricUiConfig.Parser)
    is QrScanRoute -> config.asLegacyArgument(QrScanUiConfig.Parser)
    is PresentationRequestRoute -> config.asLegacyArgument(RequestUriConfig.Parser)
    is ProximityQrRoute -> config.asLegacyArgument(RequestUriConfig.Parser)
    is AddDocumentRoute -> config.asLegacyArgument(IssuanceUiConfig.Parser)
    is DocumentOfferRoute -> config.asLegacyArgument(OfferUiConfig.Parser)
    is DocumentOfferCodeRoute -> config.asLegacyArgument(OfferCodeUiConfig.Parser)
    is DocumentIssuanceSuccessRoute -> config.asLegacyArgument(IssuanceSuccessUiConfig.Parser)

    SplashRoute, DashboardRoute, SettingsRoute, DocumentSignRoute -> ""
}

private fun <M : UiSerializable> M.asLegacyArgument(parser: UiSerializableParser): String =
    generateComposableArguments(
        mapOf(parser.serializedKeyName to bridgeSerializer.toBase64(this, parser).orEmpty())
    )
