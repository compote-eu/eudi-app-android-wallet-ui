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

import org.multipaz.util.Logger

/*
 * What a re-issuance does around the document it replaces: wallet-core 0.31's rules, with the app's
 * from upstream #729 (`20dca6db`, "Delete a document's records with it").
 *
 * - The new document takes the original's bookmark as soon as it exists, a deferred one included: it
 *   keeps its id when its credential arrives.
 * - The original goes once the new one is issued, at once or when a deferred credential arrives, and it
 *   goes the way any document goes here ([MultipazWalletEngine.deleteDocument]): the History gains its
 *   deletion row, as wallet-core's deletion logger writes one on Android, and its records go with it.
 * - If the original was found revoked while the re-issuance ran, the new document's status is checked
 *   without waiting for the next periodic check.
 */

/** Gives the documents that replace [originalId] what the user attached to it: its bookmark. */
internal suspend fun MultipazWalletEngine.carryOverToReplacement(originalId: String, replacementIds: List<String>) {
    if (replacementIds.isEmpty() || originalId in replacementIds) return
    if (isDocumentBookmarked(originalId)) replacementIds.forEach { storeBookmark(it) }
}

/**
 * Deletes [originalId] now that its replacement is issued.
 *
 * Best effort, as on Android: the re-issuance has succeeded whatever happens here, and a document that
 * could not be deleted is still a document the user can remove.
 */
internal suspend fun MultipazWalletEngine.retireReplaced(originalId: String, requestRevocationCheck: () -> Unit) {
    if (isDocumentRevoked(originalId)) requestRevocationCheck()
    deleteDocument(originalId)
        .onSuccess { Logger.i(TAG, "deleted $originalId, which its re-issuance replaced") }
        .onFailure { Logger.w(TAG, "could not delete $originalId after its re-issuance", it) }
}

/** The documents a parked re-issuance will replace once its credential arrives. */
internal suspend fun MultipazWalletStore.documentsAwaitingReplacement(): Set<String> =
    documentStore.listDocuments().mapNotNullTo(mutableSetOf()) { it.eudiMetadata?.replacesDocumentId }

private const val TAG = "DocumentReplacement"
