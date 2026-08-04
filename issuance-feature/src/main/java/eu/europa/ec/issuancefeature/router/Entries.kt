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
// `featureIssuanceGraph`.
package eu.europa.ec.issuancefeature.router

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.issuancefeature.ui.add.AddDocumentScreen
import eu.europa.ec.issuancefeature.ui.code.DocumentOfferCodeScreen
import eu.europa.ec.issuancefeature.ui.offer.DocumentOfferScreen
import eu.europa.ec.issuancefeature.ui.success.DocumentIssuanceSuccessScreen
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.DocumentIssuanceSuccessRoute
import eu.europa.ec.shared.navigation.DocumentOfferCodeRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

fun EntryProviderScope<NavKey>.featureIssuanceEntries(navigator: AppNavigator) {
    entry<AddDocumentRoute> { route ->
        AddDocumentScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.config) })
        )
    }

    entry<DocumentOfferRoute> { route ->
        DocumentOfferScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.config) })
        )
    }

    entry<DocumentOfferCodeRoute> { route ->
        DocumentOfferCodeScreen(
            navigator = navigator,
            viewModel = koinViewModel(parameters = { parametersOf(route.config) })
        )
    }

    entry<DocumentIssuanceSuccessRoute> { route ->
        DocumentIssuanceSuccessScreen(
            navigator,
            koinViewModel(parameters = { parametersOf(route.config) })
        )
    }
}
