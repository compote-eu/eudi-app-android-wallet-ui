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
import eu.europa.ec.shared.wallet.document.toWalletDocument
import kotlinx.io.bytestring.ByteString
import org.multipaz.document.Document
import org.multipaz.storage.KeyExistsStorageException

/**
 * The iOS [WalletEngine], reading multipaz's `DocumentStore` in Kotlin.
 *
 * This is the hybrid architecture's document half (see `wiki/KMP_FEASIBILITY.md`): multipaz is fully
 * multiplatform, but the EUDI layers above it — wallet-core and the document manager — are
 * Android-only, so the read path they provide is reimplemented here over the same substrate rather
 * than delegated to. The OpenID4VCI/VP half is *not* here; those libraries have no iOS artifact.
 *
 * What works, and what does not, in this milestone:
 *  - ✅ [getAllDocuments], [getAllDocumentsWithDetails] and [getMainPidDocument], with the same field
 *    mapping the Android engine performs — the projection they share lives in commonMain and is
 *    tested there against the same cases.
 *  - ✅ bookmarks, persisted beside the documents in multipaz's storage.
 *  - ⚠️ **claims are mdoc-only**, because SD-JWT VC parsing needs the JVM-only
 *    `eudi-lib-jvm-sdjwt-kt`. An SD-JWT document still lists correctly; only its claims are absent,
 *    which today means `getMainPidDocument` resolves claims for an mdoc PID but not an SD-JWT one.
 *  - ✅ **revocation**, over multipaz's own Token Status List implementation — see
 *    [MultipazRevocationChecker] for why not `eudi-lib-kmp-statium`. Cached in storage like Android's
 *    Room table; the *trigger* is the host's, since iOS has no WorkManager.
 *
 * Not a Koin `@Factory`: iOS has no DI graph for the wallet layer yet, and the shared view-models
 * that consume `WalletEngine` are not reachable on iOS until their screens are shared. Construct it
 * through [create].
 */
