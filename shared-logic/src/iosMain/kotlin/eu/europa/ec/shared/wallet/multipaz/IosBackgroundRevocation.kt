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

import kotlinx.coroutines.CancellationException
import org.multipaz.util.Logger

/**
 * What a revocation sweep found, in a shape Swift can log and report to the system.
 *
 * [newlyRevoked] is documents that were not flagged before this sweep and are now — the number worth
 * seeing in a log, because it is the only outcome that changes what the user is shown.
 */
data class BackgroundRevocationSummary(
    val checked: Int,
    val newlyRevoked: Int,
    val failed: Boolean,
) {
    override fun toString(): String =
        "checked=$checked newlyRevoked=$newlyRevoked${if (failed) " (failed)" else ""}"
}

/**
 * Refreshes every document's revocation status — iOS's counterpart of Android's `RevocationWorkManager`,
 * which `Application` enqueues every 15 minutes through WorkManager.
 *
 * ## Why this function exists at all
 *
 * The mechanism has worked since `650e8c1e` and is covered by twelve tests. **Nothing called it.** The
 * comment on [IosWalletEngine.refreshRevocationStatuses] claimed "the host decides for now: at launch,
 * on foreground, or from a pull-to-refresh" — and no host did any of those, so
 * `WalletEngine.getRevokedDocumentIds` answered from a table that was never written and a revoked
 * credential kept displaying as valid. A mechanism with no trigger is not a shipped feature.
 *
 * ## Two triggers, because one is not enough
 *
 *  - **At launch**, once per process, from the app shell. This is the trigger that can be relied on: it
 *    guarantees the status is no older than the current session whenever the user is actually looking at
 *    their documents.
 *  - **From the `BGProcessingTask`** that already tops up credentials. Opportunistic — `BGTaskScheduler`
 *    may leave a task unrun for days — but it is the only path that runs with the app closed, which is
 *    what Android's worker gives. Pairing the two sweeps on one task is also what the official iOS
 *    wallet does, which starts a revocation worker beside its re-issuance one.
 *
 * The earlier reasoning for staying off that task — that every check would "spend a request per document
 * to conclude `Unknown`" until the trust gate opens — **expired with `650e8c1e`**: a status list is now
 * read and acted on under `INFORM` without an anchor, so a sweep reaches a real verdict.
 *
 * ## What it deliberately does not do
 *
 * No notification, and no deletion. Android's worker broadcasts; here the Documents screen re-reads the
 * store when it appears, so a newly flagged document surfaces on its own. Flagging is all that happens —
 * the flag/clear decision itself is `revocationAction` in commonMain, which both platforms call.
 *
 * Failure is reported rather than thrown: one unreachable status endpoint must not make the whole sweep
 * look like a crash to `BGTaskScheduler`, which weighs completion when deciding whether to grant time
 * again. Cancellation *is* rethrown, because that is iOS asking for its time back.
 */
suspend fun runBackgroundRevocation(): BackgroundRevocationSummary {
    val engine = IosWalletEngine()
    var checked = 0

    return try {
        val newlyRevoked = engine.refreshRevocationStatuses { documentId, outcome ->
            checked++
            Logger.i(TAG, "$documentId -> $outcome")
        }
        BackgroundRevocationSummary(
            checked = checked,
            newlyRevoked = newlyRevoked.size,
            failed = false,
        )
    } catch (cancelled: CancellationException) {
        Logger.i(TAG, "sweep cancelled after $checked document(s)")
        throw cancelled
    } catch (error: Throwable) {
        Logger.e(TAG, "revocation sweep failed after $checked document(s)", error)
        BackgroundRevocationSummary(checked = checked, newlyRevoked = 0, failed = true)
    }.also { Logger.i(TAG, "background revocation: $it") }
}

private const val TAG = "IosBackgroundRevocation"
