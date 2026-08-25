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
 * What a background sweep did, in a shape Swift can report to the system.
 *
 * `refreshed` is documents whose credentials were actually topped up; a document that was due and then
 * failed counts as [failed], not as a silent success — `BGTaskScheduler` decides whether to keep giving
 * the app time partly on whether its work completes, so pretending is counterproductive.
 */
data class BackgroundReIssuanceSummary(
    val considered: Int,
    val due: Int,
    val refreshed: Int,
    val failed: Int,
) {
    val didWork: Boolean get() = refreshed > 0

    override fun toString(): String =
        "considered=$considered due=$due refreshed=$refreshed failed=$failed"
}

/**
 * Tops up the credentials of every document whose policy says it is running out — iOS's counterpart of
 * Android's `ReIssuanceWorkManager`, which `Application` enqueues every 15 minutes through WorkManager.
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
    var due = 0
    var refreshed = 0
    var failed = 0

    for (document in documents) {
        val stored = document.toStoredDocument() ?: continue
        if (!stored.isDueForReIssuance(now)) continue
        due++

        try {
            val progress = engine.refreshCredentials(document.identifier)
            if (progress is IosIssuanceProgress.Issued && progress.credentialsFetched > 0) {
                refreshed++
                Logger.i(
                    TAG,
                    "re-issued ${progress.credentialsFetched} credential(s) for ${document.identifier}"
                )
            } else {
                Logger.i(TAG, "nothing fetched for ${document.identifier}: $progress")
            }
        } catch (cancelled: CancellationException) {
            // iOS asked for the time back. Stop where we are rather than burning the expiration
            // handler's grace period on another network round-trip.
            Logger.i(TAG, "sweep cancelled after $refreshed refresh(es)")
            throw cancelled
        } catch (error: Throwable) {
            failed++
            Logger.e(TAG, "re-issuance failed for ${document.identifier}", error)
        }
    }

    return BackgroundReIssuanceSummary(
        considered = documents.size,
        due = due,
        refreshed = refreshed,
        failed = failed,
    ).also { Logger.i(TAG, "background re-issuance: $it") }
}

private const val TAG = "IosBackgroundReIssuance"