internal class MultipazWalletEngine(
    private val store: MultipazWalletStore,
) : WalletEngine {

    override suspend fun getAllDocuments(): List<WalletDocument> =
        ownDocuments().map { WalletDocument(id = it.identifier) }

    override suspend fun getAllDocumentsWithDetails(locale: String): List<WalletDocument> =
        ownDocuments()
            .mapNotNull { it.toStoredDocument() }
            .map { stored ->
                stored.toWalletDocument(
                    locale = locale,
                    isRevoked = isDocumentRevoked(stored.id),
                )
            }

    override suspend fun getMainPidDocument(): WalletDocument? =
        // The oldest issued PID of either format, matching
        // `WalletCoreDocumentsController.getMainPidDocument` — which sorts by creation time and
        // considers only issued documents. Candidates are narrowed from metadata alone, so claims
        // are parsed for the winner only. Like the Android engine, this returns id and claims and
        // nothing else; use getAllDocumentsWithDetails for the rest.
        ownDocuments()
            .filter { document ->
                document.eudiMetadata?.let { it.issuedAt != null && it.format.identifier in PidFormatTypes } == true
            }
            .minByOrNull { it.created }
            ?.toStoredDocument(readClaims = true)
            ?.let { WalletDocument(id = it.id, claims = it.claims) }

    /**
     * Deletes [documentId] and its credentials, and drops any bookmark it had — the bookmark table is
     * keyed by document id, so leaving the row would silently re-apply to a future document that reused
     * the id.
     */
    suspend fun deleteDocument(documentId: String): Result<Unit> = runCatching {
        store.documentStore.deleteDocument(documentId)
        deleteBookmark(documentId)
    }

    /** The document's mdoc claims with their namespaces, for the details screen. */
    suspend fun getNamespacedClaims(documentId: String): Map<String, StoredMdocClaim> =
        ownDocuments().firstOrNull { it.identifier == documentId }
            ?.readNamespacedClaims()
            ?: emptyMap()

    //region bookmarks

    override suspend fun isDocumentBookmarked(documentId: String): Boolean =
        store.bookmarksTable().get(documentId) != null

    override suspend fun storeBookmark(bookmarkId: String) {
        // Presence is the bookmark, so re-bookmarking an already-bookmarked document is a no-op
        // rather than an error — the Android side's DAO upsert behaves the same way.
        try {
            store.bookmarksTable().insert(key = bookmarkId, data = ByteString())
        } catch (_: KeyExistsStorageException) {
            // Already bookmarked.
        }
    }

    override suspend fun deleteBookmark(bookmarkId: String) {
        store.bookmarksTable().delete(bookmarkId)
    }

    //endregion

    //region revocation

    /**
     * The documents a previous [refreshRevocationStatuses] found revoked, from the cache table.
     *
     * Read from storage rather than checked live for the same reason Android reads Room here: a
     * status-list check needs the network, and every caller of this is rendering a list.
     */
    override suspend fun getRevokedDocumentIds(): List<String> =
        store.revokedDocumentsTable().enumerate()

    override suspend fun isDocumentRevoked(documentId: String): Boolean =
        store.revokedDocumentsTable().get(documentId) != null

    /**
     * Re-checks every document's status list and updates the cache, returning the documents that
     * *became* revoked in this pass.
     *
     * This is the body of Android's `RevocationWorkManager` — including its two-way behaviour, which
     * is easy to miss: a credential that is valid again has its cached row **removed**, so revocation
     * is not a one-way door. What it deliberately does not do is decide *when* to run. Android has
     * WorkManager and a 15-minute period; iOS has no equivalent that a Compose-only host can install,
     * so the trigger is the caller's (see `IosWalletEngine.refreshRevocationStatuses`).
     *
     * The return value is what a caller needs to raise the "documents revoked" notification the
     * Android broadcast produces; ignoring it and reading [getRevokedDocumentIds] afterwards is also
     * fine.
     */
    suspend fun refreshRevocationStatuses(
        checker: MultipazRevocationChecker,
        onOutcome: (documentId: String, outcome: RevocationOutcome) -> Unit = { _, _ -> },
    ): List<WalletDocument> {
        val table = store.revokedDocumentsTable()
        val alreadyRevoked = table.enumerate().toSet()
        val newlyRevoked = mutableListOf<WalletDocument>()

        ownDocuments().forEach { document ->
            val outcome = checker.check(document.revocationStatus())
            onOutcome(document.identifier, outcome)

            when {
                outcome.isRevoked && document.identifier !in alreadyRevoked -> {
                    table.insert(key = document.identifier, data = ByteString())
                    newlyRevoked += WalletDocument(id = document.identifier)
                }

                // Valid again: drop the flag. `Unknown` deliberately does not — an unreachable
                // status list must not silently clear a revocation.
                outcome is RevocationOutcome.Valid && document.identifier in alreadyRevoked -> {
                    table.delete(document.identifier)
                }
            }
        }

        return newlyRevoked
    }

    //endregion

    /**
     * The documents this wallet owns. Scoped by document-manager id like
     * `DocumentManagerImpl.getDocuments`, so a store shared with another component never leaks
     * documents into our list — and read without touching credentials, keeping [getAllDocuments]
     * as cheap as its contract promises.
     */
    private suspend fun ownDocuments(): List<Document> =
        store.documentStore.listDocuments()
            .filter { it.eudiMetadata?.documentManagerId == store.documentManagerId }

    companion object {

        /**
         * The PID format identifiers, duplicated from `DocumentIdentifier.MdocPid` / `SdJwtPid` in
         * `:shared-ui`'s `eu.europa.ec.corelogic.model`, which this module cannot import — the
         * dependency runs shared-ui -> shared-logic, not the other way. Keep the two in step.
         */
        private val PidFormatTypes = setOf("eu.europa.ec.eudi.pid.1", "urn:eudi:pid:1")
    }
}

/**
 * Opens the wallet's document store on this device and returns the iOS [WalletEngine] over it.
 *
 * Suspending because opening the store creates the Secure Enclave secure area, and returning
 * `WalletEngine` rather than the implementation keeps multipaz types out of this module's public API
 * (and out of the SharedKit framework header).
 */
suspend fun createIosWalletEngine(): WalletEngine =
    MultipazWalletEngine(MultipazWalletStore.open())
