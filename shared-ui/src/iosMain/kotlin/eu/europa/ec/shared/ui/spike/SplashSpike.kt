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
// It now renders the *real* shared `SplashScreen` from commonMain — the same composable the Android
// host renders through `featureStartupEntries` — inside [IosNavHost], which is the same upstream
// `NavDisplay`. So the interesting parts are no longer iOS-specific at all; what lives here is only
// what a host must supply: the `UIViewController`, the Koin start, and a stand-in for the dashboard,
// which is still Android-only.
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
import eu.europa.ec.shared.navigation.SplashRoute
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.ic_logo_lockup_mark
import eu.europa.ec.shared.resources.ic_logo_lockup_wordmark
import eu.europa.ec.dashboardfeature.interactor.HomeInteractor
import eu.europa.ec.dashboardfeature.ui.home.HomeScreen
import eu.europa.ec.dashboardfeature.ui.home.HomeViewModel
import eu.europa.ec.resourceslogic.theme.ThemeManager
import eu.europa.ec.shared.ui.di.SharedUiModule
import eu.europa.ec.shared.ui.di.module as sharedUiDefinitions
import eu.europa.ec.shared.ui.navigation.IosNavHost
import eu.europa.ec.uilogic.component.AppIconKey
import eu.europa.ec.uilogic.component.drawableResource
import eu.europa.ec.startupfeature.interactor.SplashInteractor
import eu.europa.ec.startupfeature.ui.splash.SplashScreen
import eu.europa.ec.startupfeature.ui.splash.SplashViewModel
import org.jetbrains.compose.resources.painterResource
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
                    entry<DashboardRoute> {
                        // The REAL shared HomeScreen, driven by the iOS HomeInteractor over the
                        // Kotlin-over-multipaz WalletEngine — so the name it greets you with comes
                        // out of a credential stored in multipaz's DocumentStore.
                        val homeInteractor = remember { KoinPlatform.getKoin().get<HomeInteractor>() }
                        HomeScreen(
                            navigator = navigator,
                            viewModel = remember { HomeViewModel(homeInteractor) },
                            onDashboardEventSent = {},
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
