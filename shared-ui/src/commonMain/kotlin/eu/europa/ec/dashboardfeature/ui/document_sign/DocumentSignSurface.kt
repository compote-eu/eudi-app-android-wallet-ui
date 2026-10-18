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

import androidx.compose.runtime.Composable

/** What a signing attempt did, as far as the wallet can see. */
sealed interface DocumentSignOutcome {

    /**
     * The RQES SDK took over and owns the screen from here.
     *
     * Deliberately the end of the wallet's knowledge: the SDK runs its own UI for choosing a
     * signing service, authorizing and signing, and reports its own errors there. The wallet learns
     * nothing more about how it ended, which is why there is no Succeeded case to pattern-match on.
     */
    data object Started : DocumentSignOutcome

    /** The user backed out of the document picker without choosing anything. */
    data object Cancelled : DocumentSignOutcome

    /** Nothing was handed over. [reason] is already localized and fit to show. */
    data class Failed(val reason: String) : DocumentSignOutcome
}

/** Opens the platform's document picker and hands what it returns to the signing SDK. */
fun interface DocumentSignTrigger {
    fun selectAndSign()
}

/**
 * The whole platform half of document signing, and deliberately so.
 *
 * What differs between the platforms is only *choosing a PDF* and *handing it over*: Android has an
 * `ActivityResultContracts.OpenDocument` launcher and `EudiRQESUi.initiate(context, documentUri)`,
 * iOS has `UIDocumentPickerViewController` and `initiate(on: UIViewController, fileUrl:)`. Both are
 * scoped to composition — Android's launcher must be created in it, and iOS needs the hosting view
 * controller — which is why this is a composable returning a trigger rather than a suspend function
 * on a bridge interface.
 *
 * Everything around it is shared: the title, the subtitle, the select-document row, and the error
 * the screen shows if the handover fails.
 *
 * The two halves are one conversation, so they belong on one side of the seam: the picked document
 * is handed straight to the SDK without the shared screen ever naming a file, a URI or a context.
 *
 * Whether the platform can sign at all is a *separate* question, answered by
 * `HomeInteractor.canSignDocuments` — that one decides whether the entry point is offered, while
 * this one runs the flow once it has been.
 */
@Composable
expect fun rememberDocumentSignTrigger(
    onOutcome: (DocumentSignOutcome) -> Unit,
): DocumentSignTrigger
