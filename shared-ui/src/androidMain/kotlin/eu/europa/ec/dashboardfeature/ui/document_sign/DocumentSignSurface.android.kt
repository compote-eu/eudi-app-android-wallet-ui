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

package eu.europa.ec.dashboardfeature.ui.document_sign

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import eu.europa.ec.eudi.rqesui.infrastructure.DocumentUri
import eu.europa.ec.eudi.rqesui.infrastructure.EudiRQESUi

/** The PDF-only filter the picker applies; the SDK signs nothing else. */
private const val PDF_MIME_TYPE = "application/pdf"

/**
 * Android's half: the system document picker, then `EudiRQESUi`.
 *
 * The SDK is initialised once in `Application.onCreate` (`initializeRqes`), so there is nothing to
 * configure here — `initiate` opens the SDK's own activity and owns the flow from that point.
 */
@Composable
actual fun rememberDocumentSignTrigger(
    onOutcome: (DocumentSignOutcome) -> Unit,
): DocumentSignTrigger {
    val context = LocalContext.current
    // The launcher outlives individual recompositions while [onOutcome] may not, so read the latest
    // one at call time rather than capturing the instance the launcher was created with.
    val currentOnOutcome by rememberUpdatedState(onOutcome)

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // A null uri is the user dismissing the picker — not a failure, and nothing to report but
        // that the flow stopped.
        if (uri == null) {
            currentOnOutcome(DocumentSignOutcome.Cancelled)
            return@rememberLauncherForActivityResult
        }
        runCatching {
            EudiRQESUi.initiate(context = context, documentUri = DocumentUri(uri))
        }.onSuccess {
            currentOnOutcome(DocumentSignOutcome.Started)
        }.onFailure { throwable ->
            currentOnOutcome(
                DocumentSignOutcome.Failed(
                    reason = throwable.message.orEmpty(),
                )
            )
        }
    }

    return remember(picker) {
        DocumentSignTrigger { picker.launch(arrayOf(PDF_MIME_TYPE)) }
    }
}
