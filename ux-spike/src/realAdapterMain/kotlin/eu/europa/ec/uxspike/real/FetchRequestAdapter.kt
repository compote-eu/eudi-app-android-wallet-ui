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

package eu.europa.ec.uxspike.real

import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.corelogic.model.RegistrationStatusDomain
import eu.europa.ec.corelogic.model.RelyingPartyDomain
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractorPartialState
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.ux.contract.pres.FetchRequestResult
import eu.europa.ec.ux.contract.pres.RequestAttributePayload
import eu.europa.ec.ux.contract.pres.RequestPayload
import eu.europa.ec.ux.contract.pres.Requirement
import eu.europa.ec.ux.contract.pres.Trust
import eu.europa.ec.ux.contract.pres.VerifierPayload
import kotlinx.coroutines.flow.firstOrNull

/** What [fetchRequestFrom] hands back: the typed result and what [send][InteractorPresCrossDeviceServices.send] needs. */
internal class Fetched(
    val result: FetchRequestResult,
    val combination: RequestCombinationUi? = null,
    val claimsAreSelectable: Boolean = false,
)

/**
 * `fetchRequest`: `setConfig` with the link (as the app's dashboard does for a deep link), then the first event of
 * `getRequestDocuments`, mapped. The app's own screen keeps collecting; the contract wants one answer.
 */
internal suspend fun fetchRequestFrom(
    request: PresentationRequestInteractor,
    uri: String,
    log: (String) -> Unit,
): Fetched {
    request.setConfig(RequestUriConfig(PresentationMode.OpenId4Vp(uri = uri, initiatorRoute = DashboardRoute)), intentAction = null)
    val first = request.getRequestDocuments().firstOrNull()
        ?: return Fetched(FetchRequestResult.Error(reason = AdapterGaps.NO_REQUEST_EVENT))
    return mapRequestState(first, log)
}

/** One partial state of the real request interactor as a typed contract result (the M11b mapping table). */
internal fun mapRequestState(state: PresentationRequestInteractorPartialState, log: (String) -> Unit): Fetched = when (state) {
    is PresentationRequestInteractorPartialState.Success -> mapSuccess(state, log)

    // Lossy: the real NoData names no document, and the Android interactor also folds REVOKED documents into it,
    // so DocInvalid has no source (G-DOC).
    is PresentationRequestInteractorPartialState.NoData -> {
        log("GAP fetchRequest: NoData -> DocMissing without document_name (revoked documents are folded in too)")
        Fetched(FetchRequestResult.DocMissing(documentName = null))
    }

    // Lossy: an untyped message. Expired cannot be told apart from any other failure (G-EXP).
    is PresentationRequestInteractorPartialState.Failure -> {
        log("GAP fetchRequest: untyped Failure(${state.error}) -> Error; Expired is not derivable")
        Fetched(FetchRequestResult.Error(reason = state.error))
    }

    // Lossy: the app's hard block carries no request, and RpUntrusted needs one (G-RP).
    is PresentationRequestInteractorPartialState.VerifierNotTrusted -> {
        log("GAP fetchRequest: VerifierNotTrusted (a block with no request) -> Error, not RpUntrusted")
        Fetched(FetchRequestResult.Error(reason = AdapterGaps.VERIFIER_NOT_TRUSTED))
    }

    is PresentationRequestInteractorPartialState.Disconnect -> {
        log("GAP fetchRequest: Disconnect -> Error (the contract has no 'ended before asking' result)")
        Fetched(FetchRequestResult.Error(reason = AdapterGaps.DISCONNECTED))
    }
}

private fun mapSuccess(state: PresentationRequestInteractorPartialState.Success, log: (String) -> Unit): Fetched {
    val combination = state.combinationsUi.firstOrNull()
        ?: return Fetched(FetchRequestResult.DocMissing(documentName = null))
    if (state.combinationsUi.size > 1) {
        log("GAP fetchRequest: ${state.combinationsUi.size} alternative combinations; the contract has one request, the first is used")
    }
    if (combination.documents.size > 1) {
        log("GAP fetchRequest: ${combination.documents.size} documents; the contract has one document_name, the first is used")
    }
    val request = RequestPayload(
        attributes = combination.attributes(state.claimsAreSelectable),
        documentName = combination.documents.firstOrNull()?.domainPayload?.docName,
    )
    val verifier = state.relyingParty.toVerifierPayload()
    val hasMandatory = request.attributes.any { it.requirement == Requirement.MANDATORY }
    // The app has no per-claim "required" (`ClaimDomain.isRequired` is false on every path), so the requirement is
    // the request's own `claimsAreSelectable`: all optional, or all mandatory (G-REQ).
    log("GAP fetchRequest: requirement derived from claimsAreSelectable=${state.claimsAreSelectable}; label_copy carries the claim's display title, not a copy key")
    val result = when {
        !state.relyingParty.isFullyVerified -> {
            if (!hasMandatory) log("GAP fetchRequest: RpUntrusted with no mandatory item breaks RUL-pres-request-has-mandatory")
            FetchRequestResult.RpUntrusted(request, verifier)
        }

        hasMandatory -> FetchRequestResult.RequestReady(request, verifier)
        else -> FetchRequestResult.OptionalOnly(request, verifier)
    }
    return Fetched(result, combination, state.claimsAreSelectable)
}

/**
 * The claims of every document, as the request screen shows them: each leaf row of the card's tree. The id is the
 * row's own `itemId` (the app's `ClaimItemId` encoding), so [withSelection] can tick it back.
 */
internal fun RequestCombinationUi.attributes(claimsAreSelectable: Boolean): List<RequestAttributePayload> =
    documents.flatMap { document -> document.headerUi.nestedItems.flatMap { it.leaves() } }.map { row ->
        val header = row.header
        val main = (header.mainContentData as? ListItemMainContentDataUi.Text)?.text
        val checkbox = (header.trailingContentData as? ListItemTrailingContentDataUi.Checkbox)?.checkboxData
        val optional = claimsAreSelectable && checkbox != null && checkbox.enabled
        RequestAttributePayload(
            id = header.itemId,
            // The contract types this as a copy KEY; the app has only a resolved display title (G-LABEL).
            labelCopy = header.overlineText ?: main ?: header.itemId,
            requirement = if (optional) Requirement.OPTIONAL else Requirement.MANDATORY,
            // RUL-pres-request-*-unticked: optional items arrive unticked. The app's screen starts with every row ticked.
            selected = false,
            // An image claim (portrait) has no text value (G-VALUE).
            value = main.takeIf { header.overlineText != null },
        )
    }

private fun ExpandableListItemUi.leaves(): List<ExpandableListItemUi.SingleListItem> = when (this) {
    is ExpandableListItemUi.SingleListItem -> listOf(this)
    is ExpandableListItemUi.NestedListItem -> nestedItems.flatMap { it.leaves() }
}

/** The verifier as the contract wants it, from the app's relying party (both trust layers: `isFullyVerified`). */
internal fun RelyingPartyDomain.toVerifierPayload(): VerifierPayload {
    val details = when (val r = registration) {
        is RegistrationStatusDomain.Verified -> r.details
        is RegistrationStatusDomain.NotVerified -> r.details
        RegistrationStatusDomain.NotEvaluated -> null
    }
    return VerifierPayload(
        name = name,
        privacyUrl = details?.privacyPolicyUrl,
        reason = details?.intendedUse,
        serviceName = details?.serviceDescription,
        trust = if (isFullyVerified) Trust.TRUSTED else Trust.UNTRUSTED,
    )
}
