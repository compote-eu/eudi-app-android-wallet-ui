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

// Nav3 Stage 4: the shared translator seam.
//
// View-models now emit typed [AppRoute] navigation effects, and every `*Screen.kt` translator turns
// those into back-stack operations here instead of hand-writing `navigate(string) { popUpTo(string) }`.
// The two functions mirror [eu.europa.ec.shared.navigation.AppNavigator.navigate] / `popUpTo`
// one-for-one — same names for the parameters, same defaults — so the Stage-5 host swap is a change
// of receiver (`navController` -> `navigator`) rather than a rewrite of every call site.
//
// Note the asymmetry they encapsulate, which is exactly what the hand-written call sites kept getting
// wrong: navigating needs a *navigable* route (`toLegacyRoute()`, arguments included), while a pop
// target needs the route *pattern* (`toLegacyScreen().screenRoute`, arguments stripped).
package eu.europa.ec.uilogic.navigation.helper

import androidx.navigation.NavController
import eu.europa.ec.shared.navigation.AppRoute

/**
 * Push [route], optionally clearing the back stack up to [popUpTo] first.
 *
 * [popUpToInclusive] defaults to `true` because that is what every existing call site did — the
 * screen being left behind is normally consumed by the navigation.
 */
fun NavController.navigateToRoute(
    route: AppRoute,
    popUpTo: AppRoute? = null,
    popUpToInclusive: Boolean = true,
) {
    navigate(route.toLegacyRoute()) {
        popUpTo?.let {
            popUpTo(it.toLegacyScreen().screenRoute) { inclusive = popUpToInclusive }
        }
    }
}

/**
 * Push [route] and drop the screen we are leaving — the "this screen is consumed by the navigation"
 * pattern (QrScan, QuickPin, Biometric, AddDocument, DocumentOffer, DocumentOfferCode, ProximityQR).
 *
 * These call sites used to hardcode their own `popUpTo(CommonScreens.X.screenRoute)`. Reading the
 * target off the controller instead of naming it keeps the legacy `Screen` constants out of the
 * translators and — unlike naming a config-carrying route — needs no reconstruction of the config,
 * so there is nothing for a Stage-5 equality check to miss.
 *
 * Equivalent on the normal path: every destination is registered as `composable(route =
 * SomeScreens.X.screenRoute)`, and `NavDestination.route` returns that same pattern, so while the
 * screen is displayed `currentDestination?.route` *is* the literal those call sites hardcoded.
 *
 * One divergence, deliberately accepted: if a second navigation effect is delivered after the first
 * already navigated away, the old code's `popUpTo` named a route no longer on the stack and quietly
 * did nothing, whereas this pops whatever is now current. Both paths are the same pre-existing
 * double-navigation bug; neither is reachable from a single user action.
 *
 * Set [popUpToCurrent] to false to keep the current screen on the back stack.
 */
fun NavController.navigateReplacingCurrent(route: AppRoute, popUpToCurrent: Boolean = true) {
    val current = currentDestination?.route
    navigate(route.toLegacyRoute()) {
        if (popUpToCurrent) {
            current?.let { popUpTo(it) { inclusive = true } }
        }
    }
}

/** Pop entries down to [route], also removing it when [inclusive]. */
fun NavController.popBackStackTo(route: AppRoute, inclusive: Boolean = false): Boolean =
    popBackStack(route = route.toLegacyScreen().screenRoute, inclusive = inclusive)
