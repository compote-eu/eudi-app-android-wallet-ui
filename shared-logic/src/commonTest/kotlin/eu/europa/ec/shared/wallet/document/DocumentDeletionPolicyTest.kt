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

// Runs on the JVM *and* on Kotlin/Native, which is the point: the rule used to be written once per
// platform, and the two had drifted. A commonTest is what makes "both platforms agree" checked rather
// than intended — the same reasoning as `RevocationPolicyTest`.
package eu.europa.ec.shared.wallet.document

import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentDeletionPolicyTest {

    // ---- documentDeletionScope: the details screen, deciding what to delete -------------------

    @Test
    fun a_build_that_does_not_force_pid_activation_never_wipes_the_wallet() {
        // Every build of either platform is this one today, so this row is the shipping behaviour:
        // deleting the main PID from its details screen removes that document and nothing else.
        assertEquals(
            DocumentDeletionScope.SingleDocument,
            documentDeletionScope(
                forcePidActivation = false,
                deletedDocumentIsPid = true,
                pidDocumentCount = 1,
                deletedDocumentIsMainPid = true,
            ),
        )
    }

    @Test
    fun deleting_a_non_pid_never_wipes_the_wallet() {
        assertEquals(
            DocumentDeletionScope.SingleDocument,
            documentDeletionScope(
                forcePidActivation = true,
                deletedDocumentIsPid = false,
                pidDocumentCount = 3,
                deletedDocumentIsMainPid = false,
            ),
        )
    }

    @Test
    fun deleting_the_only_pid_wipes_the_wallet() {
        // Nothing else can be relied on once the PID everything hangs off is gone.
        assertEquals(
            DocumentDeletionScope.WholeWallet,
            documentDeletionScope(
                forcePidActivation = true,
                deletedDocumentIsPid = true,
                pidDocumentCount = 1,
                deletedDocumentIsMainPid = true,
            ),
        )
    }

    @Test
    fun the_only_pid_wipes_the_wallet_even_if_it_is_not_flagged_as_the_main_one() {
        // Android reached this through `else true` rather than by checking: with one PID, that PID is
        // the main one whatever `getMainPidDocument()` happens to answer — including null, which it
        // does return. Keeping the shape means the port is faithful, not merely plausible.
        assertEquals(
            DocumentDeletionScope.WholeWallet,
            documentDeletionScope(
                forcePidActivation = true,
                deletedDocumentIsPid = true,
                pidDocumentCount = 1,
                deletedDocumentIsMainPid = false,
            ),
        )
    }

    @Test
    fun deleting_the_main_pid_of_several_wipes_the_wallet() {
        assertEquals(
            DocumentDeletionScope.WholeWallet,
            documentDeletionScope(
                forcePidActivation = true,
                deletedDocumentIsPid = true,
                pidDocumentCount = 2,
                deletedDocumentIsMainPid = true,
            ),
        )
    }

    @Test
    fun deleting_a_spare_pid_leaves_the_wallet_alone() {
        // The wallet still holds the PID it depends on, so there is nothing to reset.
        assertEquals(
            DocumentDeletionScope.SingleDocument,
            documentDeletionScope(
                forcePidActivation = true,
                deletedDocumentIsPid = true,
                pidDocumentCount = 2,
                deletedDocumentIsMainPid = false,
            ),
        )
    }

    // ---- documentDeletionOutcome: the documents list, describing what happened ----------------

    @Test
    fun emptying_a_pid_requiring_wallet_from_the_list_returns_the_user_to_the_start() {
        assertEquals(
            DocumentDeletionScope.WholeWallet,
            documentDeletionOutcome(forcePidActivation = true, walletIsEmptyAfterDeletion = true),
        )
    }

    @Test
    fun a_wallet_that_still_holds_something_just_pops_back() {
        assertEquals(
            DocumentDeletionScope.SingleDocument,
            documentDeletionOutcome(forcePidActivation = true, walletIsEmptyAfterDeletion = false),
        )
    }

    @Test
    fun emptying_a_wallet_that_does_not_require_a_pid_is_an_ordinary_deletion() {
        // The shipping configuration again, and the case iOS used to get wrong on the *details*
        // screen: it reported the wallet emptied whenever the last document went, ignoring the flag,
        // which threw the user out to the lock screen where Android simply popped back to the list.
        assertEquals(
            DocumentDeletionScope.SingleDocument,
            documentDeletionOutcome(forcePidActivation = false, walletIsEmptyAfterDeletion = true),
        )
    }
}
