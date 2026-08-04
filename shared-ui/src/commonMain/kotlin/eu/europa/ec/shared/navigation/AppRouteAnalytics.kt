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

// Nav3 Stage 5: screen identity for analytics.
//
// The legacy host logged `destination.route.firstPart("?")` — i.e. the `Screen.screenName`
// constant — on every destination change. Those names are the identifiers already present in
// collected analytics, so they are reproduced verbatim here rather than derived from the class or
// `@SerialName`, which would silently rename every screen in the dashboards.
package eu.europa.ec.shared.navigation

/** The analytics screen name for this destination (identical to the retired `Screen.screenName`). */
val AppRoute.analyticsName: String
    get() = when (this) {
        SplashRoute -> "SPLASH"

        DashboardRoute -> "DASHBOARD"
        SettingsRoute -> "SETTINGS"
        DocumentSignRoute -> "DOCUMENT_SIGN"
        is DocumentDetailsRoute -> "DOCUMENT_DETAILS"
        is TransactionDetailsRoute -> "TRANSACTION_DETAILS"

        is SuccessRoute -> "SUCCESS"
        is BiometricRoute -> "BIOMETRIC"
        is QuickPinRoute -> "QUICK_PIN"
        is QrScanRoute -> "QR_SCAN"

        is PresentationRequestRoute -> "PRESENTATION_REQUEST"
        is PresentationLoadingRoute -> "PRESENTATION_LOADING"
        is PresentationSuccessRoute -> "PRESENTATION_SUCCESS"

        is ProximityQrRoute -> "PROXIMITY_QR"
        is ProximityRequestRoute -> "PROXIMITY_REQUEST"
        is ProximityLoadingRoute -> "PROXIMITY_LOADING"
        is ProximitySuccessRoute -> "PROXIMITY_SUCCESS"

        is AddDocumentRoute -> "ISSUANCE_ADD_DOCUMENT"
        is DocumentOfferRoute -> "ISSUANCE_DOCUMENT_OFFER"
        is DocumentOfferCodeRoute -> "ISSUANCE_DOCUMENT_OFFER_CODE"
        is DocumentIssuanceSuccessRoute -> "ISSUANCE_DOCUMENT_SUCCESS"
    }

/**
 * The scalar navigation arguments of this destination, for analytics.
 *
 * The legacy host handed the whole nav-argument bundle to `logScreen`, which for the
 * config-carrying screens meant a Base64 blob of the entire UI config. Only the identifying
 * scalars are reported now; the config payloads were never usable analytics data.
 */
val AppRoute.analyticsParams: Map<String, String>
    get() = when (this) {
        is DocumentDetailsRoute -> mapOf("documentId" to documentId)
        is TransactionDetailsRoute -> mapOf("transactionId" to transactionId)
        is QuickPinRoute -> mapOf("pinFlow" to pinFlow.name)

        is PresentationLoadingRoute -> mapOf("scopeId" to scopeId)
        is PresentationSuccessRoute -> mapOf("scopeId" to scopeId)
        is ProximityRequestRoute -> mapOf("scopeId" to scopeId)
        is ProximityLoadingRoute -> mapOf("scopeId" to scopeId)
        is ProximitySuccessRoute -> mapOf("scopeId" to scopeId)

        SplashRoute, DashboardRoute, SettingsRoute, DocumentSignRoute,
        is SuccessRoute, is BiometricRoute, is QrScanRoute,
        is PresentationRequestRoute, is ProximityQrRoute,
        is AddDocumentRoute, is DocumentOfferRoute, is DocumentOfferCodeRoute,
        is DocumentIssuanceSuccessRoute,
            -> emptyMap()
    }
