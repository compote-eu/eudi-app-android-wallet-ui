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

// SPIKE — the first Compose Multiplatform UI in this project, and the first time a shared view-model
// drives a real screen on iOS. It answers questions no Gradle task can:
//
//   1. does Compose MP actually render on iOS (the Skiko path we had never exercised)?
//   2. does a commonMain `MviViewModel` — viewModelScope, coroutines, its `init` block — work inside a
//      real iOS app lifecycle?
//   3. do compose-resources strings resolve at *render* time on iOS, including argument substitution?
//   4. does Koin resolve shared definitions on Kotlin/Native?
//   5. does the hand-written [IosNavHost] navigate a real back stack, driven by a shared view-model's
//      navigation *effect* — the same `Effect.Navigation.SwitchScreen` the Android screens consume?
//
// Deliberately in iosMain, not commonMain: Compose MP's UI artifacts map onto AndroidX Compose on
// Android, so putting them in commonMain would place them on the shipping app's classpath beside its
// own Compose BOM. None of the questions above depend on where this source sits.
//
// The interactor is a stub because every shared view-model's interactor *implementation* is still
// Android-side. So this proves the UI/DI/MVI/navigation/resources stack, NOT wallet functionality.
package eu.europa.ec.shared.ui.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.home_screen_welcome_user_message
import eu.europa.ec.shared.ui.navigation.IosNavHost
import eu.europa.ec.startupfeature.interactor.SplashInteractor
import eu.europa.ec.startupfeature.ui.splash.Effect
import eu.europa.ec.startupfeature.ui.splash.SplashViewModel
import org.jetbrains.compose.resources.stringResource
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
                        SplashSpikeScreen(onRouteResolved = { navigator.navigate(it) })
                    }
                    entry<DashboardRoute> {
                        DashboardSpikeScreen(onBack = { navigator.pop() })
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

@Composable
private fun SplashSpikeScreen(onRouteResolved: (AppRoute) -> Unit) {
    val interactor = remember { KoinPlatform.getKoin().get<SplashInteractor>() }
    val viewModel = remember { SplashViewModel(interactor) }
    val state by viewModel.viewState.collectAsState()
    var waited by remember { mutableStateOf(false) }

    // The view-model's `init` already started the timer. Consuming its navigation effect here is the
    // same contract the Android `SplashScreen` implements — proof that a shared view-model can drive
    // real navigation on iOS.
    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            if (effect is Effect.Navigation.SwitchScreen) {
                waited = true
                onRouteResolved(effect.route)
            }
        }
    }

    SpikeColumn {
        Text("Compose MP on ${Platform.name}", style = MaterialTheme.typography.titleMedium)

        // A *formatted* corpus string, so this proves positional argument substitution on iOS, not
        // just lookup.
        Text(
            text = stringResource(Res.string.home_screen_welcome_user_message, "iOS"),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "shared SplashViewModel: logoAnimationDuration = ${state.logoAnimationDuration} ms",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "interactor via Koin: ${interactor::class.simpleName}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!waited) {
            CircularProgressIndicator()
            Text("waiting for the splash decision…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DashboardSpikeScreen(onBack: () -> Unit) {
    // Per-entry state: this counter belongs to the Dashboard entry, and the host gives that entry its
    // own SaveableStateHolder slot and its own ViewModelStore.
    var taps by remember { mutableStateOf(0) }

    SpikeColumn {
        Text("Navigated by the shared view-model", style = MaterialTheme.typography.titleMedium)
        Text("DashboardRoute", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "IosNavHost pushed this entry after SplashViewModel emitted " +
                    "Effect.Navigation.SwitchScreen.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { taps++ }) { Text("per-entry state: $taps") }
        Button(onClick = onBack) { Text("navigator.pop()") }
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
