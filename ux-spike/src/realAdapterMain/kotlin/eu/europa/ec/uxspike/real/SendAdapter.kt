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

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.corelogic.model.AuthenticationData
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingObserveResponsePartialState as Observed
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingSendRequestedDocumentPartialState
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractor
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.ux.contract.pres.SendResult
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * `send`: the selection ticked back into the card (`updateRequestedDocuments`), then the loading interactor in the
 * order the app's loading screen runs it: observe, send on `RequestReadyToBeSent`, authenticate per document on
 * `UserAuthenticationRequired` (Android), and the first terminal state is the result. The loading interactor joins
 * the request interactor's Koin scope first (Android: that scope holds the exchange's controller).
 */
@Suppress("LongParameterList")
internal suspend fun sendWith(
    request: PresentationRequestInteractor,
    loading: PresentationLoadingInteractor,
    combination: RequestCombinationUi,
    selection: List<String>,
    claimsAreSelectable: Boolean,
    context: PlatformContext?,
    log: (String) -> Unit,
): SendResult {
    request.updateRequestedDocuments(combination.withSelection(selection.toSet(), claimsAreSelectable))
    loading.setScopeId(request.presentationScopeId)
    var result: SendResult? = null
    loading.observeResponse().firstOrNull { state ->
        result = when (state) {
            Observed.RequestReadyToBeSent -> sendOnce(loading)
            is Observed.UserAuthenticationRequired ->
                if (authenticateAll(loading, state.authenticationData, context, log)) sendOnce(loading)
                else SendResult.Error(reason = AdapterGaps.DEVICE_AUTH_FAILED)
            Observed.Success -> SendResult.Sent
            // Lossy: the verifier's redirect is dropped; the contract's Sent has no field for it (G-REDIRECT).
            is Observed.Redirect -> SendResult.Sent.also { log("GAP send: Redirect(${state.uri}) -> Sent, the redirect is dropped") }
            // Untyped: Expired cannot be told apart (G-EXP).
            is Observed.Failure -> SendResult.Error(reason = state.error).also { log("GAP send: untyped Failure -> Error; Expired is not derivable") }
            is Observed.Rejected -> SendResult.Error(reason = AdapterGaps.REJECTED_WHILE_SENDING)
            // The DC-API hand-off (Android) is another flow; this one is OpenID4VP cross-device.
            is Observed.IntentToSend -> SendResult.Error(reason = AdapterGaps.DC_API_INTENT)
        }
        result != null
    }
    return result ?: SendResult.Error(reason = AdapterGaps.NO_RESPONSE_EVENT)
}

/** Null while the response is on its way (the terminal state follows on the same stream), an error otherwise. */
private suspend fun sendOnce(loading: PresentationLoadingInteractor): SendResult? =
    when (val sent = loading.sendRequestedDocuments()) {
        PresentationLoadingSendRequestedDocumentPartialState.Success -> null
        is PresentationLoadingSendRequestedDocumentPartialState.Failure -> SendResult.Error(reason = sent.error)
    }

/**
 * Android's per-document key prompt, as the loading screen raises it. It needs the host activity: with none (iOS never
 * asks; a host that passes none) it fails honestly. This is a SECOND prompt after `authenticate` (G-AUTH-ORDER).
 */
private suspend fun authenticateAll(
    loading: PresentationLoadingInteractor,
    data: List<AuthenticationData>,
    context: PlatformContext?,
    log: (String) -> Unit,
): Boolean {
    log("GAP send: UserAuthenticationRequired x${data.size}; a second prompt after authenticate")
    if (context == null || data.isEmpty()) return false
    return data.all { item ->
        suspendCancellableCoroutine { continuation ->
            loading.handleUserAuthentication(
                context = context,
                crypto = item.crypto,
                notifyOnAuthenticationFailure = true,
                resultHandler = DeviceAuthenticationResult(
                    onAuthenticationSuccess = {
                        item.onAuthenticationSuccess()
                        if (continuation.isActive) continuation.resume(true)
                    },
                    onAuthenticationError = { if (continuation.isActive) continuation.resume(false) },
                    onAuthenticationFailure = { if (continuation.isActive) continuation.resume(false) },
                ),
            )
        }
    }
}

/**
 * The card with the rows of [selection] ticked and every other selectable row unticked; a row the user cannot untick
 * (mandatory: not selectable, or a disabled checkbox) stays as the app built it.
 */
internal fun RequestCombinationUi.withSelection(selection: Set<String>, claimsAreSelectable: Boolean): RequestCombinationUi =
    copy(
        documents = documents.map { document ->
            document.copy(
                headerUi = document.headerUi.copy(
                    nestedItems = document.headerUi.nestedItems.map { it.ticked(selection, claimsAreSelectable) },
                ),
            )
        },
    )

private fun ExpandableListItemUi.ticked(selection: Set<String>, claimsAreSelectable: Boolean): ExpandableListItemUi = when (this) {
    is ExpandableListItemUi.NestedListItem -> copy(nestedItems = nestedItems.map { it.ticked(selection, claimsAreSelectable) })
    is ExpandableListItemUi.SingleListItem -> {
        val box = header.trailingContentData as? ListItemTrailingContentDataUi.Checkbox
        if (box == null || !box.checkboxData.enabled || !claimsAreSelectable) {
            this
        } else {
            copy(
                header = header.copy(
                    trailingContentData = box.copy(checkboxData = box.checkboxData.copy(isChecked = header.itemId in selection)),
                ),
            )
        }
    }
}
