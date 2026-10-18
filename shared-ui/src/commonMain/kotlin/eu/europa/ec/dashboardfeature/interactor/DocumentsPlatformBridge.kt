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

package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.businesslogic.extension.ioDispatcher
import eu.europa.ec.corelogic.model.DocumentCategories
import eu.europa.ec.corelogic.model.FormatType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

/**
 * The small platform-specific remainder of `DocumentsInteractor`, so the interactor itself — the
 * document-list mapping and the whole filter definition, which is the bulk and the part that must not
 * diverge between platforms — can live in commonMain.
 *
 * Every member here returns a **commonMain** type, deliberately: that keeps each platform's mapping on
 * its own side of the boundary. Android's deferred-issuance and deletion flows speak wallet-core states
 * and translate them to the interactor's partial states before they cross; the shared interactor never
 * sees a wallet-core type.
 *
 * Implemented in `:dashboard-feature` over wallet-core, and in `:shared-ui`'s iosMain over multipaz.
 */
interface DocumentsPlatformBridge {

    /** The wallet's configured document categories — static configuration, not a query. */
    val documentCategories: DocumentCategories

    /** BCP-47 tag for the user's locale, used to pick issuer display names. */
    fun localeTag(): String

    /** Whether to show the `n/m` credential counter on each document — a user preference. */
    suspend fun showBatchIssuanceCounter(): Boolean

    /**
     * Deletes [documentId], reporting whether this emptied the wallet — which the caller treats
     * differently, since an empty wallet returns the user to activation.
     */
    fun deleteDocument(documentId: String): Flow<DocumentInteractorDeleteDocumentPartialState>

    /**
     * Retries issuance for documents whose issuer deferred them.
     *
     * Android-only in substance: iOS has no issuance path yet, so its implementation reports an empty
     * result rather than pretending to retry.
     */
    fun tryIssuingDeferredDocuments(
        deferredDocuments: Map<String, FormatType>,
        dispatcher: CoroutineDispatcher = ioDispatcher,
    ): Flow<DocumentInteractorRetryIssuingDeferredDocumentsPartialState>
}
