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

import eu.europa.ec.shared.wallet.document.isDueForReIssuance
import kotlinx.coroutines.CancellationException
import org.multipaz.document.Document
import org.multipaz.util.Logger
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What a sweep did, in a shape Swift can print.
 *
 * `refreshed` is documents whose credentials were actually topped up; a document that was due and then
 * failed counts as [failed], not as a silent success. 🪤 The reason given here until 2026-09-04 was that
 * `BGTaskScheduler` weighs completion when deciding whether to grant time again — true then, void now
 * that there is no scheduler. The honest-counting rule outlives it: this summary is the **only** place a
 * failed top-up surfaces, since the caller has no screen to show one on, and it under-reported failures
 * for exactly that reason until `7485f1cc`.
 */
data class BackgroundReIssuanceSummary(
    val considered: Int,
    val due: Int,
    val refreshed: Int,
    val failed: Int,
    val unchanged: Int,
    /**
     * The documents a re-issuance replaced, at once or by one the issuer deferred: what Android's worker
     * names in its details broadcast, so a details screen showing one of them closes.
     */
    val replaced: List<String> = emptyList(),
) {
    val didWork: Boolean get() = refreshed > 0 || replaced.isNotEmpty()

    /**
     * Whether every due document was accounted for — the invariant that makes this summary readable.
     *
     * A sweep that leaves due documents in none of the three buckets is under-reporting, which is
     * exactly the defect [unchanged] was added to close: a failed fetch used to land nowhere and the
     * summary said `due=1 refreshed=0 failed=0`, which reads as "nothing needed doing".
     */
    val accountedFor: Boolean get() = due == refreshed + failed + unchanged

    override fun toString(): String =
        "considered=$considered due=$due refreshed=$refreshed failed=$failed unchanged=$unchanged " +
            "replaced=${replaced.size}"
}

/** What one due document's refresh attempt amounted to. */
internal enum class RefreshOutcome { Refreshed, Failed, NothingToFetch }

/**
 * Which bucket one attempt falls into.
 *
 * Extracted from the sweep so the classification can be tested without a store, and so that adding a
 * variant to [IosIssuanceProgress] forces a decision here rather than falling into an `else` — which is
 * how the original defect survived: `Failure` was invisible because the call site asked only whether
 * credentials had been fetched.
 *
 * 🚩 **A partial batch counts as [RefreshOutcome.Refreshed].** `Issued.failures` can be non-empty while
 * credentials still arrived, and its own KDoc leaves the verdict to the caller. Work did happen, so the
 * sweep should not tell iOS the run failed; the failures are logged instead. Zero fetched *with*
 * failures is the genuinely bad case and counts as [RefreshOutcome.Failed].
 */
internal fun refreshOutcomeOf(progress: IosIssuanceProgress): RefreshOutcome = when (progress) {
    is IosIssuanceProgress.Failure,
    is IosIssuanceProgress.IssuerNotTrusted,
        -> RefreshOutcome.Failed
    is IosIssuanceProgress.Issued -> when {
        progress.credentialsFetched > 0 -> RefreshOutcome.Refreshed
        progress.failures.isNotEmpty() -> RefreshOutcome.Failed
        else -> RefreshOutcome.NothingToFetch
    }
}

/** Counts [outcomes] into a summary, so the arithmetic is testable apart from the sweep that gathers it. */
internal fun summarize(
    considered: Int,
    outcomes: List<RefreshOutcome>,
    replaced: List<String> = emptyList(),
): BackgroundReIssuanceSummary = BackgroundReIssuanceSummary(
    considered = considered,
    due = outcomes.size,
    refreshed = outcomes.count { it == RefreshOutcome.Refreshed },
    failed = outcomes.count { it == RefreshOutcome.Failed },
    unchanged = outcomes.count { it == RefreshOutcome.NothingToFetch },
    replaced = replaced,
)

/**
 * The documents a sweep re-issues: due by their own policy, without a deferred re-issuance on the way to
 * replace them, and not revoked or suspended.
 *
 * One with a replacement on the way stays due until that arrives, and re-issuing it again would only
 * park another: wallet-core's worker has no such check, and would add a `Pending` document every interval
 * until the first is collected (read from wallet-core 0.31 and the app's worker, not measured).
 *
 * A blocked one is upstream's rule (#729, `b595f866`): the re-issued document would carry no revocation
 * record, so the block would be lost.
 */
