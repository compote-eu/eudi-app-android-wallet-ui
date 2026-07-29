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
}
