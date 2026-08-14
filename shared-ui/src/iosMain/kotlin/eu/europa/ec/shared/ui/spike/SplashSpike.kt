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

// The iOS app's Compose entry point.
//
// It now renders the *real* shared screens from commonMain — the same composables the Android host
// renders through its `router/Entries.kt` files — inside [IosNavHost], which is the same upstream
// `NavDisplay`. So the interesting parts are no longer iOS-specific at all; what lives here is only
// what a host must supply: the `UIViewController`, the Koin start, and each route's view-model.
// Nothing stands in for a shared screen any more: `DashboardScreen` brought the real bottom
// navigation and retired the two-button `SharedScreenSwitcher`.
//
// Everything this file was originally written to prove is now proven on the simulator: Compose MP
// renders on iOS, a commonMain `MviViewModel` works inside a real iOS app lifecycle, compose-resources
// resolve at render time (strings *and* the logo drawable), Koin resolves on Kotlin/Native, and a
// shared view-model's navigation effect drives a real back stack.
//
// The interactor is a stub because every shared view-model's interactor *implementation* is still
// Android-side. So this proves the UI/DI/MVI/navigation/resources stack, NOT wallet functionality.
package eu.europa.ec.shared.ui.spike

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentDetailsRoute
import eu.europa.ec.shared.navigation.SplashRoute
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.ic_logo_lockup_mark
import eu.europa.ec.shared.resources.ic_logo_lockup_wordmark
import eu.europa.ec.dashboardfeature.interactor.DashboardInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentsInteractor
import eu.europa.ec.dashboardfeature.interactor.HomeInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionsInteractor
import eu.europa.ec.dashboardfeature.ui.dashboard.DashboardScreen
import eu.europa.ec.dashboardfeature.ui.dashboard.DashboardViewModel
import eu.europa.ec.dashboardfeature.ui.documents.detail.DocumentDetailsScreen
import eu.europa.ec.dashboardfeature.ui.documents.detail.DocumentDetailsViewModel
import eu.europa.ec.dashboardfeature.ui.documents.list.DocumentsViewModel
import eu.europa.ec.dashboardfeature.ui.home.HomeViewModel
import eu.europa.ec.dashboardfeature.ui.settings.SettingsScreen
import eu.europa.ec.dashboardfeature.ui.settings.SettingsViewModel
import eu.europa.ec.dashboardfeature.ui.transactions.list.TransactionsViewModel
import eu.europa.ec.dashboardfeature.interactor.SettingsInteractor
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractor
import eu.europa.ec.issuancefeature.interactor.DocumentIssuanceSuccessInteractor
import eu.europa.ec.issuancefeature.interactor.DocumentOfferInteractor
import eu.europa.ec.issuancefeature.ui.add.AddDocumentScreen
import eu.europa.ec.issuancefeature.ui.add.AddDocumentViewModel
import eu.europa.ec.issuancefeature.ui.code.DocumentOfferCodeScreen
import eu.europa.ec.issuancefeature.ui.code.DocumentOfferCodeViewModel
import eu.europa.ec.issuancefeature.ui.offer.DocumentOfferScreen
import eu.europa.ec.issuancefeature.ui.offer.DocumentOfferViewModel
import eu.europa.ec.issuancefeature.ui.success.DocumentIssuanceSuccessScreen
import eu.europa.ec.issuancefeature.ui.success.DocumentIssuanceSuccessViewModel
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.DocumentIssuanceSuccessRoute
import eu.europa.ec.shared.navigation.DocumentOfferCodeRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.SettingsRoute
import eu.europa.ec.uilogic.navigation.helper.DeepLinkClassifier
import eu.europa.ec.resourceslogic.theme.ThemeManager
import eu.europa.ec.shared.ui.di.SharedUiModule
import eu.europa.ec.shared.ui.di.module as sharedUiDefinitions
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.ui.navigation.IosNavHost
import eu.europa.ec.uilogic.component.AppIconKey
import eu.europa.ec.uilogic.component.drawableResource
import eu.europa.ec.startupfeature.interactor.SplashInteractor
import eu.europa.ec.startupfeature.ui.splash.SplashScreen
import eu.europa.ec.startupfeature.ui.splash.SplashViewModel
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

/**
 * Entry point for the Swift side.
 *
 * Note how small the surface is: one function returning a `UIViewController`. Swift never names a
 * view-model, state, effect or route, which is what keeps the Obj-C flat-namespace export problem
 * (`State`, `State_`, `State__`, …) harmless on this architecture.
 */
