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
import org.multipaz.util.Logger
import kotlin.time.Clock

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
) {
    val didWork: Boolean get() = refreshed > 0

    /**
     * Whether every due document was accounted for — the invariant that makes this summary readable.
     *
     * A sweep that leaves due documents in none of the three buckets is under-reporting, which is
     * exactly the defect [unchanged] was added to close: a failed fetch used to land nowhere and the
     * summary said `due=1 refreshed=0 failed=0`, which reads as "nothing needed doing".
     */
    val accountedFor: Boolean get() = due == refreshed + failed + unchanged

    override fun toString(): String =
        "considered=$considered due=$due refreshed=$refreshed failed=$failed unchanged=$unchanged"
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
    is IosIssuanceProgress.Failure -> RefreshOutcome.Failed
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
): BackgroundReIssuanceSummary = BackgroundReIssuanceSummary(
    considered = considered,
    due = outcomes.size,
    refreshed = outcomes.count { it == RefreshOutcome.Refreshed },
    failed = outcomes.count { it == RefreshOutcome.Failed },
    unchanged = outcomes.count { it == RefreshOutcome.NothingToFetch },
)

/**
 * Tops up the credentials of every document whose policy says it is running out.
 *
 * ⚠️ **"Background" is now a name, not a description.** This was iOS's counterpart of Android's
 * `ReIssuanceWorkManager` — a `BGProcessingTask` running with the app closed — until that task was
 * removed on 2026-09-04 so the wallet database could carry `NSFileProtectionComplete`. It is now driven
 * from `refreshWalletOnLaunch()` in `iOSApp.swift`, once per process. Android still enqueues its worker
 * every 15 minutes; the divergence is deliberate.
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
 * - *No deletion, and no notifications.* Android's worker also prunes and broadcasts; on iOS the
 *   Documents screen re-reads the store when it appears, so a top-up shows up on its own.
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

    for (document in documents) {
        val stored = document.toStoredDocument() ?: continue
        if (!stored.isDueForReIssuance(now)) continue

        try {
            val progress = engine.refreshCredentials(document.identifier)
            val outcome = refreshOutcomeOf(progress)
            outcomes += outcome
            when (outcome) {
                RefreshOutcome.Refreshed -> Logger.i(
                    TAG,
                    "re-issued ${(progress as IosIssuanceProgress.Issued).credentialsFetched} " +
                        "credential(s) for ${document.identifier}"
                )
                // Logged at warning level because this is the case that used to vanish: a document
                // that was due, was attempted, and did not get credentials.
                RefreshOutcome.Failed ->
                    Logger.w(TAG, "refresh failed for ${document.identifier}: $progress")
                RefreshOutcome.NothingToFetch ->
                    Logger.i(TAG, "nothing to fetch for ${document.identifier}: $progress")
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

    return summarize(considered = documents.size, outcomes = outcomes)
        .also { Logger.i(TAG, "background re-issuance: $it") }
}

private const val TAG = "IosBackgroundReIssuance"
