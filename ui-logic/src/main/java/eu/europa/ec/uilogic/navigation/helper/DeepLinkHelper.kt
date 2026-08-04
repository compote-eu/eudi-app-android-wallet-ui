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

package eu.europa.ec.uilogic.navigation.helper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import eu.europa.ec.businesslogic.util.safeLet
import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.eudi.rqesui.infrastructure.EudiRQESUi
import eu.europa.ec.eudi.rqesui.infrastructure.RemoteUri
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.uilogic.extension.openUrl

fun hasDeepLink(deepLinkUri: Uri?): DeepLinkAction? {
    return safeLet(
        deepLinkUri,
        deepLinkUri?.scheme
    ) { uri, scheme ->
        DeepLinkAction(
            link = uri,
            type = DeepLinkType.parse(
                scheme = scheme,
                host = uri.host
            )
        )
    }
}

fun handleDeepLinkAction(
    navigator: AppNavigator,
    context: Context,
    uri: Uri,
    route: AppRoute? = null
) {
    hasDeepLink(uri)?.let { action ->
        handleDeepLinkAction(
            navigator = navigator,
            context = context,
            action = action,
            route = route
        )
    }
}

/**
 * Dispatch a deep link: either broadcast/hand it off to another component, or navigate to [route].
 *
 * Nav3 Stage 5: the navigating branches used to build a route *string* here — the screen came from
 * the deep-link type and the arguments arrived pre-Base64-encoded from the caller. The caller now
 * supplies the whole typed destination (it is the one that knows the config to put in it), so this
 * only decides *whether* the link navigates. The two remaining `Screen` lookups are therefore gone,
 * as is the `arguments: String?` parameter.
 *
 * A navigating type with no [route] is a no-op, matching the old behaviour: the route string would
 * have been the bare screen name with its required config argument missing.
 */
fun handleDeepLinkAction(
    navigator: AppNavigator,
    context: Context,
    action: DeepLinkAction,
    route: AppRoute? = null
) {
    when (action.type) {
        DeepLinkType.OPENID4VP, DeepLinkType.CREDENTIAL_OFFER -> {
            route?.let { navigator.navigate(it, popUpTo = it, popUpToInclusive = true) }
        }

        DeepLinkType.ISSUANCE -> {
            notify(
                context = context,
                action = CoreActions.VCI_RESUME_ACTION,
                bundle = bundleOf(Pair("uri", action.link.toString()))
            )
        }

        DeepLinkType.EXTERNAL -> {
            context.openUrl(action.link)
        }

        DeepLinkType.DYNAMIC_PRESENTATION -> {
            notify(
                context = context,
                action = CoreActions.VCI_DYNAMIC_PRESENTATION,
                bundle = bundleOf(Pair("uri", action.link.toString()))
            )
        }

        DeepLinkType.RQES -> {
            action.link.getQueryParameter("code")?.let { authorizationCode ->
                EudiRQESUi.resume(
                    context = context,
                    authorizationCode = authorizationCode
                )
            }
        }

        DeepLinkType.RQES_DOC_RETRIEVAL -> {
            EudiRQESUi.initiate(
                context = context,
                remoteUri = RemoteUri(action.link)
            )
        }
    }
}

private fun notify(
    context: Context,
    action: String,
    bundle: Bundle? = null
) {
    Intent().also { intent ->
        intent.action = action
        intent.setPackage(context.packageName)
        bundle?.let { intent.putExtras(it) }
        context.sendBroadcast(intent)
    }
}
