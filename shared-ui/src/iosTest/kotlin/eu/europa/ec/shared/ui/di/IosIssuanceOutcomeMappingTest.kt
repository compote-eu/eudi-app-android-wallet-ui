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

import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.shared.wallet.multipaz.IosIssuanceProgress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which screen an iOS issuance ends on when the issuer deferred something — the one Android's
 * `WalletCoreDocumentsController` picks: "it will come later" (`DeferredSuccess`) for a wallet-initiated
 * issuance only when nothing was issued at once, for an offer as soon as anything was deferred.
 */
class IosIssuanceOutcomeMappingTest {

    private val mdl = "org.iso.18013.5.1.mDL"
    private val pid = "eu.europa.ec.eudi.pid.1"

    // ---- add document (prioritizeDeferred = false) -------------------------------------------------

    @Test
    fun a_wallet_initiated_issuance_that_was_only_deferred_says_it_will_come_later() {
        val outcome = IosIssuanceProgress.Issued(documentIds = listOf("parked"), deferred = mapOf("parked" to mdl))

        assertEquals(IssueDocumentsPartialState.DeferredSuccess(mapOf("parked" to mdl)), outcome.toAddDocumentState())
    }

    @Test
    fun a_wallet_initiated_issuance_with_one_document_issued_at_once_is_a_success() {
        val outcome = IosIssuanceProgress.Issued(
            documentIds = listOf("issued", "parked"),
            deferred = mapOf("parked" to pid),
        )

        assertEquals(IssueDocumentsPartialState.Success(listOf("issued", "parked")), outcome.toAddDocumentState())
    }

    @Test
    fun a_wallet_initiated_issuance_deferred_after_a_failure_still_says_it_will_come_later() {
        // Android decides on what was issued at once, which is nothing here, whatever else failed.
        val outcome = IosIssuanceProgress.Issued(
            documentIds = listOf("parked"),
            failures = mapOf("second" to "refused"),
            deferred = mapOf("parked" to mdl),
        )

        assertEquals(IssueDocumentsPartialState.DeferredSuccess(mapOf("parked" to mdl)), outcome.toAddDocumentState())
    }

    @Test
    fun an_untrusted_refusal_is_reported_before_a_deferral() {
        val outcome = IosIssuanceProgress.Issued(
            documentIds = listOf("parked"),
            failures = mapOf("second" to "not trusted"),
            untrusted = setOf("second"),
            deferred = mapOf("parked" to mdl),
        )

        assertEquals(
            IssueDocumentsPartialState.PartialSuccessWithUntrustedIssuer(
                issuedDocumentIds = listOf("parked"),
                untrustedDocuments = mapOf("second" to "not trusted"),
            ),
            outcome.toAddDocumentState(),
        )
        assertEquals(outcome.toAddDocumentState(), outcome.toOfferState())
    }

    @Test
    fun a_wallet_initiated_issuance_without_deferrals_is_unchanged() {
        assertEquals(
            IssueDocumentsPartialState.Success(listOf("a")),
            IosIssuanceProgress.Issued(documentIds = listOf("a")).toAddDocumentState(),
        )
        assertEquals(
            IssueDocumentsPartialState.PartialSuccess(listOf("a"), mapOf("b" to "failed")),
            IosIssuanceProgress.Issued(documentIds = listOf("a"), failures = mapOf("b" to "failed")).toAddDocumentState(),
        )
    }

    // ---- offer (prioritizeDeferred = true) ---------------------------------------------------------

    @Test
    fun an_offer_with_anything_deferred_says_it_will_come_later_even_beside_an_issued_document() {
        val outcome = IosIssuanceProgress.Issued(
            documentIds = listOf("issued", "parked"),
            deferred = mapOf("parked" to pid),
        )

        assertEquals(IssueDocumentsPartialState.DeferredSuccess(mapOf("parked" to pid)), outcome.toOfferState())
    }

    @Test
    fun an_offer_with_nothing_deferred_is_a_success() {
        assertEquals(
            IssueDocumentsPartialState.Success(listOf("issued")),
            IosIssuanceProgress.Issued(documentIds = listOf("issued")).toOfferState(),
        )
    }
}
