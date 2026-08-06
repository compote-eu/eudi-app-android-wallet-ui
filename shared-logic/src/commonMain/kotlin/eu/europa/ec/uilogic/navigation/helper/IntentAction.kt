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

// Moved to commonMain on the `PlatformIntent` handle. The intent is *carried, never inspected* here —
// only `type` is read by shared code (RequestViewModel) — which is exactly the case the handle exists
// for; the one place that needs the real Intent is :common-feature's RequestUriConfigMapper, which
// stays Android-side.
//
// `@Parcelize`/`Parcelable` was dropped rather than abstracted: nothing parcels an IntentAction any
// more. It is never put in or read from a Bundle, and INTENT_ACTION_KEY below has no reader at all —
// both are leftovers of the pre-Nav3 navigation, which passed the action through a bundle argument.
// Our own `b49a7d84` (Nav3 Stage 5) replaced that with the activity-scoped one-shot slot in
// `EudiComponentActivity`, which holds a plain field. The key is upstream's (from the DC API commit
// `0348e3d9`) so it is left in place rather than deleted.
package eu.europa.ec.uilogic.navigation.helper

import eu.europa.ec.shared.platform.PlatformIntent

const val INTENT_ACTION_KEY = "intent_action"

data class IntentAction(
    val intent: PlatformIntent,
    val type: IntentType
)

enum class IntentType(val associatedActions: List<String>) {
    DC_API(
        associatedActions = listOf(
            "androidx.identitycredentials.action.get_credentials",
            "androidx.credentials.registry.provider.action.get_credential"
        )
    ),
}

