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

// Nav3 Stage 2: the "config carries its next destination" contract, now typed and KMP-clean.
// `NavigationType` used to hold an `@Contextual Screen` plus a `Map<String, String?>` of
// pre-Base64-serialized arguments; it now holds an [AppRoute], which bundles its arguments as
// typed fields. That drops the ScreenSerializer/UiSerializer coupling and lets this file — and
// therefore every config that embeds it (Biometric/Offer/OfferCode/IssuanceSuccess/Success) —
// live in commonMain alongside [AppRoute]. Package unchanged so call sites don't churn.
package eu.europa.ec.uilogic.config

import eu.europa.ec.shared.navigation.AppRoute
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConfigNavigation(
    val navigationType: NavigationType,
    val indicateFlowCompletion: FlowCompletion = FlowCompletion.NONE
)

@Serializable
sealed interface NavigationType {
    @Serializable
    @SerialName("Pop")
    data object Pop : NavigationType

    @Serializable
    @SerialName("Finish")
    data object Finish : NavigationType

    /**
     * Push [route], optionally clearing the back stack up to [popUpTo] first.
     *
     * Replaces the former `PushScreen(screen, arguments, popUpToScreen)` *and* the string-based
     * `PushRoute(route, popUpToRoute)`: with typed routes the two are the same operation.
     */
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
     * An Android-only side effect: hand [link] to the deep-link handler. [routeToPop] stays a raw
     * route string because it round-trips through the domain layer
     * (`PresentationControllerConfig.initiatorRoute`, which core-logic cannot type against
     * [AppRoute]); it is retyped when the Nav3 host lands in Stage 5.
     */
    @Serializable
    @SerialName("Deeplink")
    data class Deeplink(val link: String, val routeToPop: String? = null) : NavigationType
}

@Serializable
enum class FlowCompletion {
    CANCEL,
    SUCCESS,
    NONE
}
