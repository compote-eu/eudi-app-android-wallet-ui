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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * That a background sweep reports what actually happened.
 *
 * This is the regression test for a defect found by **running the task on a device** rather than by
 * reading the code: a document that was due, was attempted, and came back
 * `Failure(This wallet no longer has a connection to the issuer…)` was counted in neither `refreshed`
 * nor `failed`, because the call site asked only whether credentials had been fetched and let
 * everything else fall into an `else`. The device log read `due=1 refreshed=0 failed=0` — which is what
 * a wallet with nothing to do looks like.
 *
 * The class KDoc had promised the opposite the whole time ("a document that was due and then failed
 * counts as failed, not as a silent success"), so the code contradicted its own documentation.
 */
class IosBackgroundReIssuanceTest {

    @Test
    fun a_refusal_from_the_issuer_counts_as_failed() {
        // Verbatim the shape observed on the iPhone SE on 2026-09-04.
        val progress = IosIssuanceProgress.Failure(
            message = "This wallet no longer has a connection to the issuer that provided this document.",
        )

        assertEquals(RefreshOutcome.Failed, refreshOutcomeOf(progress))
    }

    @Test
    fun credentials_actually_fetched_count_as_refreshed() {
        val progress = IosIssuanceProgress.Issued(documentIds = listOf("doc-1"), credentialsFetched = 3)

        assertEquals(RefreshOutcome.Refreshed, refreshOutcomeOf(progress))
    }

    @Test
    fun a_batch_that_needed_nothing_is_neither_refreshed_nor_failed() {
        // The benign case, and the reason a blanket `else -> Failed` would have been wrong: a policy
        // can leave a document due while the issuer has nothing new to hand over.
        val progress = IosIssuanceProgress.Issued(documentIds = listOf("doc-1"), credentialsFetched = 0)

        assertEquals(RefreshOutcome.NothingToFetch, refreshOutcomeOf(progress))
    }

    @Test
    fun nothing_fetched_with_failures_recorded_counts_as_failed() {
        // `Issued` carries a `failures` map and its KDoc leaves the verdict to the caller. Zero
        // credentials *and* named failures is a failure by any reading.
        val progress = IosIssuanceProgress.Issued(
            documentIds = emptyList(),
            failures = mapOf("pid-config" to "the issuer refused"),
            credentialsFetched = 0,
        )

        assertEquals(RefreshOutcome.Failed, refreshOutcomeOf(progress))
    }

    @Test
    fun a_partial_batch_still_counts_as_refreshed() {
        // Deliberate: work happened, so the sweep must not tell iOS the run failed. Reversing this
        // would make one bad configuration suppress the app's whole scheduling budget.
        val progress = IosIssuanceProgress.Issued(
            documentIds = listOf("doc-1"),
            failures = mapOf("second-config" to "timed out"),
            credentialsFetched = 1,
        )

        assertEquals(RefreshOutcome.Refreshed, refreshOutcomeOf(progress))
    }

    @Test
    fun the_device_scenario_no_longer_reports_zero_failures() {
        // Two documents in the store, one due, its refresh refused: what the iPhone actually did.
        // Classified rather than hand-fed, so this reproduces the device scenario end to end. Passing
        // `RefreshOutcome.Failed` in directly would test only the arithmetic and would have gone on
        // passing with the original defect in place — which is how it behaved until this line changed.
        val refused = IosIssuanceProgress.Failure(
            message = "This wallet no longer has a connection to the issuer that provided this document.",
        )
        val summary = summarize(considered = 2, outcomes = listOf(refreshOutcomeOf(refused)))

        assertEquals(2, summary.considered)
        assertEquals(1, summary.due)
        assertEquals(0, summary.refreshed)
        assertEquals(1, summary.failed, "a refused refresh must not vanish from the summary")
        assertFalse(summary.didWork)
    }

    @Test
    fun every_due_document_lands_in_exactly_one_bucket() {
        val summary = summarize(
            considered = 9,
            outcomes = listOf(
                RefreshOutcome.Refreshed,
                RefreshOutcome.Refreshed,
                RefreshOutcome.Failed,
                RefreshOutcome.NothingToFetch,
            ),
        )

        assertEquals(4, summary.due)
        assertEquals(2, summary.refreshed)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.unchanged)
        assertTrue(summary.accountedFor, "the buckets must add up to the number of due documents")
    }

    @Test
    fun a_sweep_with_nothing_due_is_clean_rather_than_failed() {
        // What the completion signal now reads: `failed == 0` has to stay true for an idle sweep, or
        // every quiet run would tell iOS the app is broken.
        val summary = summarize(considered = 4, outcomes = emptyList())

        assertEquals(0, summary.due)
        assertEquals(0, summary.failed)
        assertTrue(summary.accountedFor)
    }

    @Test
    fun the_summary_prints_the_bucket_that_used_to_be_missing() {
        // The log line is the only place this reaches a human, so the count belongs in it.
        val text = summarize(considered = 1, outcomes = listOf(RefreshOutcome.NothingToFetch)).toString()

        assertTrue(text.contains("unchanged=1"), "the summary line hid the unchanged bucket: $text")
    }
}
