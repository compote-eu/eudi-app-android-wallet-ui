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

package eu.europa.ec.commonfeature.ui.document_success

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.uilogic.extension.cacheUri
import eu.europa.ec.uilogic.extension.findActivity
import eu.europa.ec.uilogic.navigation.helper.popBackStackTo

/**
 * The Android answers to [DocumentSuccessScreen]'s injected host lambdas.
 *
 * Kept in one place because three flows end on that screen — issuance, presentation and proximity — and
 * all three need the same two Android-only behaviours. They are plain functions rather than a wrapper
 * composable so a caller that composes the screen itself (the issuance entry, which goes through
 * `DocumentIssuanceSuccessScreen`) uses the same code as [DocumentSuccessScreenHost].
 */
fun documentSuccessDeepLinkHandler(
    context: Context,
    navigator: AppNavigator,
): (link: String, routeToPop: AppRoute?) -> Unit = { link, routeToPop ->
    context.cacheUri(link.toUri())
    routeToPop?.let {
        navigator.popBackStackTo(route = it, inclusive = false)
    } ?: navigator.pop()
}

/** Finishes the activity with a result — the DC API hand-off, where the caller app awaits `setResult`. */
fun documentSuccessResultFinisher(context: Context): (PlatformIntent) -> Unit = { intent ->
    context.findActivity().let { activity ->
        // The result code is a platform constant, so it lives here rather than in the shared view-model.
        activity.setResult(Activity.RESULT_OK, intent)
        activity.finish()
    }
}

/** [DocumentSuccessScreen] with the Android host behaviour supplied, for flows with no shared wrapper. */
@Composable
fun DocumentSuccessScreenHost(
    navigator: AppNavigator,
    viewModel: DocumentSuccessViewModel,
) {
    val context = LocalContext.current

    DocumentSuccessScreen(
        navigator = navigator,
        viewModel = viewModel,
        onExternalDeepLink = documentSuccessDeepLinkHandler(context, navigator),
        onFinishWithResult = documentSuccessResultFinisher(context),
    )
}
