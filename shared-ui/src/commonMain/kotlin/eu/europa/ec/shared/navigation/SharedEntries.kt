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

package eu.europa.ec.shared.navigation

import androidx.compose.runtime.remember
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.commonfeature.ui.biometric.BiometricScreen
import eu.europa.ec.commonfeature.ui.document_success.DocumentSuccessScreen
import eu.europa.ec.commonfeature.ui.pin.PinScreen
import eu.europa.ec.commonfeature.ui.qr_scan.QrScanScreen
import eu.europa.ec.commonfeature.ui.success.SuccessScreen
import eu.europa.ec.dashboardfeature.ui.dashboard.DashboardScreen
import eu.europa.ec.dashboardfeature.ui.document_sign.DocumentSignScreen
import eu.europa.ec.dashboardfeature.ui.documents.detail.DocumentDetailsScreen
import eu.europa.ec.dashboardfeature.ui.settings.SettingsScreen
import eu.europa.ec.dashboardfeature.ui.transactions.detail.TransactionDetailsScreen
import eu.europa.ec.issuancefeature.ui.add.AddDocumentScreen
import eu.europa.ec.issuancefeature.ui.code.DocumentOfferCodeScreen
import eu.europa.ec.issuancefeature.ui.offer.DocumentOfferScreen
import eu.europa.ec.issuancefeature.ui.success.DocumentIssuanceSuccessScreen
import eu.europa.ec.presentationfeature.ui.loading.PresentationLoadingScreen
import eu.europa.ec.presentationfeature.ui.request.PresentationRequestScreen
import eu.europa.ec.presentationfeature.ui.success.PresentationSuccessViewModel
import eu.europa.ec.proximityfeature.ui.loading.ProximityLoadingScreen
import eu.europa.ec.proximityfeature.ui.qr.ProximityQRScreen
import eu.europa.ec.proximityfeature.ui.request.ProximityRequestScreen
import eu.europa.ec.proximityfeature.ui.success.ProximitySuccessViewModel
import eu.europa.ec.startupfeature.ui.splash.SplashScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Every destination in the app, bound once for both platforms.
 *
 * These 21 entries used to be written twice — six `router/Entries.kt` files in the Android feature
 * modules and one block inside `IosAppRoot` — even though they call the same screens from commonMain.
 * Two things kept them apart, and both are gone:
 *
 *  - **view-model acquisition.** Android used `koinViewModel()` from `koin-androidx-compose`, so iOS
 *    built each view-model by hand with `remember { VM(koin.get()) }`. `koin-compose-viewmodel` is
 *    multiplatform, and the shared view-models already carried `@KoinViewModel` with `@InjectedParam`
 *    for their route arguments, so one `koinViewModel { parametersOf(…) }` now serves both. iOS gains
 *    a real `ViewModelStore` scope from it, so a popped entry's `viewModelScope` is cancelled —
 *    `remember` never did that.
 *  - **the platform behaviour** each screen takes as an injected lambda, which is now answered
 *    through [LocalNavPlatformActions]. See [NavPlatformActions] for why it has to be inverted.
 *
 * 🪤 The `parkAndReturn`-then-`openDeepLink` shape below is not a simplification of what Android did:
 * it is that code, moved. Android's implementation parks the link and pops, returning true; iOS's
 * declines, so the fallback runs and iOS behaves exactly as it did when it passed no lambda at all.
 * Keep that property when adding a hook — the point of sharing an entry is not to give one platform
 * the other's behaviour.
 */
