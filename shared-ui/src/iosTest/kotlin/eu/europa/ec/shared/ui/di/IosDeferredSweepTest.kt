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

import eu.europa.ec.dashboardfeature.interactor.DocumentInteractorRetryIssuingDeferredDocumentsPartialState
import eu.europa.ec.shared.wallet.multipaz.DeferredCollection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How the iOS documents bridge reports a deferred sweep — the layer between the collector and the
 * screen, and the one a unit test can reach while the screen itself cannot be tapped on this machine.
 *
 * Each assertion here comes from something watched on the simulator on 2026-09-17: a collected
 * document arrived at the "ready" sheet with **no name**, and a document that could never be completed
 * was left in the wallet to churn yellow→red on every launch.
 */
class IosDeferredSweepTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private fun bridge(
        outcomes: Map<String, DeferredCollection>,
        names: Map<String, String> = emptyMap(),
        awaiting: Map<String, String> = emptyMap(),
        deleted: MutableList<String> = mutableListOf(),
    ): Pair<IosDocumentsPlatformBridge, MutableList<String>> =
        IosDocumentsPlatformBridge(
            deleteDocument = { documentId -> deleted += documentId; Result.success(Unit) },
            hasAnyDocument = { true },
            collectDeferred = { documentId ->
                outcomes[documentId] ?: DeferredCollection.Failed("not configured")
            },
            documentNames = { names },
            awaitingDeferred = { awaiting },
        ) to deleted

    @Test
    fun a_collected_document_is_announced_with_its_name() = runTest(dispatcher) {
        val (bridge, _) = bridge(
            outcomes = mapOf("doc-1" to DeferredCollection.Issued(listOf("credential"))),
            names = mapOf("doc-1" to "PID (MSO MDoc)"),
        )

        val result = bridge
            .tryIssuingDeferredDocuments(mapOf("doc-1" to "eu.europa.ec.eudi.pid.1"), dispatcher)
            .first()

        assertIs<DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result>(result)
        val announced = result.successfullyIssuedDeferredDocuments.single()
        assertEquals("doc-1", announced.documentId)
        // 🪤 The name came from the *cheap* document list, which carries ids and nothing else, so every
        // collected document reached the sheet blank. A blank name also prints as `[]` in a log, which
        // is how it hid — watched on the simulator before this was fixed.
        assertEquals("PID (MSO MDoc)", announced.docName)
    }

    @Test
    fun a_document_that_can_never_be_completed_is_deleted_not_just_marked() = runTest(dispatcher) {
        val (bridge, deleted) = bridge(
            outcomes = mapOf(
                "expired" to DeferredCollection.AuthorizationExpired,
                "abandoned" to DeferredCollection.Abandoned,
            ),
        )

        val result = bridge.tryIssuingDeferredDocuments(
            mapOf("expired" to "type", "abandoned" to "type"),
            dispatcher,
        ).first()

        assertIs<DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result>(result)
        assertEquals(setOf("expired", "abandoned"), result.failedIssuedDeferredDocuments.toSet())
        // The failed marking is transient UI state, so without the delete the document comes back as
        // pending on the next launch and fails again, for ever. Android deletes on the same outcomes.
        assertEquals(setOf("expired", "abandoned"), deleted.toSet())
    }

    @Test
    fun a_document_the_issuer_is_still_working_on_is_left_alone_entirely() = runTest(dispatcher) {
        val (bridge, deleted) = bridge(
            outcomes = mapOf("pending" to DeferredCollection.StillPending(retryAfterSeconds = 48)),
        )

        val result = bridge
            .tryIssuingDeferredDocuments(mapOf("pending" to "type"), dispatcher)
            .first()

        assertIs<DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result>(result)
        // Neither issued nor failed: the document stays exactly as it is and the next sweep asks again.
        assertTrue(result.successfullyIssuedDeferredDocuments.isEmpty())
        assertTrue(result.failedIssuedDeferredDocuments.isEmpty())
        assertTrue(deleted.isEmpty(), "a document still being minted must never be deleted")
        assertEquals(48, result.retryAfterSeconds)
    }

    @Test
    fun a_transport_failure_is_not_treated_as_a_dead_document() = runTest(dispatcher) {
        val (bridge, deleted) = bridge(
            outcomes = mapOf("doc-1" to DeferredCollection.Failed("the network blinked")),
        )

        val result = bridge
            .tryIssuingDeferredDocuments(mapOf("doc-1" to "type"), dispatcher)
            .first()

        assertIs<DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result>(result)
        // Deleting someone's document because a request failed would be far worse than waiting.
        assertTrue(result.failedIssuedDeferredDocuments.isEmpty())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun a_deferred_refresh_is_swept_even_though_the_screen_never_offers_it() = runTest(dispatcher) {
        val (bridge, _) = bridge(
            outcomes = mapOf("topped-up" to DeferredCollection.Issued(listOf("credential"))),
            names = mapOf("topped-up" to "PID (MSO MDoc)"),
            // The document is still `Issued` — its existing credentials work — so the screen's
            // "Pending" rule cannot see it. Only the store knows it carries a handle.
            awaiting = mapOf("topped-up" to "eu.europa.ec.eudi.pid.1"),
        )

        val result = bridge
            .tryIssuingDeferredDocuments(deferredDocuments = emptyMap(), dispatcher)
            .first()

        assertIs<DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result>(result)
        val announced = result.successfullyIssuedDeferredDocuments.single()
        assertEquals("topped-up", announced.documentId)
        assertEquals("eu.europa.ec.eudi.pid.1", announced.formatType)
    }

    @Test
    fun the_screens_own_format_type_wins_where_both_sets_name_the_same_document() = runTest(dispatcher) {
        val (bridge, _) = bridge(
            outcomes = mapOf("doc-1" to DeferredCollection.Issued(listOf("credential"))),
            awaiting = mapOf("doc-1" to "from-the-store"),
        )

        val result = bridge
            .tryIssuingDeferredDocuments(mapOf("doc-1" to "from-the-screen"), dispatcher)
            .first()

        assertIs<DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result>(result)
        // Swept once, not twice, and described the way the caller described it.
        assertEquals(1, result.successfullyIssuedDeferredDocuments.size)
        assertEquals("from-the-screen", result.successfullyIssuedDeferredDocuments.single().formatType)
    }
}
