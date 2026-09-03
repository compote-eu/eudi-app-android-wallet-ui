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

import io.ktor.client.request.get
import org.multipaz.util.Logger
import eu.europa.ec.shared.wallet.WalletDocument
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.shared.wallet.document.toWalletDocument
import kotlinx.io.bytestring.ByteString
import org.multipaz.document.Document
import eu.europa.ec.shared.wallet.config.iosWalletConfig
import eu.europa.ec.shared.wallet.revocation.RevocationActionDomain
import eu.europa.ec.shared.wallet.revocation.StatusTrustPolicyDomain
import eu.europa.ec.shared.wallet.revocation.revocationAction
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
 *  - ✅ **claims for both formats** — mdoc through `MdocCredential`, SD-JWT VC through multipaz's
 *    `SdJwtVcCredential`. This used to say mdoc-only, blaming the JVM-only `eudi-lib-jvm-sdjwt-kt`;
 *    multipaz had `org.multipaz.sdjwt` on the classpath the whole time.
 *  - ✅ **revocation**, over multipaz's own Token Status List implementation — see
 *    [MultipazRevocationChecker] for why not `eudi-lib-kmp-statium`. Cached in storage like Android's
 *    Room table; the *trigger* is the host's — see [refreshRevocationStatuses] for the two iOS has
 *    instead of WorkManager.
 *
 * Not a Koin `@Factory`, though iOS now has a DI graph and the shared view-models do consume
 * `WalletEngine` through it: `IosWalletModule` provides the *public* [IosWalletEngine], and this class
 * is internal and multipaz-typed, so exposing it would put multipaz in the graph's signature. Construct
 * it through [createIosWalletEngine], which opens the store and hands back the `WalletEngine`
 * interface rather than this type.
 */
internal class MultipazWalletEngine(
    private val store: MultipazWalletStore,
) : WalletEngine {
    /**
     * The issuer's own display name for each claim, for [locale] — `family_name` → "Family Name(s)".
     *
     * Empty when the issuer published no names for this document type. Callers fall back to the
     * identifier, so an empty map is a display gap and never a wrong value.
     *
     * **Resolution happens here, not at issuance.** What is stored is the issuer's whole
     * `display: List<Display>` per claim — every locale it published, keyed by claim path — so the
     * choice of language is made when the screen is drawn and the stored data never has to be rewritten.
     * The cost is that the *set* of locales is fixed at issuance: if the issuer adds one later, existing
     * documents will not have it.
     */
    suspend fun getClaimDisplayNames(documentId: String, locale: String): Map<String, String> {
        val document = store.documentStore.lookupDocument(documentId) ?: return emptyMap()
        val metadata = document.eudiMetadata ?: return emptyMap()
        // Null and empty mean the same thing now: the issuer published no names for this type. Both
        // are recorded at issuance, so there is nothing to go and find out.
        val claims = metadata.issuerMetadata?.claims ?: return emptyMap()

        return claims.mapNotNull { claim ->
            // The last path segment is the data-element identifier the claim map is keyed by; the
            // earlier segments are the namespace, which the caller already knows.
            val identifier = claim.path.lastOrNull() ?: return@mapNotNull null
            claim.displayNameFor(locale)?.let { identifier to it }
        }.toMap()
    }

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
    }.onSuccess {
        // Tell iOS's document-provider registry, or the registration outlives the document and the
        // system credential picker keeps offering something that can no longer be presented.
        //
        // Here rather than on `IosWalletEngine`, which is the host-facing wrapper: this is where the
        // document actually goes, so no caller can delete around the notification. Putting it on the
        // wrapper first is what the deletion test caught.
        //
        // 📌 **The reference iOS wallet gets this wrong twice, so do not "align" it back.** Its
        // `deleteDocument(with:status:)` never unregisters at all, and its `clearAllDocuments()` asks
        // for the ids to unregister *after* deleting every document, so that list is empty and the
        // call removes nothing. Their registration API is mirrored on our Swift side; their wiring
        // of it is not.
        IosDocumentRegistration.registry?.documentsChanged()
    }

    /** The document's mdoc claims with their namespaces, for the details screen. */
    suspend fun getNamespacedClaims(documentId: String): Map<String, StoredMdocClaim> =
        ownDocuments().firstOrNull { it.identifier == documentId }
            ?.readNamespacedClaims()
            ?: emptyMap()

    /** The document's SD-JWT VC claims, values still as JSON; see [readJsonClaims]. */
    suspend fun getJsonClaims(documentId: String): List<StoredJsonClaim> =
        ownDocuments().firstOrNull { it.identifier == documentId }
            ?.readJsonClaims()
            ?: emptyList()

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
     * is not a one-way door. What it deliberately does not do is decide *when* to run: the trigger is
     * the caller's (see `IosWalletEngine.refreshRevocationStatuses` for the two triggers iOS uses
     * instead of Android's 15-minute WorkManager period).
     *
     * The return value is what a caller needs to raise the "documents revoked" notification the
     * Android broadcast produces; ignoring it and reading [getRevokedDocumentIds] afterwards is also
     * fine.
     */
    suspend fun refreshRevocationStatuses(
        checker: MultipazRevocationChecker,
        policy: StatusTrustPolicyDomain = iosWalletConfig.statusTrustPolicy,
        onOutcome: (documentId: String, outcome: RevocationOutcome) -> Unit = { _, _ -> },
    ): List<WalletDocument> {
        val table = store.revokedDocumentsTable()
        val alreadyRevoked = table.enumerate().toSet()
        val newlyRevoked = mutableListOf<WalletDocument>()

        ownDocuments().forEach { document ->
            val outcome = checker.check(document.revocationStatus())
            onOutcome(document.identifier, outcome)

            // The decision is not made here. Both platforms map their library's result onto the
            // shared reading and ask `revocationAction`, so the rule lives in one place and is
            // tested on both targets — see `eu.europa.ec.shared.wallet.revocation`.
            val action = revocationAction(
                status = outcome.toDocumentStatusDomain(),
                signerTrust = outcome.toSignerTrustDomain(),
                policy = policy,
                currentlyFlagged = document.identifier in alreadyRevoked,
            )

            // Into multipaz's logger, which means into the shareable log file on iOS. The anchoring
            // half is the point: a reading acted on without an anchored signer is exactly what a
            // reader of these logs needs to see, and until iOS has trust anchors that is every one.
            Logger.i(
                TAG,
                "${document.identifier}: ${outcome.toDocumentStatusDomain()} " +
                        "signer=${outcome.toSignerTrustDomain()} policy=$policy -> $action",
            )

            when (action) {
                RevocationActionDomain.Flag -> {
                    table.insert(key = document.identifier, data = ByteString())
                    newlyRevoked += WalletDocument(id = document.identifier)
                }

                RevocationActionDomain.Clear -> table.delete(document.identifier)

                RevocationActionDomain.Leave -> Unit
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
        private const val TAG = "MultipazWalletEngine"
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
