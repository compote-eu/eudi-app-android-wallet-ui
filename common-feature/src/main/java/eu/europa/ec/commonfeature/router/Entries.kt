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
// `featureCommonGraph`.
//
// Each entry receives its typed route, so a screen's arguments are read straight off the key and
// handed to the view-model as objects. That retires the whole `navArgument(serializedKeyName)` +
// `arguments?.getString(...)` + `UiSerializer.fromBase64` chain these four reusable screens used to
// go through.
package eu.europa.ec.commonfeature.router

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.commonfeature.ui.biometric.BiometricScreen
import eu.europa.ec.commonfeature.ui.pin.PinScreen
import eu.europa.ec.commonfeature.ui.qr_scan.QrScanScreen
import eu.europa.ec.commonfeature.ui.success.SuccessScreen
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.BiometricRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.shared.navigation.QuickPinRoute
import eu.europa.ec.shared.navigation.SuccessRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

fun EntryProviderScope<NavKey>.featureCommonEntries(navigator: AppNavigator) {
    entry<BiometricRoute> { route ->
        BiometricScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.config) })
        )
    }

    entry<SuccessRoute> { route ->
        SuccessScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.config) })
        )
    }

    entry<QuickPinRoute> { route ->
        PinScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.pinFlow) })
        )
    }

    entry<QrScanRoute> { route ->
        QrScanScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.config) })
        )
    }
}
