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

import eu.europa.ec.corelogic.model.RevokedDocumentDataDomain
import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.shared.platform.IosBroadcasts
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.shared.platform.platformAction
import eu.europa.ec.shared.platform.platformStringListExtra
import eu.europa.ec.shared.wallet.WalletDocument
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the iOS sweeps broadcast, held to what Android's two workers send for the same outcome. */
class IosBroadcastsTest {

    private val revoked = listOf(
        WalletDocument(id = "doc-1", name = "PID"),
        WalletDocument(id = "doc-2", name = "mDL"),
    )

    private fun reIssuance(refreshed: Int, failed: Int = 0) = BackgroundReIssuanceSummary(
        considered = 3,
        due = refreshed + failed,
        refreshed = refreshed,
        failed = failed,
        unchanged = 0,
    )

    //region revocation

    @Test
    fun a_revocation_sweep_that_changed_nothing_broadcasts_nothing() {
        val sent = revocationBroadcasts(RevocationRefresh(newlyRevoked = emptyList(), cleared = emptyList()))

        assertTrue(sent.isEmpty())
    }

    @Test
    fun newly_revoked_documents_send_the_message_the_details_and_the_list_refresh_in_androids_order() {
        val sent = revocationBroadcasts(RevocationRefresh(newlyRevoked = revoked, cleared = emptyList()))

        assertEquals(
            listOf(
                CoreActions.REVOCATION_WORK_MESSAGE_ACTION,
                CoreActions.REVOCATION_WORK_REFRESH_DETAILS_ACTION,
                CoreActions.REVOCATION_WORK_REFRESH_ACTION,
            ),
            sent.map { it.platformAction() },
        )
        // The details screen reads the ids under the details extra, not the message's.
        assertEquals(
            listOf("doc-1", "doc-2"),
            sent[1].platformStringListExtra(CoreActions.REVOCATION_IDS_DETAILS_EXTRA),
        )
    }

    @Test
    fun a_document_valid_again_sends_only_the_list_refresh() {
        // Android refreshes the list on a clear too, but tells the user nothing: nothing was revoked.
        val sent = revocationBroadcasts(RevocationRefresh(newlyRevoked = emptyList(), cleared = listOf("doc-1")))

        assertEquals(listOf(CoreActions.REVOCATION_WORK_REFRESH_ACTION), sent.map { it.platformAction() })
    }

    @Test
    fun the_dashboard_reads_back_the_names_and_ids_the_message_carries() {
        val message = revocationBroadcasts(RevocationRefresh(newlyRevoked = revoked, cleared = emptyList())).first()

        assertEquals(
            listOf(
                RevokedDocumentDataDomain(name = "PID", id = "doc-1"),
                RevokedDocumentDataDomain(name = "mDL", id = "doc-2"),
            ),
            revokedDocumentsInBroadcast(message),
        )
    }

    @Test
    fun a_broadcast_that_is_not_the_message_reads_as_no_revoked_documents() {
        // Null, not empty: an empty list would still raise the dashboard's event, with nothing in it.
        val listRefresh = revocationBroadcasts(RevocationRefresh(newlyRevoked = revoked, cleared = emptyList())).last()

        assertNull(revokedDocumentsInBroadcast(listRefresh))
    }

    @Test
    fun names_and_ids_that_do_not_pair_up_read_as_no_revoked_documents() {
        val malformed = PlatformIntent(
            action = CoreActions.REVOCATION_WORK_MESSAGE_ACTION,
            stringListExtras = mapOf(
                CoreActions.REVOCATION_IDS_EXTRA to listOf("doc-1", "doc-2"),
                "revocation.names.extra" to listOf("PID"),
            ),
        )

        assertNull(revokedDocumentsInBroadcast(malformed))
    }

    @Test
    fun a_listening_dashboard_gets_the_revoked_documents_through_the_bus() = runTest {
        val received = mutableListOf<PlatformIntent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            IosBroadcasts.receive(listOf(CoreActions.REVOCATION_WORK_MESSAGE_ACTION)).toList(received)
        }

        announce(revocationBroadcasts(RevocationRefresh(newlyRevoked = revoked, cleared = emptyList())), "test")

        assertEquals(listOf("doc-1", "doc-2"), received.single().let(::revokedDocumentsInBroadcast)?.map { it.id })
    }

    //endregion

    //region re-issuance

    @Test
    fun a_re_issuance_that_refreshed_something_sends_the_list_refresh_and_never_the_details_one() {
        // The details broadcast would close a screen whose document iOS kept: a top-up is in place.
        val sent = reIssuanceBroadcasts(reIssuance(refreshed = 1, failed = 1))

        assertEquals(listOf(CoreActions.RE_ISSUANCE_WORK_REFRESH_ACTION), sent.map { it.platformAction() })
    }

    @Test
    fun a_re_issuance_that_refreshed_nothing_broadcasts_nothing() {
        // Android's condition is the same: something was actually re-issued.
        assertTrue(reIssuanceBroadcasts(reIssuance(refreshed = 0, failed = 2)).isEmpty())
    }

    //endregion
}
