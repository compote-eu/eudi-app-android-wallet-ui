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

package eu.europa.ec.corelogic.wallet

import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.shared.wallet.WalletEngine

/**
 * Android [WalletEngine] implementation. It delegates to the existing wallet-core controller,
 * so the seam can be adopted incrementally without touching wallet-core wiring. As more
 * capabilities move behind [WalletEngine], consumers stop depending on the controller directly.
 */
class WalletEngineImpl(
    private val documentsController: WalletCoreDocumentsController,
) : WalletEngine {

    override suspend fun getRevokedDocumentIds(): List<String> =
        documentsController.getRevokedDocumentIds()

    override suspend fun isDocumentRevoked(documentId: String): Boolean =
        documentsController.isDocumentRevoked(documentId)

    override suspend fun isDocumentBookmarked(documentId: String): Boolean =
        documentsController.isDocumentBookmarked(documentId)

    override suspend fun storeBookmark(bookmarkId: String) =
        documentsController.storeBookmark(bookmarkId)

    override suspend fun deleteBookmark(bookmarkId: String) =
        documentsController.deleteBookmark(bookmarkId)
}
