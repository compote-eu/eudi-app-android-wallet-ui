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
// `presentationGraph`.
package eu.europa.ec.presentationfeature.router

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.presentationfeature.ui.loading.PresentationLoadingScreen
import eu.europa.ec.presentationfeature.ui.request.PresentationRequestScreen
import eu.europa.ec.presentationfeature.ui.success.PresentationSuccessScreen
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.PresentationLoadingRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.PresentationSuccessRoute
import eu.europa.ec.uilogic.extension.consumePendingIntentAction
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

fun EntryProviderScope<NavKey>.presentationEntries(navigator: AppNavigator) {
    entry<PresentationRequestRoute> { route ->
        // The DC_API hand-off. This used to be read off `previousBackStackEntry.savedStateHandle`;
        // it is now the activity-scoped one-shot slot that `handleIntentAction` filled just before
        // pushing this route. `remember` gives it the same read-once-per-entry semantics that
        // `savedStateHandle.remove()` had — except it now also survives recomposition, which the
        // old expression did not.
        val context = LocalContext.current
        val intentAction = remember { context.consumePendingIntentAction() }

        PresentationRequestScreen(
            intentAction = intentAction,
            navigator = navigator,
            viewModel = koinViewModel(parameters = { parametersOf(route.config) })
        )
    }

    entry<PresentationLoadingRoute> { route ->
        PresentationLoadingScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.scopeId) })
        )
    }

    entry<PresentationSuccessRoute> { route ->
        PresentationSuccessScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.scopeId) })
        )
    }
}
