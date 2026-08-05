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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import eu.europa.ec.shared.Platform
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.SplashRoute
import eu.europa.ec.shared.ui.navigation.IosNavHost
import eu.europa.ec.startupfeature.interactor.SplashInteractor
import eu.europa.ec.startupfeature.ui.splash.SplashScreen
import eu.europa.ec.startupfeature.ui.splash.SplashViewModel
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
        MaterialTheme {
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
                        DashboardSpikeScreen()
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
    startKoin { modules(spikeModule) }
}

/**
 * Stands in for the dashboard, which is still Android-only. Reaching it is the proof: the *shared*
 * `SplashScreen` ran, its shared view-model resolved a route, and the host swapped the entry.
 *
 * Note there is no back button. The shared screen navigates with `navigateReplacingCurrent`, which
 * replaces the splash entry rather than stacking on top of it — so this is the root, exactly as on
 * Android, and `pop()` would correctly do nothing.
 */
@Composable
private fun DashboardSpikeScreen() {
    // Per-entry state, held in the SaveableStateHolder slot the host gives this entry.
    var taps by remember { mutableStateOf(0) }

    SpikeColumn {
        Text("Reached from the shared SplashScreen", style = MaterialTheme.typography.titleMedium)
        Text("DashboardRoute", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "The commonMain SplashScreen rendered its logo and animation here, then its " +
                    "shared view-model emitted Effect.Navigation.SwitchScreen and NavDisplay " +
                    "replaced the entry.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Compose MP on ${Platform.name}", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { taps++ }) { Text("per-entry state: $taps") }
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