fun EntryProviderScope<NavKey>.sharedAppEntries(navigator: AppNavigator) {

    entry<SplashRoute> {
        SplashScreen(navigator, koinViewModel())
    }

    entry<QuickPinRoute> { route ->
        PinScreen(navigator, koinViewModel { parametersOf(route.pinFlow) })
    }

    entry<QrScanRoute> { route ->
        QrScanScreen(navigator, koinViewModel { parametersOf(route.config) })
    }

    entry<BiometricRoute> { route ->
        val platform = LocalNavPlatformActions.current
        BiometricScreen(
            navigator = navigator,
            viewModel = koinViewModel { parametersOf(route.config) },
            onExternalDeepLink = { link, routeToPop, isPreAuthorization ->
                if (!platform.parkAndReturn(navigator, link, routeToPop, isPreAuthorization)) {
                    platform.openDeepLink(navigator, link)
                }
            },
        )
    }

    entry<SuccessRoute> { route ->
        val platform = LocalNavPlatformActions.current
        SuccessScreen(
            navigator = navigator,
            viewModel = koinViewModel { parametersOf(route.config) },
            onExternalDeepLink = { link, routeToPop ->
                if (!platform.parkAndReturn(navigator, link, routeToPop)) navigator.pop()
            },
        )
    }

    entry<DashboardRoute> {
        val platform = LocalNavPlatformActions.current
        DashboardScreen(
            navigator = navigator,
            viewModel = koinViewModel(),
            documentsViewModel = koinViewModel(),
            homeViewModel = koinViewModel(),
            transactionsViewModel = koinViewModel(),
            pendingLaunchIntent = { platform.pendingLaunchIntent() },
            onExternalDeepLink = { link, route -> platform.openDeepLink(navigator, link, route) },
            onIntentAction = { action, route -> platform.openIntentAction(navigator, action, route) },
            revokedDocumentsFromBroadcast = { platform.revokedDocumentsFromBroadcast(it) },
        )
    }

    entry<SettingsRoute> {
        SettingsScreen(navigator = navigator, viewModel = koinViewModel())
    }

    entry<DocumentSignRoute> {
        DocumentSignScreen(navigator, koinViewModel())
    }

    entry<DocumentDetailsRoute> { route ->
        val platform = LocalNavPlatformActions.current
        DocumentDetailsScreen(
            navigator = navigator,
            viewModel = koinViewModel { parametersOf(route.documentId) },
            pendingDeepLink = { platform.pendingDeepLink() },
            onExternalDeepLink = { link, routeToPop ->
                if (!platform.parkAndReturn(navigator, link, routeToPop)) {
                    platform.openDeepLink(navigator, link)
                }
            },
        )
    }

    entry<TransactionDetailsRoute> { route ->
        TransactionDetailsScreen(
            navigator = navigator,
            viewModel = koinViewModel { parametersOf(route.transactionId) },
        )
    }

    entry<AddDocumentRoute> { route ->
        val platform = LocalNavPlatformActions.current
        AddDocumentScreen(
            navigator = navigator,
            viewModel = koinViewModel { parametersOf(route.config) },
            pendingDeepLink = { platform.pendingDeepLink() },
            onExternalDeepLink = { link, routeToOpen ->
                platform.openDeepLink(navigator, link, routeToOpen)
            },
        )
    }

    entry<DocumentOfferRoute> { route ->
        val platform = LocalNavPlatformActions.current
        DocumentOfferScreen(
            navigator = navigator,
            viewModel = koinViewModel { parametersOf(route.config) },
            pendingDeepLink = { platform.pendingDeepLink() },
            onExternalDeepLink = { link, routeToPop ->
                if (!platform.parkAndReturn(navigator, link, routeToPop)) {
                    platform.openDeepLink(navigator, link)
                }
            },
        )
    }

    entry<DocumentOfferCodeRoute> { route ->
        DocumentOfferCodeScreen(
            navigator = navigator,
            viewModel = koinViewModel { parametersOf(route.config) },
        )
    }

    entry<DocumentIssuanceSuccessRoute> { route ->
        val platform = LocalNavPlatformActions.current
        DocumentIssuanceSuccessScreen(
            navigator = navigator,
            viewModel = koinViewModel { parametersOf(route.config) },
            onExternalDeepLink = { link, routeToPop ->
                if (!platform.parkAndReturn(navigator, link, routeToPop)) navigator.pop()
            },
            onFinishWithResult = { platform.finishWithResult(it) },
        )
    }

    entry<PresentationRequestRoute> { route ->
        val platform = LocalNavPlatformActions.current
        // Read once per entry, not once per recomposition: it is a one-shot slot the host filled just
        // before pushing this route.
        val intentAction = remember { platform.consumePendingIntentAction() }
        PresentationRequestScreen(
            intentAction = intentAction,
            navigator = navigator,
            viewModel = koinViewModel { parametersOf(route.config) },
        )
    }

    entry<PresentationLoadingRoute> { route ->
        PresentationLoadingScreen(navigator, koinViewModel { parametersOf(route.scopeId) })
    }

    entry<PresentationSuccessRoute> { route ->
        val platform = LocalNavPlatformActions.current
        DocumentSuccessScreen(
            navigator = navigator,
            viewModel = koinViewModel<PresentationSuccessViewModel> { parametersOf(route.scopeId) },
            onExternalDeepLink = { link, routeToPop ->
                if (!platform.parkAndReturn(navigator, link, routeToPop)) navigator.pop()
            },
            onFinishWithResult = { platform.finishWithResult(it) },
        )
    }

    entry<ProximityQrRoute> { route ->
        ProximityQRScreen(navigator, koinViewModel { parametersOf(route.config) })
    }

    entry<ProximityRequestRoute> { route ->
        ProximityRequestScreen(navigator, koinViewModel { parametersOf(route.scopeId) })
    }

    entry<ProximityLoadingRoute> { route ->
        ProximityLoadingScreen(navigator, koinViewModel { parametersOf(route.scopeId) })
    }

    entry<ProximitySuccessRoute> { route ->
        val platform = LocalNavPlatformActions.current
        DocumentSuccessScreen(
            navigator = navigator,
            viewModel = koinViewModel<ProximitySuccessViewModel> { parametersOf(route.scopeId) },
            onExternalDeepLink = { link, routeToPop ->
                if (!platform.parkAndReturn(navigator, link, routeToPop)) navigator.pop()
            },
            onFinishWithResult = { platform.finishWithResult(it) },
        )
    }
}
