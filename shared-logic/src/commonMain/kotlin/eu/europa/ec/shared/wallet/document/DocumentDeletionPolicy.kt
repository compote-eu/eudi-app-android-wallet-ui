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

package eu.europa.ec.shared.wallet.document

/**
 * What deleting one document does to the rest of the wallet.
 *
 * The two values map onto the partial states the delete flows already emit —
 * `AllDocumentsDeleted` and `SingleDocumentDeleted` — and the view models turn that into very
 * different journeys: [WholeWallet] navigates to the splash screen, popping the dashboard, so the user
 * lands back at the start; [SingleDocument] simply pops back to where they were.
 */
enum class DocumentDeletionScope {

    /**
     * The wallet goes back to holding nothing, and the user is returned to the start of the app.
     *
     * On the details screen this is a *decision to delete everything*, taken before anything is
     * deleted. On the documents list it is a *description* of what the single deletion left behind.
     */
    WholeWallet,

    /** Only the document the user asked about is deleted, and they return to the previous screen. */
    SingleDocument,
}

/**
 * What to delete when the user deletes a document from its **details** screen.
 *
 * The rule: a build that insists on holding a PID ([forcePidActivation]) cannot go on holding the
 * other documents once the PID they hang off is gone, so removing that PID takes everything with it.
 * With several PIDs present, only the main one matters — the rest are spares, and deleting a spare
 * leaves the wallet perfectly usable.
 *
 * @param forcePidActivation whether this build refuses to be useful without a PID
 *   (`SharedAppConfig.forcePidActivation`, false in every build of either platform today).
 * @param deletedDocumentIsPid whether the document being deleted is a PID, in either format.
 * @param pidDocumentCount how many PIDs the wallet holds *including* the one being deleted.
 * @param deletedDocumentIsMainPid whether it is the PID the wallet treats as the main one. Only
 *   consulted when more than one PID exists; with exactly one, that one is the main one by
 *   definition, and Android's original reached the same answer through its `else true` branch.
 */
fun documentDeletionScope(
    forcePidActivation: Boolean,
    deletedDocumentIsPid: Boolean,
    pidDocumentCount: Int,
    deletedDocumentIsMainPid: Boolean,
): DocumentDeletionScope = when {
    !forcePidActivation -> DocumentDeletionScope.SingleDocument
    !deletedDocumentIsPid -> DocumentDeletionScope.SingleDocument
    pidDocumentCount > 1 && !deletedDocumentIsMainPid -> DocumentDeletionScope.SingleDocument
    else -> DocumentDeletionScope.WholeWallet
}

/**
 * What the user is told after a document is deleted from the **documents list**.
 *
 * Unlike [documentDeletionScope] this changes nothing about what gets deleted — the single document
 * is already gone by the time it is asked. It answers whether that deletion left the wallet in a
 * state it cannot work in, which for a build requiring a PID means holding nothing at all.
 *
 * @param forcePidActivation as in [documentDeletionScope].
 * @param walletIsEmptyAfterDeletion whether any document at all remains.
 */
fun documentDeletionOutcome(
    forcePidActivation: Boolean,
    walletIsEmptyAfterDeletion: Boolean,
): DocumentDeletionScope =
    if (forcePidActivation && walletIsEmptyAfterDeletion) {
        DocumentDeletionScope.WholeWallet
    } else {
        DocumentDeletionScope.SingleDocument
    }
