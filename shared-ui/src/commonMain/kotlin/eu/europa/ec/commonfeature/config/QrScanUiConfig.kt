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

// Nav3 Stage 3: moved to :shared-ui commonMain (package unchanged) as the payload of QrScanRoute.
package eu.europa.ec.commonfeature.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface QrScanFlow {
    @Serializable
    @SerialName("Presentation")
    data object Presentation : QrScanFlow

    @Serializable
    @SerialName("Issuance")
    data class Issuance(val issuanceFlowType: IssuanceFlowType) : QrScanFlow

    @Serializable
    @SerialName("Signature")
    data object Signature : QrScanFlow
}

/**
 * Which flow opened the scanner. That is the *whole* payload: the screen's title and subtitle were
 * previously carried here as resolved strings, but every one of the four call sites derived them
 * from [qrScanFlow] and nothing else — the same relationship `QrScanViewModel.informativeText`
 * already expressed as a `when`. Deriving them at the destination removes both the duplication and
 * three of this config's callers' reasons to resolve strings at all.
 */
@Serializable
data class QrScanUiConfig(
    val qrScanFlow: QrScanFlow
)