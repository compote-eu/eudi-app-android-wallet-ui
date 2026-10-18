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
// IntentAction, so it stays here.
//
// Stage 5: `initiatorRoute` crosses into :core-logic, which must not depend on :shared-ui, so the
// destination is encoded to an opaque token here and decoded back to an `AppRoute` when the domain
// hands it to the UI again (PresentationSuccessInteractor -> PresentationSuccessViewModel). core
// never reads the value. See AppRouteCodec.
package eu.europa.ec.commonfeature.config

import eu.europa.ec.corelogic.controller.PresentationControllerConfig
import eu.europa.ec.shared.navigation.AppRouteCodec
import eu.europa.ec.uilogic.navigation.helper.IntentAction
import eu.europa.ec.uilogic.navigation.helper.IntentType

fun RequestUriConfig.toDomainConfig(intentAction: IntentAction?): PresentationControllerConfig {
    val presentationMode = mode
    val initiator = AppRouteCodec.encode(presentationMode.initiatorRoute)
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
