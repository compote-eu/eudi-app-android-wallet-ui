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

import eu.europa.ec.shared.wallet.document.IssuerMetadata
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import org.multipaz.util.Logger
import kotlin.coroutines.cancellation.CancellationException
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
 *    Room table; the *trigger* is the host's — see [refreshRevocationStatuses] for what iOS has
 *    instead of WorkManager, and why revocation is not on it yet.
 *
 * Not a Koin `@Factory`, though iOS now has a DI graph and the shared view-models do consume
 * `WalletEngine` through it: `IosWalletModule` provides the *public* [IosWalletEngine], and this class
 * is internal and multipaz-typed, so exposing it would put multipaz in the graph's signature. Construct
 * it through [create].
 */
internal class MultipazWalletEngine(
    private val store: MultipazWalletStore,
    /**
     * Swappable so a test can back the claim-name backfill with a `MockEngine` rather than the network;
     * production takes the default.
     */
    private val claimNameHttpEngine: HttpClientEngine? = null,
) : WalletEngine {

    /** Documents whose claim names have been fetched for this run; see `backfillClaimNames`. */
    private val claimNameFetchAttempted = mutableSetOf<String>()
    /**
     * The issuer's own display name for each claim, for [locale] — `family_name` → "Family Name(s)".
     *
     * Empty when this document was provisioned before claim names were stored, or when the issuer
     * published none. Callers fall back to the identifier, so an empty map is a display gap and never a
     * wrong value.
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
        // `null` means this document predates claim names being captured at issuance; an empty list
        // means the issuer publishes none. Only the first is worth a fetch.
        val claims = metadata.issuerMetadata?.claims
            ?: backfillClaimNames(document, metadata)
            ?: return emptyMap()

        return claims.mapNotNull { claim ->
            // The last path segment is the data-element identifier the claim map is keyed by; the
            // earlier segments are the namespace, which the caller already knows.
            val identifier = claim.path.lastOrNull() ?: return@mapNotNull null
            claim.displayNameFor(locale)?.let { identifier to it }
        }.toMap()
    }

    /**
     * Fetches the issuer's claim names for a document provisioned before they were stored, and keeps
     * them.
     *
     * Reuses [openID4VciHttpClient] rather than a plain client, so the *same* code path reads the names
     * as at issuance: that client unwraps this issuer's signed (JWT) metadata and extracts
     * `credential_metadata.claims` into the notice. One implementation of "how claim names are read", not
     * two that can drift.
     *
     * Returns null when nothing could be learned, and **only persists on success** — including a
     * successful fetch that yields nothing, which settles the document so it is never re-fetched. A
     * failure leaves `claims` null so a later view can try again.
     */
    private suspend fun backfillClaimNames(
        document: Document,
        metadata: EudiDocumentMetadata,
    ): List<IssuerMetadata.Claim>? {
        // Documents issued before `1f3fdefa` recorded the issuer's *name* here rather than its URL, so
        // they cannot be resolved at all — see the ledger. Guarding on the scheme is what tells the two
        // apart without a second field.
        val issuerUrl = metadata.issuerMetadata?.credentialIssuerIdentifier
            ?.takeIf { it.startsWith("https://") }
            ?: return null

        // One attempt per document per run, whatever the outcome. Measured, not theoretical: a details
        // view of a seeded fixture — whose issuer is the deliberately unresolvable
        // `fixture.issuer.invalid` — produced three DNS lookups in a single probe run, and a real
        // document whose issuer has gone away would do the same forever. Deliberately *not* persisted:
        // a later launch should try again, because the usual reason this fails is that the network was
        // down, and that is not a property of the document.
        if (!claimNameFetchAttempted.add(document.identifier)) return null

        val notice = IssuerClaimDisplayNotice()
        val client = openID4VciHttpClient(
            engine = claimNameHttpEngine ?: Darwin.create(),
            claimDisplayNotice = notice,
        )
        val fetched = try {
            val response = client.get("$issuerUrl/.well-known/openid-credential-issuer")
            // ktor does not throw on a non-2xx by default, and a `503` must not be mistaken for "this
            // issuer publishes no names" — that would settle the document on the strength of a bad
            // minute. A test pins this; it failed on the first attempt at exactly this line.
            if (!response.status.isSuccess()) {
                Logger.w(
                    TAG,
                    "claim names for ${document.identifier}: $issuerUrl answered ${response.status}",
                )
                return null
            }
            response.bodyAsText()
            notice.claimsByDocumentType
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, "could not fetch claim names for ${document.identifier}: ${t.message}")
            return null
        } finally {
            client.close()
        }

        // Empty is a real answer and is stored; see `rememberClaimNames`.
        val claims = fetched[metadata.format.identifier].orEmpty()
        metadata.rememberClaimNames(claims)
        document.edit { this.metadata = metadata }
        Logger.i(
            TAG,
            "backfilled ${claims.size} claim name(s) for ${document.identifier} from $issuerUrl",
        )
        return claims
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
     * the caller's (see `IosWalletEngine.refreshRevocationStatuses`, which explains what iOS now has
     * instead of Android's 15-minute WorkManager period, and why revocation does not use it yet).
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
