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
// Note that the three bottom-navigation tabs are not entries here. They are a selection inside
// `DashboardScreen` (see [eu.europa.ec.dashboardfeature.ui.component.BottomNavigationItem]), never
// destinations on the app's back stack; the dashboard entry simply passes them the app
// [AppNavigator] for the destinations the tabs navigate to.
package eu.europa.ec.dashboardfeature.router

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.businesslogic.extension.getParcelableArrayListExtra
import eu.europa.ec.corelogic.model.RevokedDocumentParcel
import eu.europa.ec.corelogic.model.toDomain
import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.dashboardfeature.ui.dashboard.DashboardScreen
import eu.europa.ec.dashboardfeature.ui.dashboard.PendingLaunchIntent
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import eu.europa.ec.uilogic.extension.cacheUri
import eu.europa.ec.uilogic.extension.getPendingIntent
import eu.europa.ec.uilogic.extension.getPendingUri
import eu.europa.ec.uilogic.navigation.helper.handleDeepLinkAction
import eu.europa.ec.uilogic.navigation.helper.handleIntentAction
import eu.europa.ec.uilogic.navigation.helper.hasIntentAction
import eu.europa.ec.uilogic.navigation.helper.popBackStackTo
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

fun EntryProviderScope<NavKey>.featureDashboardEntries(navigator: AppNavigator) {
    entry<DashboardRoute> {
        // DashboardScreen is shared; everything below is what only the Android host can do —
        // the one-shot pending intent and the revocation broadcast's `Parcelable` extra live in
        // :ui-logic and :core-logic respectively, neither of which :shared-ui can see.
        val context = LocalContext.current
        DashboardScreen(
            navigator = navigator,
            viewModel = koinViewModel(),
            documentsViewModel = koinViewModel(),
            homeViewModel = koinViewModel(),
            transactionsViewModel = koinViewModel(),
            pendingLaunchIntent = {
                // `getPendingIntent()` is one-shot, so it is read once and both readings of it are
                // returned together: the link it carries and, failing that, the app action it is.
                val pendingIntent = context.getPendingIntent()
                PendingLaunchIntent(
                    deepLink = pendingIntent?.data?.toString(),
                    intentAction = hasIntentAction(pendingIntent),
                )
            },
            onExternalDeepLink = { link, route ->
                handleDeepLinkAction(
                    navigator = navigator,
                    context = context,
                    uri = link.toUri(),
                    route = route,
                )
            },
            onIntentAction = { action, route ->
                handleIntentAction(
                    navigator = navigator,
                    context = context,
                    action = action,
                    route = route,
                )
            },
            revokedDocumentsFromBroadcast = { intent ->
                intent.getParcelableArrayListExtra<RevokedDocumentParcel>(
                    action = CoreActions.REVOCATION_IDS_EXTRA
                )?.map { it.toDomain() }
            },
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
        // DocumentDetailsScreen is shared, so the Android-only deep-link plumbing is supplied here
        // rather than reached from commonMain: `handleDeepLinkAction` lives in :ui-logic, which
        // depends on :shared-ui, so the screen cannot call it directly.
        val context = LocalContext.current
        DocumentDetailsScreen(
            navigator = navigator,
            viewModel = koinViewModel(parameters = { parametersOf(route.documentId) }),
            pendingDeepLink = { context.getPendingUri()?.toString() },
            onExternalDeepLink = { link, routeToPop ->
                val uri = link.toUri()
                routeToPop?.let {
                    context.cacheUri(uri)
                    navigator.popBackStackTo(route = it, inclusive = false)
                } ?: handleDeepLinkAction(
                    navigator = navigator,
                    context = context,
                    uri = uri,
                )
            },
        )
    }

    entry<TransactionDetailsRoute> { route ->
        TransactionDetailsScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.transactionId) })
        )
    }
}
