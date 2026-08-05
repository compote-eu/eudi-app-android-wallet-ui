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
// drives a real screen on iOS. Its job is to answer four questions that no Gradle task can:
//
//   1. does Compose MP actually render on iOS (the Skiko path we have never exercised)?
//   2. does a commonMain `MviViewModel` — viewModelScope, coroutines, its `init` block — work inside a
//      real iOS app lifecycle?
//   3. do compose-resources strings resolve at *render* time on iOS, including argument substitution?
//      (until now only unit-tested)
//   4. does Koin resolve shared definitions on Kotlin/Native?
//
// Deliberately in iosMain, not commonMain: Compose MP's UI artifacts map onto AndroidX Compose on
// Android, so putting them in commonMain would place them on the shipping app's classpath beside its
// own Compose BOM. None of the questions above depend on where this source sits. When the real screens
// move to commonMain that dependency question gets decided on its own merits.
//
// The interactor is a stub because every shared view-model's interactor *implementation* is still
// Android-side. So this proves the UI/DI/MVI/resources stack, NOT wallet functionality on iOS.
package eu.europa.ec.shared.ui.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.home_screen_welcome_user_message
import eu.europa.ec.startupfeature.interactor.SplashInteractor
import eu.europa.ec.startupfeature.ui.splash.Effect
import eu.europa.ec.startupfeature.ui.splash.SplashViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

/**
 * Entry point for the Swift side. Returns a `UIViewController` hosting Compose, which is the whole
 * surface iOS needs — note it exposes no view-model types, which is why the flat-namespace
 * `State`/`State_`/`State__` export problem stays harmless on this architecture.
 */
fun SplashSpikeViewController(): UIViewController {
    startKoinIfNeeded()
    return ComposeUIViewController { SplashSpike() }
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
private fun SplashSpike() {
    val interactor = remember { KoinPlatform.getKoin().get<SplashInteractor>() }
    val viewModel = remember { SplashViewModel(interactor) }
    val state by viewModel.viewState.collectAsState()
    var resolvedRoute by remember { mutableStateOf<AppRoute?>(null) }

    // The view-model's `init` already started the timer; this only observes where it decides to go.
    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            if (effect is Effect.Navigation.SwitchScreen) {
                resolvedRoute = effect.route
            }
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = "Compose MP on ${Platform.name}",
                    style = MaterialTheme.typography.titleMedium,
                )

                // A *formatted* corpus string, so this also proves positional argument substitution
                // works on iOS, not just lookup.
                Text(
                    text = stringResource(Res.string.home_screen_welcome_user_message, "iOS"),
                    style = MaterialTheme.typography.headlineSmall,
                )

                Text(
                    text = "shared SplashViewModel: logoAnimationDuration = " +
                            "${state.logoAnimationDuration} ms",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = "interactor via Koin: ${interactor::class.simpleName}",
                    style = MaterialTheme.typography.bodySmall,
                )

                val route = resolvedRoute
                if (route == null) {
                    CircularProgressIndicator()
                    Text("waiting for the splash decision…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        text = "routed to: ${route::class.simpleName}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

private class IosStubSplashInteractor : SplashInteractor {
    override suspend fun getAfterSplashRoute(): AppRoute = DashboardRoute
}
