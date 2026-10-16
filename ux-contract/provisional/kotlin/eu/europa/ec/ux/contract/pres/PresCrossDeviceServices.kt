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

// PROVISIONAL (M1b): replaced by ux-spec/ at M6; do not extend. Hand copy of spike-contract-boundary.md §2.
package eu.europa.ec.ux.contract.pres

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** App logic behind FLW-pres-cross-device. The runtime calls nothing else (product/services/SVC-pres-*.yaml). */
interface PresCrossDeviceServices {
    suspend fun fetchRequest(uri: String): FetchRequestResult
    suspend fun authenticate(): AuthenticateResult
    suspend fun verificationCode(): VerificationCodeResult
    suspend fun send(selection: List<String>): SendResult
    suspend fun cancel(): CancelResult
}

@Serializable
data class RequestPayload(
    @SerialName("document_name")
    val documentName: String,
    val attributes: List<RequestedAttribute>
)

@Serializable
sealed interface FetchRequestResult {
    @Serializable
    @SerialName("RequestReady")
    data class RequestReady(val verifier: Verifier, val request: RequestPayload) : FetchRequestResult

    @Serializable
    @SerialName("OptionalOnly")
    data class OptionalOnly(val verifier: Verifier, val request: RequestPayload) : FetchRequestResult

    @Serializable
    @SerialName("RpUntrusted")
    data class RpUntrusted(val verifier: Verifier, val request: RequestPayload) : FetchRequestResult

    @Serializable
    @SerialName("DocMissing")
    data class DocMissing(
        @SerialName("document_name")
        val documentName: String
    ) : FetchRequestResult

    @Serializable
    @SerialName("DocInvalid")
    data class DocInvalid(
        @SerialName("document_name")
        val documentName: String
    ) : FetchRequestResult

    @Serializable
    @SerialName("Expired")
    data object Expired : FetchRequestResult

    @Serializable
    @SerialName("Error")
    data class Error(val reason: String? = null) : FetchRequestResult
}

@Serializable
sealed interface AuthenticateResult {
    @Serializable
    @SerialName("Authenticated")
    data object Authenticated : AuthenticateResult

    @Serializable
    @SerialName("Failed")
    data object Failed : AuthenticateResult

    @Serializable
    @SerialName("NotEnrolled")
    data object NotEnrolled : AuthenticateResult

    @Serializable
    @SerialName("Expired")
    data object Expired : AuthenticateResult

    @Serializable
    @SerialName("Error")
    data class Error(val reason: String? = null) : AuthenticateResult
}

@Serializable
sealed interface VerificationCodeResult {
    @Serializable
    @SerialName("Ready")
    data class Ready(
        val code: String,
        @SerialName("portal_url")
        val portalUrl: String? = null
    ) : VerificationCodeResult

    @Serializable
    @SerialName("Expired")
    data object Expired : VerificationCodeResult

    @Serializable
    @SerialName("Error")
    data class Error(val reason: String? = null) : VerificationCodeResult
}

@Serializable
sealed interface SendResult {
    @Serializable
    @SerialName("Sent")
    data object Sent : SendResult

    @Serializable
    @SerialName("Expired")
    data object Expired : SendResult

    @Serializable
    @SerialName("Error")
    data class Error(val reason: String? = null) : SendResult
}

@Serializable
sealed interface CancelResult {
    @Serializable
    @SerialName("Cancelled")
    data object Cancelled : CancelResult

    @Serializable
    @SerialName("NothingToCancel")
    data object NothingToCancel : CancelResult

    @Serializable
    @SerialName("Error")
    data class Error(val reason: String? = null) : CancelResult
}
