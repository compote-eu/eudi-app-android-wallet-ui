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

import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractorPartialState
import eu.europa.ec.presentationfeature.interactor.PresentationSuccessInteractorGetUiItemsPartialState
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentFormat
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentRequest
import eu.europa.ec.shared.wallet.multipaz.IosRemotePresentationState
import eu.europa.ec.shared.wallet.multipaz.IosRemotePresenter
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PID_NAMESPACE = "eu.europa.ec.eudi.pid.1"

/**
 * A remote (OpenID4VP) request offers no claim selection on iOS, as on Android.
 *
 * 🚨 Measured on an iPhone 2026-10-19: the request screen let the user untick claims, and the wallet sent
 * them anyway. multipaz 0.101.0's `OpenID4VP.generateResponse` builds the response from the DCQL query's
 * claims, not from the selection we narrow — so an untick changed the screen and nothing else. Android's
 * `WalletCorePresentationController.requestAllowsClaimSelection` is false for DCQL requests, and these
 * cases hold iOS to the same rule: read-only rows, and every requested claim kept.
 */
class IosRemoteClaimSelectionTest {

    private fun coordinator(): IosRemotePresentationCoordinator {
        val presenter = IosRemotePresenter(walletEngine = IosWalletEngine(), isRegistrationCheckEnabled = { false })
        return IosRemotePresentationCoordinator(presenter, KeyStringCatalog)
    }

    private fun claim(identifier: String) = IosPresentmentRequest.RequestedClaimInfo(
        claim = ClaimPathDomain(
            segments = listOf(ClaimPathSegment.Key(identifier)),
            type = ClaimType.MsoMdoc(namespace = PID_NAMESPACE),
        ),
        displayName = identifier,
        value = identifier,
        intentToRetain = false,
    )

    private val requestedClaims = listOf("family_name", "given_name", "birth_date")

    /** The DCQL request of the 2026-10-19 measurement: one PID, three claims, no `claim_sets`. */
    private val request = IosPresentmentRequest(
        requesterName = "Verifier",
        requesterIsTrusted = true,
        combinations = listOf(
            IosPresentmentRequest.Combination(
                documents = listOf(
                    IosPresentmentRequest.RequestedDocument(
                        documentId = "doc-1",
                        credentialId = "cred-1",
                        documentName = "PID",
                        docType = PID_NAMESPACE,
                        format = IosPresentmentFormat.MsoMdoc,
                        queryId = "pid",
                        claims = requestedClaims.map(::claim),
                    ),
                ),
            ),
        ),
    )

    private fun IosRemotePresentationCoordinator.consent(): PresentationRequestInteractorPartialState.Success =
        assertIs<PresentationRequestInteractorPartialState.Success>(
            requestEventFor(IosRemotePresentationState.Requesting(request)),
        )

    private fun ExpandableListItemUi.leaves(): List<ExpandableListItemUi.SingleListItem> = when (this) {
        is ExpandableListItemUi.SingleListItem -> listOf(this)
        is ExpandableListItemUi.NestedListItem -> nestedItems.flatMap { it.leaves() }
    }

    @Test
    fun a_remote_request_offers_no_claim_selection() {
        assertFalse(
            coordinator().consent().claimsAreSelectable,
            "the response is built from the query, so a selection on screen would not reach it",
        )
    }

    @Test
    fun a_remote_request_draws_its_claims_without_checkboxes() {
        val leaves = coordinator().consent().combinationsUi.single()
            .documents.single().headerUi.nestedItems.flatMap { it.leaves() }

        assertEquals(requestedClaims.size, leaves.size)
        assertTrue(
            leaves.none { it.header.trailingContentData is ListItemTrailingContentDataUi.Checkbox },
            "a checkbox here would offer a choice the response ignores",
        )
    }

    @Test
    fun a_remote_answer_keeps_every_requested_claim() {
        val coordinator = coordinator()
        coordinator.updateSelection(coordinator.consent().combinationsUi.single())

        val shared = assertIs<PresentationSuccessInteractorGetUiItemsPartialState.Success>(coordinator.successItems())
            .documentsUi.single().nestedItems.flatMap { it.leaves() }

        // Read-only rows carry no ticks, so "what the user kept" cannot come from ticks here: reading it
        // that way would keep nothing, and the wallet would decline a request the user agreed to.
        assertEquals(requestedClaims.size, shared.size)
    }
}

/** Resolves to the resource's own key: these cases are about structure, not wording. */
private object KeyStringCatalog : StringCatalog {
    override fun get(resource: StringResource): String = resource.key
    override fun get(resource: StringResource, vararg args: Any): String = resource.key
    override suspend fun warm() = Unit
}
