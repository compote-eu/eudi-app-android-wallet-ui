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

package eu.europa.ec.issuancefeature.ui.success

import androidx.compose.runtime.Composable
import eu.europa.ec.commonfeature.ui.document_success.DocumentSuccessScreen
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.platform.PlatformIntent

/**
 * The screen the issuance flow ends on. Nothing but naming: the behaviour is
 * [DocumentSuccessScreen]'s, and so are the two host lambdas it forwards — see their documentation there.
 */
@Composable
fun DocumentIssuanceSuccessScreen(
    navigator: AppNavigator,
    viewModel: DocumentIssuanceSuccessViewModel,
    onExternalDeepLink: (link: String, routeToPop: AppRoute?) -> Unit = { _, _ -> navigator.pop() },
    onFinishWithResult: (PlatformIntent) -> Unit = {},
) {
    DocumentSuccessScreen(
        navigator = navigator,
        viewModel = viewModel,
        onExternalDeepLink = onExternalDeepLink,
        onFinishWithResult = onFinishWithResult,
    )
}