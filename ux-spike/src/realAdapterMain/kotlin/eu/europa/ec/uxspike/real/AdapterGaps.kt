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

package eu.europa.ec.uxspike.real

/**
 * M11b: what the real interactors cannot give the generated contract. [UNDECLARED] is exactly what
 * `PresCrossDeviceServiceResults.undeclared(adapter)` must report (the conformance test pins it); the reasons below
 * are the `Error.reason` values the adapter uses where the app has a state the contract has no result for.
 * The full table (generated result, real source, status, what the app team would change) is
 * `spike-evidence/M11b/adapter-report.md`.
 */
object AdapterGaps {
    /** Per service, the contract results with no real source (impossible, never faked). */
    val UNDECLARED: Map<String, List<String>> = mapOf(
        // G-EXP: every expiry reaches the app as an untyped Failure(String) or a biometric failure.
        "authenticate" to listOf("Expired"),
        // G-DOC, G-EXP: revoked documents are folded into NoData on Android; no expiry type.
        "fetchRequest" to listOf("DocInvalid", "Expired"),
        "send" to listOf("Expired"),
        // G-VC: the app has no verification-code step at all.
        "verificationCode" to listOf("Ready", "Expired"),
    )

    const val NO_VERIFICATION_CODE_SOURCE = "adapter: the app has no verification-code source"
    const val NOTHING_TO_SEND = "adapter: no fetched request to answer"
    const val NO_REQUEST_EVENT = "adapter: the request stream ended with no event"
    const val NO_RESPONSE_EVENT = "adapter: the response stream ended with no terminal event"
    const val VERIFIER_NOT_TRUSTED = "adapter: verifier not trusted (the app blocks; no request to show)"
    const val DISCONNECTED = "adapter: the exchange ended before a request arrived"
    const val PIN_ONLY_USER = "adapter: the user chose PIN; the contract has no PIN path"
    const val DEVICE_AUTH_FAILED = "adapter: the per-document key authentication failed or had no host"
    const val REJECTED_WHILE_SENDING = "adapter: the verifier reported a rejection while sending"
    const val DC_API_INTENT = "adapter: a DC-API intent hand-off; not this flow"
}
