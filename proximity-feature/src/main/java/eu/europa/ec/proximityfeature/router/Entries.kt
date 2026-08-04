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

// Nav3 Stage 5: this module's contribution to the host's `entryProvider`, replacing
// `featureProximityGraph`.
package eu.europa.ec.proximityfeature.router

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.proximityfeature.ui.loading.ProximityLoadingScreen
import eu.europa.ec.proximityfeature.ui.qr.ProximityQRScreen
import eu.europa.ec.proximityfeature.ui.request.ProximityRequestScreen
import eu.europa.ec.proximityfeature.ui.success.ProximitySuccessScreen
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.ProximityLoadingRoute
import eu.europa.ec.shared.navigation.ProximityQrRoute
import eu.europa.ec.shared.navigation.ProximityRequestRoute
import eu.europa.ec.shared.navigation.ProximitySuccessRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

fun EntryProviderScope<NavKey>.featureProximityEntries(navigator: AppNavigator) {
    entry<ProximityQrRoute> { route ->
        ProximityQRScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.config) })
        )
    }

    entry<ProximityRequestRoute> { route ->
        ProximityRequestScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.scopeId) })
        )
    }

    entry<ProximityLoadingRoute> { route ->
        ProximityLoadingScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.scopeId) })
        )
    }

    entry<ProximitySuccessRoute> { route ->
        ProximitySuccessScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.scopeId) })
        )
    }
}
