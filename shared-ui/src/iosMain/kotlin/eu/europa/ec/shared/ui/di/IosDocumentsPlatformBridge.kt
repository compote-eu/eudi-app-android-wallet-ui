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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.corelogic.model.DeferredDocumentDataDomain
import eu.europa.ec.corelogic.model.DocumentCategories
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.dashboardfeature.interactor.DocumentInteractorDeleteDocumentPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentInteractorRetryIssuingDeferredDocumentsPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentsPlatformBridge
import eu.europa.ec.shared.wallet.config.iosWalletConfig
import eu.europa.ec.shared.wallet.multipaz.DeferredCollection
import eu.europa.ec.shared.wallet.document.DocumentDeletionScope
import eu.europa.ec.shared.wallet.document.documentDeletionOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * iOS's [DocumentsPlatformBridge]. Deliberately thin — the document-list mapping and the filter
 * definitions are shared, so this supplies only what genuinely differs.
 *
 * @param deleteDocument how to delete, injected so this file does not name a multipaz type; the
 * implementation lives with the engine in :shared-logic.
 */
internal class IosDocumentsPlatformBridge(
    private val deleteDocument: suspend (documentId: String) -> Result<Unit>,
    private val hasAnyDocument: suspend () -> Boolean,
    /** Asks the issuer for one parked document's credential. See `collectDeferredDocument`. */
    private val collectDeferred: suspend (documentId: String) -> DeferredCollection,
    /**
     * Every document actually waiting on the issuer, which is not the same set the screen offers.
     *
     * The screen selects by issuance state, and a **refresh** that was deferred keeps its existing
     * credentials and stays `Issued` — correctly, since the document is still usable. It would
     * therefore never be swept, and the credentials the issuer is minting for it would never be
     * claimed. This is the authoritative set.
     */
    private val awaitingDeferred: suspend () -> Map<String, FormatType>,
    /**
     * Names for the "ready" sheet, looked up **with details**: the cheap document list carries ids and
     * nothing else, so a name taken from it is always blank. Measured 2026-09-17 — the sheet would have
     * listed the collected documents with no names at all.
     */
    private val documentNames: suspend (locale: String) -> Map<String, String>,
) : DocumentsPlatformBridge {

    /**
     * The wallet's categorisation, from the one list both platforms read.
     *
     * This used to be a hand-written two-entry map — PID only — justified by a comment saying iOS
     * "cannot issue" anything else. That stopped being true, and the map did not: an mDL or a tax
     * credential accepted through a credential offer was filed under `Other` here and under
     * `Government` on Android. [DocumentCategories.Default] is now the single definition of this,
     * shared with `WalletCoreConfig`.
     */
    override val documentCategories: DocumentCategories
        get() = DocumentCategories.Default

    /** The device locale's language, which is all the issuer-display lookup matches on. */
    override fun localeTag(): String =
        NSLocale.currentLocale.languageCode

    /**
     * The user's own choice, from the same store the settings screen's switch writes to — so flipping
     * that switch changes what these rows show, as on Android. Defaults to true, as Android's
     * preference does.
     */
    override suspend fun showBatchIssuanceCounter(): Boolean =
        IosPreferences.showBatchIssuanceCounter()

    override fun deleteDocument(
        documentId: String,
    ): Flow<DocumentInteractorDeleteDocumentPartialState> = flow {
        deleteDocument.invoke(documentId).fold(
            onSuccess = {
                val outcome = documentDeletionOutcome(
                    forcePidActivation = iosWalletConfig.forcePidActivation,
                    walletIsEmptyAfterDeletion = !hasAnyDocument(),
                )
                emit(
                    when (outcome) {
                        DocumentDeletionScope.WholeWallet ->
                            DocumentInteractorDeleteDocumentPartialState.AllDocumentsDeleted

                        DocumentDeletionScope.SingleDocument ->
                            DocumentInteractorDeleteDocumentPartialState.SingleDocumentDeleted
                    }
                )
            },
            onFailure = { throwable ->
                emit(
                    DocumentInteractorDeleteDocumentPartialState.Failure(
                        errorMessage = throwable.message.orEmpty()
                    )
                )
            },
        )
    }

    /**
     * Reports nothing issued, rather than attempting a retry: **deferred** issuance has no iOS
     * implementation — multipaz treats the issuer's `202 Accepted` as an error and keeps the token and
     * DPoP state private, so there is nothing to resume from.
     *
     * ⚠️ This used to blame OpenID4VCI as a whole "having no iOS implementation", which is false:
     * `IosCredentialIssuer`, `IosOpenID4VciBackend`, `OpenID4VciHttpClient` and
     * `IosBackgroundReIssuance` are that implementation. Only the deferred branch is missing.
     *
     * An empty result is the same shape the Android side produces when no deferred document is ready,
     * so the caller needs no special case.
     */
    /**
     * Collects every document whose issuer deferred it.
     *
     * Three outcomes, and the difference between them is the whole point:
     * - **issued** — the document is finished and is named in the "ready" sheet;
     * - **still pending** — reported in *neither* list, so the document stays as it is and the next
     *   sweep asks again. This is the normal answer while an issuer is still working;
     * - **dead** — a spent handle or an authorization too old to refresh. Only these are reported as
     *   failed, because the screen renders that as a document that will never arrive.
     *
     * A transport failure is deliberately treated as "ask again later" rather than as a failure: a
     * wallet that marked a document dead because the network blinked would be worse than one that waits.
     */
    override fun tryIssuingDeferredDocuments(
        deferredDocuments: Map<String, FormatType>,
        dispatcher: CoroutineDispatcher,
    ): Flow<DocumentInteractorRetryIssuingDeferredDocumentsPartialState> = flow {
        val issuedIds = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val intervals = mutableListOf<Int>()
        // The screen's set first, so its ordering and its format types win where they overlap.
        val toSweep = deferredDocuments + awaitingDeferred().filterKeys { it !in deferredDocuments }
        toSweep.forEach { (documentId, _) ->
            when (val outcome = collectDeferred(documentId)) {
                is DeferredCollection.Issued -> issuedIds += documentId

                // Reported failed AND removed, which is what Android does with the same two outcomes
                // (`Expired` and `IssuerNotTrusted` both call `deleteDocument`). Leaving it in the
                // wallet is not neutral: the failed marking is transient UI state, so on the next
                // launch the document reappears as pending, fails again, and churns yellow→red for
                // ever. Watched on the simulator 2026-09-17 before this was added.
                is DeferredCollection.Abandoned,
                is DeferredCollection.AuthorizationExpired,
                    -> {
                    failed += documentId
                    // 🪤 `.invoke`, not `deleteDocument(id)`: that name also belongs to this class's
                    // own override, which returns a COLD Flow — calling it deletes nothing at all
                    // unless the flow is collected, and it reads exactly like a delete. Caught by
                    // `IosDeferredSweepTest`, which is the only reason it is not still there.
                    deleteDocument.invoke(documentId)
                }

                // Keep what the issuer asked for, so the caller can wait that long instead of guessing.
                is DeferredCollection.StillPending ->
                    outcome.retryAfterSeconds?.let { intervals += it }

                is DeferredCollection.Failed,
                is DeferredCollection.Unsupported,
                    -> Unit
            }
        }
        // One lookup for the whole sweep rather than one per document: the detailed read walks every
        // document's claims, and the sheet needs nothing else from it.
        val names = if (issuedIds.isEmpty()) emptyMap() else documentNames(localeTag())
        emit(
            DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result(
                successfullyIssuedDeferredDocuments = issuedIds.map { documentId ->
                    DeferredDocumentDataDomain(
                        documentId = documentId,
                        formatType = toSweep.getValue(documentId),
                        docName = names[documentId].orEmpty(),
                    )
                },
                failedIssuedDeferredDocuments = failed,
                // The LARGEST request, because `interval` means "not sooner than this" and one sweep
                // serves every pending document: waiting for the longest violates nobody, while the
                // shortest would poll a slower issuer earlier than it asked.
                retryAfterSeconds = intervals.maxOrNull(),
            )
        )
    }.flowOn(dispatcher)
}
