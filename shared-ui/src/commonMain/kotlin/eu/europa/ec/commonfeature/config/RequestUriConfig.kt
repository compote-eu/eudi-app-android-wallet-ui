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

// Nav3 Stage 2/3: moved to :shared-ui commonMain (package unchanged) as the payload of
// PresentationRequestRoute / ProximityQrRoute. `initiatorRoute` is now a typed [AppRoute] instead
// of a legacy route string. The `toDomainConfig` mapper stays Android-side (:common-feature): it
// touches core-logic's PresentationControllerConfig and the Intent-carrying IntentAction.
package eu.europa.ec.commonfeature.config

import eu.europa.ec.shared.navigation.AppRoute
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface PresentationMode {
    val scopeId: String

    /** The screen that started this presentation — where the flow returns when it completes. */
    val initiatorRoute: AppRoute

    @Serializable
    @SerialName("OpenId4Vp")
    data class OpenId4Vp(
        val uri: String,
        override val initiatorRoute: AppRoute,
    ) : PresentationMode {

        override val scopeId: String
            get() = "vp_presentation_scope_id"
    }

    @Serializable
    @SerialName("Ble")
    data class Ble(
        override val initiatorRoute: AppRoute,
    ) : PresentationMode {

        override val scopeId: String
            get() = "ble_presentation_scope_id"
    }

    @Serializable
    @SerialName("DcApi")
    data class DcApi(
        override val initiatorRoute: AppRoute,
    ) : PresentationMode {

        override val scopeId: String
            get() = "dc_api_presentation_scope_id"
    }
}

@Serializable
data class RequestUriConfig(
    val mode: PresentationMode
) {

    val presentationScopeId: String = mode.scopeId
}
