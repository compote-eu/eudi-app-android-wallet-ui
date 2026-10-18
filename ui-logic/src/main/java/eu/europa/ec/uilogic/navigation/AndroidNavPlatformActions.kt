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

package eu.europa.ec.uilogic.navigation

import android.app.Activity
import android.content.Context
import androidx.core.net.toUri
import eu.europa.ec.businesslogic.extension.getParcelableArrayListExtra
import eu.europa.ec.corelogic.model.RevokedDocumentDataDomain
import eu.europa.ec.corelogic.model.RevokedDocumentParcel
import eu.europa.ec.corelogic.model.toDomain
import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.dashboardfeature.ui.dashboard.PendingLaunchIntent
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.NavPlatformActions
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.uilogic.extension.cacheUri
import eu.europa.ec.uilogic.extension.consumePendingIntentAction
import eu.europa.ec.uilogic.extension.findActivity
import eu.europa.ec.uilogic.extension.getPendingIntent
import eu.europa.ec.uilogic.extension.getPendingUri
import eu.europa.ec.uilogic.navigation.helper.IntentAction
import eu.europa.ec.uilogic.navigation.helper.handleDeepLinkAction
import eu.europa.ec.uilogic.navigation.helper.handleIntentAction
import eu.europa.ec.uilogic.navigation.helper.hasIntentAction
import eu.europa.ec.uilogic.navigation.helper.navigateReplacingCurrent
import eu.europa.ec.uilogic.navigation.helper.popBackStackTo

/**
 * Android's answers for the shared `entry<Route> { }` blocks.
 *
 * This lives in `:ui-logic` rather than in `:shared-ui/androidMain` because every helper it calls
 * does: the one-shot intent slots on `EudiComponentActivity`, the deep-link and intent-action
 * helpers, and — through `:core-logic` — the revocation broadcast's `Parcelable` extra. `:ui-logic`
 * depends on `:shared-ui`, so this is the near side of that dependency, which is exactly why
 * [NavPlatformActions] is an interface declared over there and implemented here.
 *
 * Each member is the body the corresponding Android `entry<Route> { }` used to inline, unchanged.
 *
 * @param context the composition's context, which must be the activity: the intent slots and
 *   [findActivity] both walk up from it. Read from `LocalContext` where this is provided.
 */
class AndroidNavPlatformActions(
    private val context: Context,
) : NavPlatformActions {

    override fun pendingDeepLink(): String? = context.getPendingUri()?.toString()

    override fun pendingLaunchIntent(): PendingLaunchIntent {
        // `getPendingIntent()` is one-shot, so it is read once and both readings of it are returned
        // together: the link it carries and, failing that, the app action it is.
        val pendingIntent = context.getPendingIntent()
        return PendingLaunchIntent(
            deepLink = pendingIntent?.data?.toString(),
            intentAction = hasIntentAction(pendingIntent),
        )
    }

    override fun consumePendingIntentAction(): IntentAction? = context.consumePendingIntentAction()

    override fun revokedDocumentsFromBroadcast(
        intent: PlatformIntent,
    ): List<RevokedDocumentDataDomain>? =
        intent.getParcelableArrayListExtra<RevokedDocumentParcel>(
            action = CoreActions.REVOCATION_IDS_EXTRA
        )?.map { it.toDomain() }

    override fun finishWithResult(intent: PlatformIntent) {
        context.findActivity().let { activity ->
            // The result code is a platform constant, so it lives here rather than in the shared
            // view-model.
            activity.setResult(Activity.RESULT_OK, intent)
            activity.finish()
        }
    }

    override fun parkAndReturn(
        navigator: AppNavigator,
        link: String,
        routeToPop: AppRoute?,
        isPreAuthorization: Boolean,
    ): Boolean {
        val destination = routeToPop ?: return false
        context.cacheUri(link.toUri())
        if (isPreAuthorization) {
            // A pre-authorization hand-off must not leave the screen it came from on the stack.
            navigator.navigateReplacingCurrent(destination)
        } else {
            navigator.popBackStackTo(route = destination, inclusive = false)
        }
        return true
    }

    override fun openDeepLink(navigator: AppNavigator, link: String, route: AppRoute?) {
        handleDeepLinkAction(
            navigator = navigator,
            context = context,
            uri = link.toUri(),
            route = route,
        )
    }

    override fun openIntentAction(
        navigator: AppNavigator,
        action: IntentAction,
        route: AppRoute?,
    ) {
        handleIntentAction(
            navigator = navigator,
            context = context,
            action = action,
            route = route,
        )
    }
}
