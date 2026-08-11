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

package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.WalletDocument
import eu.europa.ec.shared.wallet.WalletEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The iOS [WalletEngine] as something a DI container can construct: **not suspending to build**.
 *
 * Opening the document store is suspending — it creates the Secure Enclave secure area and touches
 * SQLite — but a Koin definition cannot be. So this is a synchronously-constructible facade that opens
 * the store on first use, under a [Mutex] so concurrent first calls open it exactly once, and forwards
 * every member to the real engine.
 *
 * A deliberate consequence: constructing this is free, so it costs nothing to register eagerly, and
 * the first document read is where any storage error surfaces.
 */
class IosWalletEngine : WalletEngine {

    private val mutex = Mutex()
    private var delegate: WalletEngine? = null

    private suspend fun delegate(): WalletEngine =
        delegate ?: mutex.withLock {
            // Re-check inside the lock: another caller may have opened it while we waited.
            delegate ?: MultipazWalletEngine(MultipazWalletStore.open()).also { delegate = it }
        }

    override suspend fun getAllDocuments(): List<WalletDocument> =
        delegate().getAllDocuments()

    override suspend fun getAllDocumentsWithDetails(locale: String): List<WalletDocument> =
        delegate().getAllDocumentsWithDetails(locale)

    override suspend fun getMainPidDocument(): WalletDocument? =
        delegate().getMainPidDocument()

    override suspend fun isDocumentBookmarked(documentId: String): Boolean =
        delegate().isDocumentBookmarked(documentId)

    override suspend fun storeBookmark(bookmarkId: String) =
        delegate().storeBookmark(bookmarkId)

    override suspend fun deleteBookmark(bookmarkId: String) =
        delegate().deleteBookmark(bookmarkId)

    override suspend fun getRevokedDocumentIds(): List<String> =
        delegate().getRevokedDocumentIds()

    override suspend fun isDocumentRevoked(documentId: String): Boolean =
        delegate().isDocumentRevoked(documentId)
}
