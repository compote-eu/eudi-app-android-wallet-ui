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

// snake_case names because this also runs on Kotlin/Native, which forbids spaces in test names.
package eu.europa.ec.shared.wallet.revocation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The point of this suite is *where it runs*: `commonTest` compiles into both the JVM test binary and
 * the Kotlin/Native one, so every assertion here is executed once per platform. That is what makes
 * "both platforms behave the same" a checked property rather than an intention.
 */
class RevocationPolicyTest {

    private fun action(
        status: DocumentStatusDomain,
        trust: StatusSignerTrustDomain,
        policy: StatusTrustPolicyDomain,
        flagged: Boolean = false,
    ) = revocationAction(status, trust, policy, flagged)

    //region what the status means once trust is out of the way

    @Test
    fun a_trusted_invalid_reading_flags_a_document_that_is_not_flagged_yet() {
        assertEquals(
            RevocationActionDomain.Flag,
            action(DocumentStatusDomain.Invalid, StatusSignerTrustDomain.Trusted, StatusTrustPolicyDomain.Enforce),
        )
    }

    @Test
    fun suspended_is_treated_exactly_like_invalid() {
        assertEquals(
            RevocationActionDomain.Flag,
            action(DocumentStatusDomain.Suspended, StatusSignerTrustDomain.Trusted, StatusTrustPolicyDomain.Enforce),
        )
    }

    @Test
    fun an_already_flagged_document_is_left_alone_rather_than_reflagged() {
        // Re-flagging would re-fire the user-facing "documents revoked" notification every sweep.
        assertEquals(
            RevocationActionDomain.Leave,
            action(
                DocumentStatusDomain.Invalid,
                StatusSignerTrustDomain.Trusted,
                StatusTrustPolicyDomain.Enforce,
                flagged = true,
            ),
        )
    }

    @Test
    fun a_trusted_valid_reading_clears_an_existing_flag() {
        // Revocation is not a one-way door on either platform.
        assertEquals(
            RevocationActionDomain.Clear,
            action(
                DocumentStatusDomain.Valid,
                StatusSignerTrustDomain.Trusted,
                StatusTrustPolicyDomain.Enforce,
                flagged = true,
            ),
        )
    }

    @Test
    fun a_valid_reading_for_an_unflagged_document_changes_nothing() {
        assertEquals(
            RevocationActionDomain.Leave,
            action(DocumentStatusDomain.Valid, StatusSignerTrustDomain.Trusted, StatusTrustPolicyDomain.Enforce),
        )
    }

    @Test
    fun an_unknown_status_never_decides_anything_in_either_direction() {
        for (flagged in listOf(false, true)) {
            for (policy in StatusTrustPolicyDomain.entries) {
                assertEquals(
                    RevocationActionDomain.Leave,
                    action(DocumentStatusDomain.Unknown, StatusSignerTrustDomain.Trusted, policy, flagged),
                    "Unknown must never act (flagged=$flagged, policy=$policy)",
                )
            }
        }
    }

    //endregion

    //region what the policy does with a signer that could not be trusted

    @Test
    fun enforce_ignores_a_reading_with_no_anchors_to_check_against() {
        // iOS's permanent state. wallet-core throws StatusListNotTrustedException for the same case
        // and the Android worker swallows it, which lands on the same outcome.
        for (status in DocumentStatusDomain.entries) {
            for (flagged in listOf(false, true)) {
                assertEquals(
                    RevocationActionDomain.Leave,
                    action(status, StatusSignerTrustDomain.NoAnchorsAvailable, StatusTrustPolicyDomain.Enforce, flagged),
                    "Enforce must not act on an unanchored $status (flagged=$flagged)",
                )
            }
        }
    }

    @Test
    fun enforce_ignores_a_chain_that_failed_against_anchors_that_do_exist() {
        for (flagged in listOf(false, true)) {
            assertEquals(
                RevocationActionDomain.Leave,
                action(DocumentStatusDomain.Invalid, StatusSignerTrustDomain.NotTrusted, StatusTrustPolicyDomain.Enforce, flagged),
            )
        }
    }

    @Test
    fun enforce_is_not_satisfied_by_a_platform_that_could_not_evaluate_trust() {
        // Unreachable today — a platform that enforces has by definition evaluated — but a policy
        // demanding proof must not be satisfied by the absence of it.
        assertEquals(
            RevocationActionDomain.Leave,
            action(DocumentStatusDomain.Invalid, StatusSignerTrustDomain.NotEvaluated, StatusTrustPolicyDomain.Enforce),
        )
    }

    @Test
    fun inform_lets_the_status_decide_whatever_the_signer_trust_was() {
        // wallet-core computes the trust result under INFORM and then discards it, so every trust
        // value has to reach the same action. This is what keeps Android's behaviour unchanged.
        for (trust in StatusSignerTrustDomain.entries) {
            assertEquals(
                RevocationActionDomain.Flag,
                action(DocumentStatusDomain.Invalid, trust, StatusTrustPolicyDomain.Inform),
                "Inform must act on Invalid regardless of trust ($trust)",
            )
            assertEquals(
                RevocationActionDomain.Clear,
                action(DocumentStatusDomain.Valid, trust, StatusTrustPolicyDomain.Inform, flagged = true),
                "Inform must clear on Valid regardless of trust ($trust)",
            )
        }
    }

    //endregion

    //region the two properties each platform's engine relies on

    @Test
    fun under_enforce_an_unanchored_valid_can_never_clear_a_revocation() {
        // The dangerous direction: if it could, answering the status URL would be enough to
        // un-revoke a credential, which is the whole purpose of verifying the list's signature.
        assertEquals(
            RevocationActionDomain.Clear,
            action(DocumentStatusDomain.Valid, StatusSignerTrustDomain.Trusted, StatusTrustPolicyDomain.Enforce, flagged = true),
        )
        for (trust in listOf(
            StatusSignerTrustDomain.NoAnchorsAvailable,
            StatusSignerTrustDomain.NotTrusted,
            StatusSignerTrustDomain.NotEvaluated,
        )) {
            assertEquals(
                RevocationActionDomain.Leave,
                action(DocumentStatusDomain.Valid, trust, StatusTrustPolicyDomain.Enforce, flagged = true),
                "an untrusted Valid must not un-revoke ($trust)",
            )
        }
    }

    @Test
    fun the_policy_is_total_so_neither_engine_can_meet_an_unhandled_combination() {
        // Every engine calls this with library-derived values; a gap would surface as a silent
        // "Leave" in production rather than a compile error, so the whole cross product is walked.
        var seen = 0
        for (status in DocumentStatusDomain.entries) {
            for (trust in StatusSignerTrustDomain.entries) {
                for (policy in StatusTrustPolicyDomain.entries) {
                    for (flagged in listOf(false, true)) {
                        revocationAction(status, trust, policy, flagged)
                        seen++
                    }
                }
            }
        }
        assertEquals(
            DocumentStatusDomain.entries.size *
                StatusSignerTrustDomain.entries.size *
                StatusTrustPolicyDomain.entries.size * 2,
            seen,
        )
    }

    //endregion
}
