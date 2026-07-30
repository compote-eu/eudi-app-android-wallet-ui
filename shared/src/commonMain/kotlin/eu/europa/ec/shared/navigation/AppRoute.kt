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
import kotlinx.serialization.Serializable

/**
 * Phase-3c / N2: type-safe Navigation 3 routes.
 *
 * Each route is a [NavKey] back-stack key *and* `@Serializable`, carrying navigation arguments as
 * typed fields instead of hand-Base64-serialized config strings. This retires the legacy apparatus
 * ([eu.europa.ec.uilogic.navigation.Screen] string routes, `UiSerializer`, and the
 * `generateComposable*` builders).
 *
 * This file covers the routes whose arguments are primitives or absent (mirroring
 * [eu.europa.ec.uilogic.navigation]'s `*Screens`). Routes that currently carry rich config objects
 * (Success, Biometric, QrScan, PresentationRequest, ProximityQR, the issuance offer/success flows,
 * QuickPin) are added in a later step, once those configs are made KMP-clean and moved to
 * commonMain (they still embed Compose types / java.net.URI today).
 */
sealed interface AppRoute : NavKey

// --- Startup ---
@Serializable
data object SplashRoute : AppRoute

// --- Dashboard ---
@Serializable
data object DashboardRoute : AppRoute

@Serializable
data object SettingsRoute : AppRoute

@Serializable
data object DocumentSignRoute : AppRoute

@Serializable
data class DocumentDetailsRoute(val documentId: String) : AppRoute

@Serializable
data class TransactionDetailsRoute(val transactionId: String) : AppRoute

// --- Presentation (online) ---
@Serializable
data class PresentationLoadingRoute(val scopeId: String) : AppRoute

@Serializable
data class PresentationSuccessRoute(val scopeId: String) : AppRoute

// --- Proximity ---
@Serializable
data class ProximityRequestRoute(val scopeId: String) : AppRoute

@Serializable
data class ProximityLoadingRoute(val scopeId: String) : AppRoute

@Serializable
data class ProximitySuccessRoute(val scopeId: String) : AppRoute
