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

package eu.europa.ec.uilogic.component.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

// `OneTimeLaunchedEffect` used to live here. It guarded its block with `rememberSaveable`, which
// meant "once per saved-state lifetime" — so after process death the restored flag suppressed the
// block even though the process, and every ViewModel in it, was brand new. Every caller used it to
// kick off ViewModel-scoped initialisation, so every caller was silently broken on the restore path.
//
// It is deliberately not replaced: work that must happen once per ViewModel belongs in the
// ViewModel's `init`, which survives configuration change and re-runs after process death. Where the
// work needs data from the composition (LoadingViewModel, RequestViewModel), the pattern is a plain
// `LaunchedEffect(Unit)` plus a non-saveable ViewModel-scoped guard. Note that simply switching this
// helper to `remember` would have been wrong: it re-fires on every configuration change.

@Composable
fun LifecycleEffect(
    lifecycleOwner: LifecycleOwner, lifecycleEvent: Lifecycle.Event, block: () -> Unit
) {
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == lifecycleEvent) {
                block()
            }
        }
        // Add the observer to the lifecycle
        lifecycleOwner.lifecycle.addObserver(observer)

        // When the effect leaves the Composition, remove the observer
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}