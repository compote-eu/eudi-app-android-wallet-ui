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

import androidx.navigation3.runtime.NavKey
import eu.europa.ec.commonfeature.config.BiometricUiConfig
import eu.europa.ec.commonfeature.config.IssuanceSuccessUiConfig
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OfferCodeUiConfig
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.config.QrScanUiConfig
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.commonfeature.model.PinFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The app's type-safe Navigation 3 routes — the complete set of destinations.
 *
 * Each route is a [NavKey] back-stack key *and* `@Serializable`, carrying its navigation arguments as
 * typed fields. This replaced an apparatus of `Screen` route-pattern strings, a Base64+reflection
 * `UiSerializer`, and `generateComposable*` link builders, none of which could go KMP.
 *
 * The hierarchy is sealed, so every route lives in this file — including the config-carrying ones,
 * which is why `AppRoute` and the configs it references live in this module. The base is
 * `@Serializable` so an `AppRoute`-typed *field* (see
 * [eu.europa.ec.uilogic.config.NavigationType.PushRoute]) serializes polymorphically; each variant
 * declares a short `@SerialName` to keep those nested payloads small.
 *
 * The host that consumes these is `RouterHostImpl.StartFlow` (a `NavDisplay` over
 * [AppNavigator]); each feature module contributes its destinations via a `featureXEntries`
 * `entryProvider` extension.
 */
@Serializable
sealed interface AppRoute : NavKey

// --- Startup ---
@Serializable
@SerialName("Splash")
data object SplashRoute : AppRoute

// --- Dashboard ---
@Serializable
@SerialName("Dashboard")
data object DashboardRoute : AppRoute

@Serializable
@SerialName("Settings")
data object SettingsRoute : AppRoute

@Serializable
@SerialName("DocumentSign")
data object DocumentSignRoute : AppRoute

@Serializable
@SerialName("DocumentDetails")
data class DocumentDetailsRoute(val documentId: String) : AppRoute

@Serializable
@SerialName("TransactionDetails")
data class TransactionDetailsRoute(val transactionId: String) : AppRoute

// --- Common (reusable screens) ---
@Serializable
@SerialName("Success")
data class SuccessRoute(val config: SuccessUIConfig) : AppRoute

@Serializable
@SerialName("Biometric")
data class BiometricRoute(val config: BiometricUiConfig) : AppRoute

@Serializable
@SerialName("QuickPin")
data class QuickPinRoute(val pinFlow: PinFlow) : AppRoute

@Serializable
@SerialName("QrScan")
data class QrScanRoute(val config: QrScanUiConfig) : AppRoute

// --- Presentation (online) ---
@Serializable
@SerialName("PresentationRequest")
data class PresentationRequestRoute(val config: RequestUriConfig) : AppRoute

@Serializable
@SerialName("PresentationLoading")
data class PresentationLoadingRoute(val scopeId: String) : AppRoute

@Serializable
@SerialName("PresentationSuccess")
data class PresentationSuccessRoute(val scopeId: String) : AppRoute

// --- Proximity ---
@Serializable
@SerialName("ProximityQr")
data class ProximityQrRoute(val config: RequestUriConfig) : AppRoute

@Serializable
@SerialName("ProximityRequest")
data class ProximityRequestRoute(val scopeId: String) : AppRoute

@Serializable
@SerialName("ProximityLoading")
data class ProximityLoadingRoute(val scopeId: String) : AppRoute

@Serializable
@SerialName("ProximitySuccess")
data class ProximitySuccessRoute(val scopeId: String) : AppRoute

// --- Issuance ---
@Serializable
@SerialName("AddDocument")
data class AddDocumentRoute(val config: IssuanceUiConfig) : AppRoute

@Serializable
@SerialName("DocumentOffer")
data class DocumentOfferRoute(val config: OfferUiConfig) : AppRoute

@Serializable
@SerialName("DocumentOfferCode")
data class DocumentOfferCodeRoute(val config: OfferCodeUiConfig) : AppRoute

@Serializable
@SerialName("DocumentIssuanceSuccess")
data class DocumentIssuanceSuccessRoute(val config: IssuanceSuccessUiConfig) : AppRoute
