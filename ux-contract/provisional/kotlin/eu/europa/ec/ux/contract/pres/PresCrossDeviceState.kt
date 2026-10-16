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

enum class PresCrossDeviceState(val id: String, val isFinal: Boolean = false) {
    SCAN("scan"),
    CONNECTING("connecting"),
    REQUEST("request"),
    REQUEST_OPTIONAL_ONLY("request-optional-only"),
    REQUEST_NO_SELECTION("request-no-selection"),
    REQUEST_OPTIONAL_SELECTED("request-optional-selected"),
    REQUEST_UNTRUSTED("request-untrusted"),
    UNTRUSTED_CONFIRM("untrusted-confirm"),
    AUTH("auth"),
    AUTH_NOT_ENROLLED("auth-not-enrolled"),
    AUTH_FAILED("auth-failed"),
    VERIFICATION_CODE("verification-code"),
    SENDING("sending"),
    SUCCESS("success"),
    CANCELLED("cancelled"),
    DOCUMENT_MISSING("document-missing"),
    DOCUMENT_INVALID("document-invalid"),
    ERROR("error"),
    ERROR_EXPIRED("error-expired"),
    ERROR_INVALID_QR("error-invalid-qr"),
    DONE("done", isFinal = true),
    CLOSED("closed", isFinal = true),
    ADD_DOCUMENT("add-document", isFinal = true),
    DEVICE_SETTINGS("device-settings", isFinal = true),
    TO_HELP("to-help", isFinal = true);

    companion object {
        fun of(id: String): PresCrossDeviceState? = entries.firstOrNull { it.id == id }
    }
}

/** The ui_states of SCR-pres-request that this flow reaches (the `sd-*` ones belong to FLW-pres-same-device). */
enum class PresRequestUiState(val id: String) {
    DEFAULT("default"),
    UNTRUSTED("untrusted"),
    OPTIONAL_ONLY("optional-only"),
    NO_SELECTION("no-selection"),
    OPTIONAL_SELECTED("optional-selected")
}

/** Consent snapshot of SCR-pres-request: `/verifier` and `/request` of the flow context. */
@Serializable
data class PresRequestData(
    val verifier: Verifier,
    val request: RequestData
)

@Serializable
data class Verifier(
    val name: String,
    @SerialName("service_name")
    val serviceName: String,
    val reason: String,
    val trust: Trust,
    @SerialName("privacy_url")
    val privacyUrl: String? = null
)

@Serializable
enum class Trust {
    @SerialName("trusted")
    TRUSTED,

    @SerialName("untrusted")
    UNTRUSTED
}

@Serializable
data class RequestData(
    @SerialName("document_name")
    val documentName: String,
    val attributes: List<RequestedAttribute>,
    @SerialName("selected_count")
    val selectedCount: Int = 0,
    val attempted: Boolean = false,
    val channel: String = "cross-device"
)

@Serializable
data class RequestedAttribute(
    val id: String,
    @SerialName("label_copy")
    val labelCopy: String,
    val value: String? = null,
    val requirement: Requirement,
    val selected: Boolean = false
)

@Serializable
enum class Requirement {
    @SerialName("mandatory")
    MANDATORY,

    @SerialName("optional")
    OPTIONAL
}
