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
// `featureStartupGraph`.
//
// The `navigation(route = ModuleRoute.StartupModule)` wrapper is gone — the Nav3 back stack is flat,
// and `SplashRoute` is the host's start destination. `navDeepLink` is gone too: the app never
// navigated by URI pattern, it parsed deep links itself in `EudiComponentActivity` and navigated
// imperatively (see `handleDeepLinkAction`), so those declarations were unreachable.
package eu.europa.ec.startupfeature.router

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.SplashRoute
import eu.europa.ec.startupfeature.ui.splash.SplashScreen
import org.koin.androidx.compose.koinViewModel

fun EntryProviderScope<NavKey>.featureStartupEntries(navigator: AppNavigator) {
    entry<SplashRoute> {
        SplashScreen(navigator, koinViewModel())
    }
}
