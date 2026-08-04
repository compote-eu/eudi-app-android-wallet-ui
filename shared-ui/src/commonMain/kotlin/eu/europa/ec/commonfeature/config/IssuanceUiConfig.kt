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

// Nav3 Stage 3: moved to :shared-ui commonMain (package unchanged) as the payload of
// AddDocumentRoute. `FormatType` is `typealias FormatType = String` in the Android-only :core-logic,
// so the field is declared as `String` here; call sites passing a `FormatType` still compile.
package eu.europa.ec.commonfeature.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface IssuanceFlowType {
    @Serializable
    @SerialName("NoDocument")
    data object NoDocument : IssuanceFlowType

    @Serializable
    @SerialName("ExtraDocument")
    data class ExtraDocument(val formatType: String?) : IssuanceFlowType
}

@Serializable
data class IssuanceUiConfig(
    val flowType: IssuanceFlowType,
)