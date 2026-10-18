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

package eu.europa.ec.uxspike.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitViewController
import androidx.compose.ui.platform.testTag
import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentDisclosure
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentFormat
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentRequest
import eu.europa.ec.uxspike.UxSpikeLog
import kotlinx.cinterop.ExperimentalForeignApi

/** Test tag of the line the harness shows once the hosted sub-flow reported its decision. */
const val UX_DCAPI_HARNESS_RESULT_TAG = "ux-dcapi-harness-result"

/**
 * The isolated host harness for track C (`--ux-spike dcapi-harness`): embeds the same view controller the extension
 * hosts, with a scripted PID request, inside the app. It shows that the controller renders and completes; it is
 * **not** a D10 pass, because the extension process, ExtensionKit's scene and the real DC API delivery are not involved.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
fun UxDcApiHarness() {
    var result by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        UIKitViewController(
            factory = {
                UxDcApiSubflowViewController(scriptedRequest()) { disclosures: List<IosPresentmentDisclosure>? ->
                    UxSpikeLog.log("dcapi-harness decision: ${disclosures?.size ?: "refused"}")
                    result = "dcapi-harness: completed, decision=${disclosures?.size ?: "refused"}"
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        result?.let {
            BasicText(
                it,
                Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().testTag(UX_DCAPI_HARNESS_RESULT_TAG),
            )
        }
    }
}

/** A one-document PID request for family and given name, as a trusted verifier would send (ISO 18013-5, mdoc). */
private fun scriptedRequest(): IosPresentmentRequest {
    fun claim(name: String, value: String) = IosPresentmentRequest.RequestedClaimInfo(
        claim = ClaimPathDomain.ofPlainKeys(listOf(name), ClaimType.MsoMdoc(namespace = PID_NAMESPACE)),
        displayName = name,
        value = value,
        intentToRetain = false,
    )
    return IosPresentmentRequest(
        requesterName = "Harness verifier",
        requesterIsTrusted = true,
        combinations = listOf(
            IosPresentmentRequest.Combination(
                documents = listOf(
                    IosPresentmentRequest.RequestedDocument(
                        documentId = "harness-doc",
                        credentialId = "harness-credential",
                        documentName = "PID",
                        docType = PID_DOC_TYPE,
                        format = IosPresentmentFormat.MsoMdoc,
                        queryId = null,
                        claims = listOf(claim("family_name", "Novak"), claim("given_name", "Jana")),
                    ),
                ),
            ),
        ),
    )
}

private const val PID_DOC_TYPE = "eu.europa.ec.eudi.pid.1"
private const val PID_NAMESPACE = "eu.europa.ec.eudi.pid.1"
