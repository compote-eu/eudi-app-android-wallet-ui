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

// The "config carries its next destination" contract: typed and KMP-clean.
//
// `NavigationType` holds an [AppRoute], which bundles its arguments as typed fields. That is what
// lets this file — and therefore every config that embeds it (Biometric/Offer/OfferCode/
// IssuanceSuccess/Success) — live in commonMain alongside [AppRoute].
package eu.europa.ec.uilogic.config

import eu.europa.ec.shared.navigation.AppRoute
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConfigNavigation(
    val navigationType: NavigationType
)

@Serializable
sealed interface NavigationType {
    @Serializable
    @SerialName("Pop")
    data object Pop : NavigationType

    @Serializable
    @SerialName("Finish")
    data object Finish : NavigationType

    /** Push [route], optionally clearing the back stack up to [popUpTo] first. */
    @Serializable
    @SerialName("PushRoute")
    data class PushRoute(
        val route: AppRoute,
        val popUpTo: AppRoute? = null
    ) : NavigationType

    @Serializable
    @SerialName("PopTo")
    data class PopTo(val route: AppRoute) : NavigationType

    /**
     * An Android-only side effect: hand [link] to the deep-link handler.
     *
     * [routeToPop] is a `String` by design: it round-trips through the domain layer as
     * `PresentationControllerConfig.initiatorRoute`, and `:core-logic` must not depend on
     * `:shared-ui` (domain -> UI). It carries an [AppRoute] encoded by
     * [eu.europa.ec.shared.navigation.AppRouteCodec], which the consuming view-model decodes.
     */
    @Serializable
    @SerialName("Deeplink")
    data class Deeplink(val link: String, val routeToPop: String? = null) : NavigationType
}
