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
// `featureDashboardGraph`.
//
// Note that `DashboardScreen` keeps its own nested `NavHost` for the three bottom-navigation tabs.
// That controller is self-contained tab state (with `saveState`/`restoreState` for per-tab
// preservation) and has no relationship to the app's back stack, so it is untouched here — the
// dashboard entry simply passes it the app [AppNavigator] for the destinations the tabs navigate to.
package eu.europa.ec.dashboardfeature.router

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.dashboardfeature.ui.dashboard.DashboardScreen
import eu.europa.ec.dashboardfeature.ui.document_sign.DocumentSignScreen
import eu.europa.ec.dashboardfeature.ui.documents.detail.DocumentDetailsScreen
import eu.europa.ec.dashboardfeature.ui.settings.SettingsScreen
import eu.europa.ec.dashboardfeature.ui.transactions.detail.TransactionDetailsScreen
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentDetailsRoute
import eu.europa.ec.shared.navigation.DocumentSignRoute
import eu.europa.ec.shared.navigation.SettingsRoute
import eu.europa.ec.shared.navigation.TransactionDetailsRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

fun EntryProviderScope<NavKey>.featureDashboardEntries(navigator: AppNavigator) {
    entry<DashboardRoute> {
        DashboardScreen(
            navigator = navigator,
            viewModel = koinViewModel(),
            documentsViewModel = koinViewModel(),
            homeViewModel = koinViewModel(),
            transactionsViewModel = koinViewModel()
        )
    }

    entry<SettingsRoute> {
        SettingsScreen(
            navigator = navigator,
            viewModel = koinViewModel()
        )
    }

    entry<DocumentSignRoute> {
        DocumentSignScreen(navigator, koinViewModel())
    }

    entry<DocumentDetailsRoute> { route ->
        DocumentDetailsScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.documentId) })
        )
    }

    entry<TransactionDetailsRoute> { route ->
        TransactionDetailsScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.transactionId) })
        )
    }
}
