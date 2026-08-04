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
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.uilogic.extension.cacheIntentAction

fun hasIntentAction(intent: Intent?): IntentAction? {
    return intent?.toIntentAction()
}

/**
 * Hand [action] to [route] and go there.
 *
 * Nav3 Stage 5: as with `handleDeepLinkAction`, the caller supplies the typed destination instead of
 * this helper resolving a `Screen` and appending a Base64 argument. The action itself is parked in
 * the activity-scoped one-shot slot (see `Context.cacheIntentAction`) rather than in the departing
 * back-stack entry's `savedStateHandle`, which Nav3 does not have.
 */
fun handleIntentAction(
    navigator: AppNavigator,
    context: Context,
    action: IntentAction,
    route: AppRoute?
) {
    val destination = route ?: return
    when (action.type) {
        IntentType.DC_API -> {
            context.cacheIntentAction(action)
            navigator.navigate(destination, popUpTo = destination, popUpToInclusive = true)
        }
    }
}

private fun Intent.toIntentAction(): IntentAction? {
    return IntentType
        .entries
        .firstOrNull {
            it.associatedActions.contains(this.action?.lowercase())
        }?.let { matchedType ->
            IntentAction(intent = this, type = matchedType)
        }
}
