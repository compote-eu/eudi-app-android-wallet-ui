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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.corelogic.model.DocumentCategories
import eu.europa.ec.corelogic.model.DocumentCategory
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.dashboardfeature.interactor.DocumentInteractorDeleteDocumentPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentInteractorRetryIssuingDeferredDocumentsPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentsPlatformBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * iOS's [DocumentsPlatformBridge]. Deliberately thin — the document-list mapping and the filter
 * definitions are shared, so this supplies only what genuinely differs.
 *
 * @param deleteDocument how to delete, injected so this file does not name a multipaz type; the
 * implementation lives with the engine in :shared-logic.
 */
internal class IosDocumentsPlatformBridge(
    private val deleteDocument: suspend (documentId: String) -> Result<Unit>,
    private val hasAnyDocument: suspend () -> Boolean,
) : DocumentsPlatformBridge {

    /**
     * The same category-to-format mapping the Android flavours configure, expressed here because
     * `WalletCoreConfig` is Android-only. Kept to the two PID formats plus a catch-all: iOS can hold
     * nothing else yet, since it cannot issue.
     */
    override val documentCategories: DocumentCategories
        get() = DocumentCategories(
            value = mapOf(
                DocumentCategory.Government to listOf(
                    DocumentIdentifier.MdocPid,
                    DocumentIdentifier.SdJwtPid,
                ),
            )
        )

    /**
     * False, unlike the Android flavours' `true`: forcing PID activation would send the user into an
     * issuance flow that does not exist on iOS yet.
     */
    override val forcePidActivation: Boolean get() = false

    /** The device locale's language, which is all the issuer-display lookup matches on. */
    override fun localeTag(): String =
        NSLocale.currentLocale.languageCode

    /**
     * The user's own choice, from the same store the settings screen's switch writes to — so flipping
     * that switch changes what these rows show, as on Android. Defaults to true, as Android's
     * preference does.
     */
    override suspend fun showBatchIssuanceCounter(): Boolean =
        IosPreferences.showBatchIssuanceCounter()

    override fun deleteDocument(
        documentId: String,
    ): Flow<DocumentInteractorDeleteDocumentPartialState> = flow {
        deleteDocument.invoke(documentId).fold(
            onSuccess = {
                emit(
                    if (forcePidActivation && !hasAnyDocument()) {
                        DocumentInteractorDeleteDocumentPartialState.AllDocumentsDeleted
                    } else {
                        DocumentInteractorDeleteDocumentPartialState.SingleDocumentDeleted
                    }
                )
            },
            onFailure = { throwable ->
                emit(
                    DocumentInteractorDeleteDocumentPartialState.Failure(
                        errorMessage = throwable.message.orEmpty()
                    )
                )
            },
        )
    }

    /**
     * Reports nothing issued, rather than attempting a retry: deferred issuance is part of OpenID4VCI,
     * which has no iOS implementation yet. An empty result is the same shape the Android side produces
     * when no deferred document is ready, so the caller needs no special case.
     */
    override fun tryIssuingDeferredDocuments(
        deferredDocuments: Map<String, FormatType>,
        dispatcher: CoroutineDispatcher,
    ): Flow<DocumentInteractorRetryIssuingDeferredDocumentsPartialState> = flow {
        emit(
            DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result(
                successfullyIssuedDeferredDocuments = emptyList(),
                failedIssuedDeferredDocuments = emptyList(),
            )
        )
    }
}
