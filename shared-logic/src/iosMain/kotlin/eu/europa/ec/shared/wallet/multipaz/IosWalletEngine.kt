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
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
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
    private var store: MultipazWalletStore? = null

    /** Deletes a document. Not on [WalletEngine]: Android deletes through its wallet-core controller. */
    suspend fun deleteDocument(documentId: String): Result<Unit> =
        (delegate() as MultipazWalletEngine).deleteDocument(documentId)

    /** The document's mdoc claims with namespaces; see `StoredMdocClaim`. */
    suspend fun getNamespacedClaims(documentId: String): Map<String, StoredMdocClaim> =
        (delegate() as MultipazWalletEngine).getNamespacedClaims(documentId)

    /** Whether the wallet holds any document at all, which deletion needs in order to report which. */
    suspend fun hasAnyDocument(): Boolean = getAllDocuments().isNotEmpty()

    /**
     * Re-checks every document against its status list and updates the cached flags, returning the
     * documents that *became* revoked.
     *
     * **The trigger is the host's, and that is the design, not an omission.** Android runs this every
     * 15 minutes through WorkManager; iOS has no equivalent a Compose-only host can install —
     * `BGTaskScheduler` needs app-delegate wiring and an Info.plist identifier on the Swift side. So
     * the host decides: at launch, on foreground, or from a pull-to-refresh. Calling it more often than
     * the status list's `ttl` only wastes a request; calling it never leaves documents unflagged,
     * exactly as Android behaves before its first period elapses.
     *
     * The [HttpClient] is created per call and closed after: refreshes are minutes apart at best, so
     * holding a client (and its connection pool) open between them buys nothing.
     */
    suspend fun refreshRevocationStatuses(
        onOutcome: (documentId: String, outcome: RevocationOutcome) -> Unit = { _, _ -> },
    ): List<WalletDocument> {
        val engine = delegate() as MultipazWalletEngine
        return HttpClient(Darwin).use { client ->
            engine.refreshRevocationStatuses(
                checker = MultipazRevocationChecker(client),
                onOutcome = onOutcome,
            )
        }
    }

    /**
     * The open store, for the paths that need more than [WalletEngine] offers — issuance writes documents
     * and must write into *this* store: a second `MultipazWalletStore.open()` over the same storage is a
     * second `DocumentStore` with its own cache, so a document created there would not show up in a list
     * read from here until something reloaded.
     */
    internal suspend fun store(): MultipazWalletStore {
        delegate()
        return checkNotNull(store) { "the store is open once delegate() has returned" }
    }

    private suspend fun delegate(): WalletEngine =
        delegate ?: mutex.withLock {
            // Re-check inside the lock: another caller may have opened it while we waited.
            delegate ?: MultipazWalletStore.open().let { opened ->
                store = opened
                MultipazWalletEngine(opened).also { delegate = it }
            }
        }

    /**
     * What the wallet has done: presentations and issuances, newest first.
     *
     * Not on the [WalletEngine] contract, because Android answers the same question from wallet-core's
     * own transaction log rather than from this engine — the shared side meets them at
     * `TransactionsPlatformBridge` instead. Public here only so that bridge, which is in :shared-ui and
     * must not name a multipaz type, can reach it.
     */
    suspend fun getTransactions(): List<IosTransaction> = store().transactions()

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
