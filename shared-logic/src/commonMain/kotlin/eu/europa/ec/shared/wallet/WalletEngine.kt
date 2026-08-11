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

package eu.europa.ec.shared.wallet

/**
 * Phase-2 seam (see wiki/KMP_FEASIBILITY.md): the app-owned boundary to the wallet engine,
 * living in shared/commonMain. Presentation/business code depends on this interface with
 * platform-neutral types, instead of the Android-only `eu.europa.ec.eudi.wallet.*` /
 * Multipaz types. Each platform supplies an implementation — Android over
 * `eudi-lib-android-wallet-core`, iOS (later) over wallet-kit / a Multipaz KMP core.
 *
 * The interface grows one capability at a time; this first slice covers revocation, whose
 * signatures are already platform-neutral (String / Boolean).
 *
 * **Every accessor is `suspend`.** Reading the document store is I/O on both platforms — Android's
 * wallet-core hides that behind `runBlocking`, but on iOS the store is SQLite reached through
 * multipaz's suspending API, so a synchronous accessor could only be honoured by blocking the
 * calling thread, which on iOS is the UI thread. All consumers already call from a coroutine.
 */
interface WalletEngine {

    /** IDs of documents currently known to be revoked. */
    suspend fun getRevokedDocumentIds(): List<String>

    /** Whether the document with [documentId] is revoked. */
    suspend fun isDocumentRevoked(documentId: String): Boolean

    /** Whether the document with [documentId] is bookmarked. */
    suspend fun isDocumentBookmarked(documentId: String): Boolean

    /** Persist a bookmark for [bookmarkId]. */
    suspend fun storeBookmark(bookmarkId: String)

    /** Remove the bookmark for [bookmarkId]. */
    suspend fun deleteBookmark(bookmarkId: String)

    /**
     * The wallet's main PID document as an app-owned [WalletDocument], or null if there is
     * none. First domain-model capability: [WalletDocument] replaces the wallet-core Document
     * type at the seam.
     */
    suspend fun getMainPidDocument(): WalletDocument?

    /**
     * All documents currently in the wallet, as app-owned [WalletDocument]s carrying **identity
     * only**. For callers that just need to know what exists (or whether anything does); it does no
     * per-credential I/O. Use [getAllDocumentsWithDetails] when the fields actually matter.
     */
    suspend fun getAllDocuments(): List<WalletDocument>

    /**
     * All documents currently in the wallet, with every [WalletDocument] field populated: name,
     * format, issuance state, issuance/expiry instants, revocation, credential counts and the
     * issuer's display name and logo.
     *
     * Suspending and materially more expensive than [getAllDocuments] — resolving expiry, credential
     * counts and the low-on-credentials policy reads each document's credentials — so this is the
     * document-list accessor, not a general-purpose one.
     *
     * @param locale a BCP-47 language tag (e.g. `en-GB`) used to pick the issuer's localized
     * display name and logo. Localization of the *app's own* strings stays with the caller.
     */
    suspend fun getAllDocumentsWithDetails(locale: String): List<WalletDocument>
}
