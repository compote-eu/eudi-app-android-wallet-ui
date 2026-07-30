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

// Nav3 Stage 2/3: the Android half of RequestUriConfig, split off when the config itself moved to
// :shared-ui commonMain. It maps to core-logic's domain config and needs the Intent-carrying
// IntentAction, so it stays here. Note the AppRoute -> legacy-route-string narrowing on
// `initiatorRoute`: core-logic treats it as an opaque token (it is never read inside core, only
// handed back to the UI via PresentationSuccessInteractor), so the bridge encoding is enough until
// the Nav3 host lands in Stage 5.
package eu.europa.ec.commonfeature.config

import eu.europa.ec.corelogic.controller.PresentationControllerConfig
import eu.europa.ec.uilogic.navigation.helper.IntentAction
import eu.europa.ec.uilogic.navigation.helper.IntentType
import eu.europa.ec.uilogic.navigation.helper.toLegacyRoute

fun RequestUriConfig.toDomainConfig(intentAction: IntentAction?): PresentationControllerConfig {
    val presentationMode = mode
    val initiator = presentationMode.initiatorRoute.toLegacyRoute()
    return when (presentationMode) {
        is PresentationMode.Ble -> PresentationControllerConfig.Ble(initiator)
        is PresentationMode.OpenId4Vp -> PresentationControllerConfig.OpenId4VP(
            presentationMode.uri,
            initiator
        )

        is PresentationMode.DcApi -> {
            intentAction?.let { safeIntentAction ->
                when (safeIntentAction.type) {
                    IntentType.DC_API -> PresentationControllerConfig.DcApi(
                        initiator = initiator,
                        startIntent = safeIntentAction.intent
                    )
                }
            } ?: throw IllegalStateException("Cannot create DcApi config without intentAction")
        }
    }
}