internal suspend fun dueForReIssuance(
    store: MultipazWalletStore,
    documents: List<Document>,
    now: Instant,
): List<Document> {
    val blocked = store.blockedDocumentIds()
    val beingReplaced = store.documentsAwaitingReplacement()
    return documents.filter { document ->
        document.identifier !in beingReplaced &&
            document.identifier !in blocked &&
            document.toStoredDocument()?.isDueForReIssuance(now) == true
    }
}

/** The documents the last status check found revoked or suspended; see `MultipazWalletEngine.refreshRevocationStatuses`. */
private suspend fun MultipazWalletStore.blockedDocumentIds(): Set<String> = revokedDocumentsTable().enumerate().toSet()

/**
 * Re-issues every document whose policy says it is running out, as a new document that replaces it
 * ([IosCredentialIssuer.reIssueDocument]).
 *
 * ⚠️ **"Background" is now a name, not a description.** This was iOS's counterpart of Android's
 * `ReIssuanceWorkManager` — a `BGProcessingTask` running with the app closed — until that task was
 * removed on 2026-09-04 so the wallet database could carry `NSFileProtectionComplete`. It is now driven
 * by [IosForegroundChecks], every 15 minutes while the app is active, as Android's worker repeats;
 * running only while active is the deliberate difference.
 *
 * 🚩 So nothing here runs while the device is locked, and nothing should be added that does: the
 * database is unreadable then, by design.
 *
 * **Where the thresholds come from is the whole point.** Each document carries the policy the issuer
 * advertised in `credential_reuse_policy`, so "is this due?" is answered per document by
 * `isDueForReIssuance` rather than by a wallet-wide rule — the same question Android's worker asks, in
 * shared code so the two cannot drift.
 *
 * **What this deliberately does not do:**
 * - *No browser fallback.* Android passes `allowAuthorizationFallback = false` for exactly this reason:
 *   a background sweep must never try to put a user in front of an authorization page. A document whose
 *   refresh token has expired is left for the user to fix in the foreground.
 * - *No pruning of other documents.* A replaced one goes with its re-issuance, and the broadcasts are
 *   Android's ([reIssuanceBroadcasts]). A screen composed after the sweep still sees the result, because
 *   the Documents screen re-reads the store when it appears.
 *
 * Failures are per document: one issuer being unreachable must not stop the sweep, since the whole
 * point is to catch up on whatever it can in the few seconds iOS grants.
 */
suspend fun runBackgroundReIssuance(): BackgroundReIssuanceSummary {
    val engine = IosWalletEngine()
    val store = engine.store()
    val now = Clock.System.now()

    // Scoped to our own documents, as everywhere else that reads the store.
    val documents = store.documentStore.listDocuments()
        .filter { it.eudiMetadata?.documentManagerId == store.documentManagerId }
    val outcomes = mutableListOf<RefreshOutcome>()
    val replaced = mutableListOf<String>()

    for (document in dueForReIssuance(store, documents, now)) {
        try {
            val progress = engine.reIssueDocument(document.identifier)
            val outcome = refreshOutcomeOf(progress)
            outcomes += outcome
            if (progress is IosIssuanceProgress.Issued && progress.documentIds.any { it != document.identifier }) {
                replaced += document.identifier
            }
            when (outcome) {
                RefreshOutcome.Refreshed -> Logger.i(
                    TAG,
                    "re-issued ${document.identifier} with ${(progress as IosIssuanceProgress.Issued).credentialsFetched} " +
                        "credential(s)"
                )
                // Logged at warning level because this is the case that used to vanish: a document
                // that was due, was attempted, and did not get credentials.
                RefreshOutcome.Failed ->
                    Logger.w(TAG, "refresh failed for ${document.identifier}: $progress")
                // Now the issuer deferring the new document: it replaces this one when it arrives.
                RefreshOutcome.NothingToFetch ->
                    Logger.i(TAG, "nothing fetched yet for ${document.identifier}: $progress")
            }
        } catch (cancelled: CancellationException) {
            // iOS asked for the time back. Stop where we are rather than burning the expiration
            // handler's grace period on another network round-trip.
            Logger.i(TAG, "sweep cancelled after ${outcomes.count { it == RefreshOutcome.Refreshed }} refresh(es)")
            throw cancelled
        } catch (error: Throwable) {
            outcomes += RefreshOutcome.Failed
            Logger.e(TAG, "re-issuance failed for ${document.identifier}", error)
        }
    }

    return summarize(
        considered = documents.size,
        outcomes = outcomes,
        replaced = replaced,
    )
        .also { Logger.i(TAG, "background re-issuance: $it") }
        .also { announce(reIssuanceBroadcasts(it), TAG) }
}

private const val TAG = "IosBackgroundReIssuance"