fun SplashSpikeViewController(): UIViewController {
    startKoinIfNeeded()
    // The string catalog resolves synchronously once warmed, which is what lets shared interactors read
    // strings outside a coroutine — so it must be warmed before anything renders. Android does this in
    // `Application.onCreate`; this is the iOS equivalent.
    runBlocking { KoinPlatform.getKoin().get<StringCatalog>().warm() }
    return ComposeUIViewController {
        // The wallet's real theme, not bare Material — it lives in commonMain as of the theme port,
        // so iOS finally renders the app's own colours, shapes and typography.
        ThemeManager.instance.Theme {
            Surface(modifier = Modifier.fillMaxSize()) {
                // Same `entry<Route> { }` shape the Android feature modules use in their
                // `router/Entries.kt`, so a feature will contribute destinations identically on both
                // platforms once its screens are shared.
                IosNavHost(startRoute = SplashRoute) { navigator ->
                    entry<SplashRoute> {
                        // The REAL shared screen from commonMain — the same composable the Android
                        // host renders via `featureStartupEntries`. Only the view-model's construction
                        // differs: Android uses `koinViewModel()`, and here it is built from the
                        // Koin-resolved interactor.
                        val interactor = remember { KoinPlatform.getKoin().get<SplashInteractor>() }
                        SplashScreen(
                            navigator = navigator,
                            viewModel = remember { SplashViewModel(interactor) },
                        )
                    }
                    entry<DocumentDetailsRoute> { route ->
                        // The shared details screen. The two deep-link lambdas keep their no-op
                        // defaults: iOS deep links arrive through the app delegate and are not wired.
                        val detailsInteractor =
                            remember { KoinPlatform.getKoin().get<DocumentDetailsInteractor>() }
                        DocumentDetailsScreen(
                            navigator = navigator,
                            viewModel = remember {
                                DocumentDetailsViewModel(
                                    documentDetailsInteractor = detailsInteractor,
                                    deepLinkClassifier = KoinPlatform.getKoin().get(),
                                    documentId = route.documentId,
                                )
                            },
                        )
                    }
                    entry<DashboardRoute> {
                        // The REAL shared dashboard: its own bottom navigation, its own side menu and
                        // the Home / Documents / Transactions tabs, all from commonMain. This replaced
                        // the `SharedScreenSwitcher` stand-in — a two-button row that existed only
                        // because the tabs are a *selection* inside this screen rather than routes, so
                        // there was nowhere else to hang them.
                        //
                        // All four host-injected lambdas keep their defaults: three are Android's
                        // activity plumbing (the one-shot pending intent, the deep-link and DC-API
                        // hand-offs), and the revocation reader cannot fire because
                        // `SystemBroadcastReceiver` is a no-op here.
                        val koin = remember { KoinPlatform.getKoin() }
                        DashboardScreen(
                            navigator = navigator,
                            viewModel = remember {
                                DashboardViewModel(
                                    dashboardInteractor = koin.get<DashboardInteractor>(),
                                    deepLinkClassifier = koin.get<DeepLinkClassifier>(),
                                )
                            },
                            documentsViewModel = remember {
                                DocumentsViewModel(koin.get<DocumentsInteractor>())
                            },
                            homeViewModel = remember { HomeViewModel(koin.get<HomeInteractor>()) },
                            transactionsViewModel = remember {
                                TransactionsViewModel(koin.get<TransactionsInteractor>())
                            },
                        )
                    }
                    entry<AddDocumentRoute> { route ->
                        // The shared add-document screen, over the shared interactor. What iOS cannot
                        // answer yet is narrower than the screen: `IosAddDocumentPlatformBridge` reports
                        // that it has no issuer catalogue, and the screen renders that as its error state
                        // — the list, its grouping and its routing are the same code Android runs.
                        val koin = remember { KoinPlatform.getKoin() }
                        AddDocumentScreen(
                            navigator = navigator,
                            viewModel = remember {
                                AddDocumentViewModel(
                                    addDocumentInteractor = koin.get<AddDocumentInteractor>(),
                                    deepLinkClassifier = koin.get<DeepLinkClassifier>(),
                                    issuanceConfig = route.config,
                                )
                            },
                        )
                    }
                    entry<DocumentOfferRoute> { route ->
                        val koin = remember { KoinPlatform.getKoin() }
                        DocumentOfferScreen(
                            navigator = navigator,
                            viewModel = remember {
                                DocumentOfferViewModel(
                                    deepLinkClassifier = koin.get<DeepLinkClassifier>(),
                                    offerUiConfig = route.config,
                                    documentOfferInteractor = koin.get<DocumentOfferInteractor>(),
                                )
                            },
                        )
                    }
                    entry<DocumentOfferCodeRoute> { route ->
                        val koin = remember { KoinPlatform.getKoin() }
                        DocumentOfferCodeScreen(
                            navigator = navigator,
                            viewModel = remember {
                                DocumentOfferCodeViewModel(
                                    offerCodeUiConfig = route.config,
                                    documentOfferInteractor = koin.get<DocumentOfferInteractor>(),
                                )
                            },
                        )
                    }
                    entry<DocumentIssuanceSuccessRoute> { route ->
                        // Fully working on iOS: its interactor is shared and the documents it lists come
                        // from multipaz through the document-details bridge.
                        val koin = remember { KoinPlatform.getKoin() }
                        DocumentIssuanceSuccessScreen(
                            navigator = navigator,
                            viewModel = remember {
                                DocumentIssuanceSuccessViewModel(
                                    interactor = koin.get<DocumentIssuanceSuccessInteractor>(),
                                    issuanceSuccessUiConfig = route.config,
                                )
                            },
                        )
                    }
                    entry<SettingsRoute> {
                        // Reached from the dashboard's side menu. The shared screen; iOS's own
                        // `SettingsPlatformBridge` decides which rows it can honestly offer.
                        val settingsInteractor =
                            remember { KoinPlatform.getKoin().get<SettingsInteractor>() }
                        SettingsScreen(
                            navigator = navigator,
                            viewModel = remember { SettingsViewModel(settingsInteractor) },
                        )
                    }
                }
            }
        }
    }
}

