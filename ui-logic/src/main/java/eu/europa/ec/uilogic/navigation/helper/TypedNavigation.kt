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

// The shared translator seam: view-models emit typed [AppRoute] navigation effects, and every
// `*Screen.kt` translator turns those into back-stack operations here.
//
// Stage 4 introduced these three functions over `NavController` so that Stage 5 would only have to
// change the receiver. That is what happened: they are now extensions on [AppNavigator] with the
// same names, parameters and defaults, so no call site moved. The Base64/route-pattern asymmetry
// they used to hide is simply gone — a destination is a value now.
package eu.europa.ec.uilogic.navigation.helper

import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute

/**
 * Push [route], optionally clearing the back stack up to [popUpTo] first.
 *
 * [popUpToInclusive] defaults to `true` because that is what every existing call site did — the
 * screen being left behind is normally consumed by the navigation.
 */
fun AppNavigator.navigateToRoute(
    route: AppRoute,
    popUpTo: AppRoute? = null,
    popUpToInclusive: Boolean = true,
) = navigate(route = route, popUpTo = popUpTo, popUpToInclusive = popUpToInclusive)

/**
 * Push [route] and drop the screen we are leaving — the "this screen is consumed by the navigation"
 * pattern (QrScan, QuickPin, Biometric, AddDocument, DocumentOffer, DocumentOfferCode, ProximityQR).
 *
 * Set [popUpToCurrent] to false to keep the current screen on the back stack.
 */
fun AppNavigator.navigateReplacingCurrent(route: AppRoute, popUpToCurrent: Boolean = true) {
    if (popUpToCurrent) replaceCurrent(route) else navigate(route)
}

/** Pop entries down to [route], also removing it when [inclusive]. */
fun AppNavigator.popBackStackTo(route: AppRoute, inclusive: Boolean = false): Boolean =
    popUpTo(target = route, inclusive = inclusive)