private val spikeModule = module {
    // Stands in for the Android `SplashInteractorImpl`, which needs ResourceProvider/ConfigLogic/
    // QuickPinInteractor. Resolving it through Koin rather than calling the constructor is the point:
    // it exercises the DI graph on Kotlin/Native.
    single<SplashInteractor> { IosStubSplashInteractor() }
}

private fun startKoinIfNeeded() {
    if (KoinPlatform.getKoinOrNull() != null) return
    // `SharedUiModule().module` is generated by the Koin compiler plugin from the module's
    // @ComponentScan. Loading it is what brings in the iosMain-only definitions — the multipaz
    // WalletEngine and the iOS HomeInteractor — because the scan runs per compilation.
    startKoin { modules(SharedUiModule().sharedUiDefinitions(), spikeModule) }
}

/**
 * Stands in for the dashboard, and doubles as the **icon-corpus check**.
 *
 * Renders every `AppIconKey` that has a migrated drawable, which is how the 53-vector corpus gets
 * verified at all: compose-resources' vector parser is *common* code, so if an icon parses and draws
 * here it parses the same way on Android — and the Android AVD's screencap returns black for Compose
 * windows, making this the only place the pixels can actually be inspected.
 *
 * Anything blank, black-on-black or the wrong colour in this grid is a parse or transform defect.
 *
 * No longer wired to a route — `DashboardRoute` now renders the real shared `HomeScreen`. To use this
 * again, point that entry here temporarily.
 */
@Suppress("unused") // Kept as the icon-corpus check; see the KDoc above for how to reach it.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardSpikeScreen() {
    val keys = remember { AppIconKey.entries.filter { it.drawableResource != null } }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Reached from the shared SplashScreen", style = MaterialTheme.typography.titleSmall)
        Text(
            "icon corpus: ${keys.size} drawable-backed keys",
            style = MaterialTheme.typography.bodySmall,
        )

        // The brand lockup, drawn the way `AppIconAndText` draws it: the coloured mark with the wordmark
        // overlaid and tinted from the scheme. Shown under both schemes because that is the whole point
        // of splitting the asset — upstream the wordmark used `?colorOnSurface`, which compose-resources
        // cannot resolve, so a single image would be stuck at one theme's colour.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialTheme(colorScheme = lightColorScheme()) { LockupLayers(label = "light") }
            MaterialTheme(colorScheme = darkColorScheme()) { LockupLayers(label = "dark") }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(keys.size) { index ->
                val key = keys[index]
                Box(
                    modifier = Modifier.padding(6.dp).size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(key.drawableResource!!),
                        contentDescription = key.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}


/** The two-layer lockup, mirroring `AppIconAndText` in :ui-logic. */
@Composable
private fun LockupLayers(label: String) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Box {
                Image(
                    painter = painterResource(Res.drawable.ic_logo_lockup_mark),
                    contentDescription = null,
                )
                Icon(
                    painter = painterResource(Res.drawable.ic_logo_lockup_wordmark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

@Composable
private fun SpikeColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        content()
    }
}

private class IosStubSplashInteractor : SplashInteractor {
    override suspend fun getAfterSplashRoute(): AppRoute = DashboardRoute
}
