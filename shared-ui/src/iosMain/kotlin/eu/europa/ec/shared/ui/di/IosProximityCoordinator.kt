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

import eu.europa.ec.commonfeature.extension.toExpandableListItems
import eu.europa.ec.commonfeature.ui.request.model.DocumentPayloadDomain
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.corelogic.model.ClaimItemId
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingObserveResponsePartialState
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingSendRequestedDocumentPartialState
import eu.europa.ec.proximityfeature.interactor.ProximityQRPartialState
import eu.europa.ec.proximityfeature.interactor.ProximityRequestInteractorPartialState
import eu.europa.ec.proximityfeature.interactor.ProximitySuccessInteractorGetUiItemsPartialState
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.asUiTextOr
import eu.europa.ec.shared.resources.document_success_collapsed_supporting_text
import eu.europa.ec.shared.resources.document_success_header_description
import eu.europa.ec.shared.resources.document_success_header_description_when_error
import eu.europa.ec.shared.resources.document_success_relying_party_default_name
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentDisclosure
import eu.europa.ec.shared.wallet.multipaz.IosProximityPresenter
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentRequest
import eu.europa.ec.shared.wallet.multipaz.IosProximityState
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.RelyingPartyDataUi
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * The single object the four proximity screens on iOS talk to.
 *
 * The screens are the shared ones, so each asks its own interactor a narrow question — "what is the QR",
 * "what was asked for", "did it send", "what went out" — but all four questions are about *one*
 * exchange. Android answers them from a `WalletCorePresentationController` held in a Koin presentation
 * scope; iOS answers them from one [IosProximityPresenter] and the two pieces of state that outlive a
 * single screen: what the user agreed to release, and who asked.
 *
 * The presenter deliberately speaks neither `RequestCombinationUi` nor multipaz types across that seam —
 * it publishes [IosPresentmentRequest] and takes [IosPresentmentDisclosure]. Turning the one into the
 * other is [toCombinationsUi]/[keptDocuments], which live at file level in
 * `IosPresentmentConsentMapping` because the same translation serves any presentation protocol rather
 * than this one in particular.
 */
internal class IosProximityCoordinator(
    private val presenter: IosProximityPresenter,
    private val strings: StringCatalog,
) {

    /** The user's current answer, in the presenter's terms. Read when the loading screen sends. */
    private var disclosures: List<IosPresentmentDisclosure> = emptyList()

    /** The same answer in the UI's terms, so the success screen can list what actually went out. */
    private var disclosed: List<DocumentPayloadDomain> = emptyList()

    private var verifierName: String? = null
    private var verifierIsTrusted: Boolean = false

    /** Whether the app is sending, which is what makes a return to [IosProximityState.Idle] a failure. */
    private var sending: Boolean = false

    //region The QR screen

    /**
     * Starts advertising, then reports what happens to it.
     *
     * Engagement runs *before* the state is collected on purpose. The presenter publishes a StateFlow, so
     * subscribing afterwards still sees where things got to — while subscribing first would replay the
     * previous attempt's outcome, and a retry would flash the old error before the new QR appeared.
     */
    fun qrEvents(): Flow<ProximityQRPartialState> = flow {
        presenter.startQrEngagement()

        presenter.state.collect { state ->
            when (state) {
                is IosProximityState.Engaging ->
                    emit(ProximityQRPartialState.QrReady(qrCode = state.qrPayload))

                is IosProximityState.Requesting ->
                    emit(ProximityQRPartialState.Connected)

                is IosProximityState.Failed ->
                    emit(ProximityQRPartialState.Error(error = state.message))

                // Engagement has already been started, so this is the end of it: the reader went away,
                // or something cancelled the exchange.
                is IosProximityState.Idle ->
                    emit(ProximityQRPartialState.Disconnected)

                // Both belong to the loading screen, which is collecting by then.
                is IosProximityState.Sending, is IosProximityState.Sent -> Unit
            }
        }
    }

    //endregion

    //region The request screen

    fun requestEvents(): Flow<ProximityRequestInteractorPartialState> =
        presenter.state.mapNotNull { state ->
            when (state) {
                is IosProximityState.Requesting -> state.request.toPartialState()

                is IosProximityState.Failed ->
                    ProximityRequestInteractorPartialState.Failure(error = state.message)

                // The reader went away, or the user backed out of the QR screen.
                is IosProximityState.Idle -> ProximityRequestInteractorPartialState.Disconnect

                is IosProximityState.Engaging,
                is IosProximityState.Sending,
                is IosProximityState.Sent,
                    -> null
            }
        }

    /**
     * Remembers what the user has kept, every time they tick or untick a claim.
     *
     * Two forms of the same answer: [disclosures] is what the presenter will act on, [disclosed] is what
     * the success screen will show. Deriving the second here rather than re-deriving it later is what
     * keeps the two from disagreeing.
     */
    fun updateSelection(selectedCombination: RequestCombinationUi?) {
        val kept = selectedCombination?.keptDocuments().orEmpty()

        disclosures = kept.map { document ->
            IosPresentmentDisclosure(
                documentId = document.match.documentId,
                credentialId = document.match.credentialId,
                claims = document.payload.docClaimsDomain.map { it.path }.toSet(),
            )
        }
        disclosed = kept.map { it.payload }
    }

    //endregion

    //region The loading screen

    /**
     * What the send is doing.
     *
     * The first emission is [ProximityLoadingObserveResponsePartialState.RequestReadyToBeSent], because
     * by the time this screen exists the user has already consented on the previous one — that state is
     * the view-model's cue to call [send]. iOS never reports `UserAuthenticationRequired`: multipaz's
     * `SecureEnclaveSecureArea` raises its own LocalAuthentication prompt when it signs, so there is no
     * separate prompt for the app to schedule.
     */
    fun sendEvents(): Flow<ProximityLoadingObserveResponsePartialState> = flow {
        presenter.state.collect { state ->
            when (state) {
                is IosProximityState.Requesting ->
                    emit(ProximityLoadingObserveResponsePartialState.RequestReadyToBeSent)

                is IosProximityState.Sending -> sending = true

                is IosProximityState.Sent ->
                    emit(ProximityLoadingObserveResponsePartialState.Success)

                is IosProximityState.Failed ->
                    emit(ProximityLoadingObserveResponsePartialState.Failure(error = state.message))

                // Idle mid-send means the exchange ended without a response — a dropped connection, or
                // a refusal the presenter turned into one. Before the send it is simply not our turn.
                is IosProximityState.Idle ->
                    if (sending) {
                        emit(
                            ProximityLoadingObserveResponsePartialState.Failure(
                                error = strings[Res.string.generic_error_message],
                            )
                        )
                    }

                is IosProximityState.Engaging -> Unit
            }
        }
    }

    /**
     * Hands the user's answer to the presenter. Success here means "sent to multipaz", not "the reader
     * has it" — the wire outcome arrives through [sendEvents], exactly as on Android.
     */
    fun send(): ProximityLoadingSendRequestedDocumentPartialState {
        if (disclosures.isEmpty()) {
            return ProximityLoadingSendRequestedDocumentPartialState.Failure(
                error = strings[Res.string.generic_error_message],
            )
        }

        presenter.accept(disclosures)
        return ProximityLoadingSendRequestedDocumentPartialState.Success
    }

    //endregion

    //region The success screen

    /** What was released, listed from the answer the user gave rather than from the response. */
    fun successItems(): ProximitySuccessInteractorGetUiItemsPartialState {
        val documentsUi = disclosed.map { payload ->
            ExpandableListItemUi.NestedListItem(
                header = ListItemDataUi(
                    itemId = ClaimItemId.DocumentHeader(
                        docId = payload.docId,
                        queryId = payload.queryId,
                    ).encode(),
                    mainContentData = ListItemMainContentDataUi.Text(text = payload.docName),
                    supportingText = strings[Res.string.document_success_collapsed_supporting_text],
                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                        iconData = AppIcons.KeyboardArrowDown,
                    ),
                ),
                nestedItems = payload.toExpandableListItems(),
                isExpanded = false,
            )
        }

        return ProximitySuccessInteractorGetUiItemsPartialState.Success(
            documentsUi = documentsUi,
            headerConfig = ContentHeaderConfig(
                description = UiText.Resource(
                    if (documentsUi.isEmpty()) {
                        Res.string.document_success_header_description_when_error
                    } else {
                        Res.string.document_success_header_description
                    }
                ),
                relyingPartyData = RelyingPartyDataUi(
                    name = verifierName.asUiTextOr(
                        fallback = UiText.Resource(Res.string.document_success_relying_party_default_name),
                    ),
                    isVerified = verifierIsTrusted,
                ),
            ),
        )
    }

    //endregion

    /** Ends the exchange: the back button, the "stop" the request screen offers, and every teardown. */
    fun cancel() {
        sending = false
        disclosures = emptyList()
        disclosed = emptyList()
        presenter.cancel()
    }

    //region multipaz's request -> the shared consent model

    private fun IosPresentmentRequest.toPartialState(): ProximityRequestInteractorPartialState {
        verifierName = requesterName
        verifierIsTrusted = requesterIsTrusted

        val combinationsUi = toCombinationsUi(strings)

        return if (combinationsUi.isEmpty()) {
            ProximityRequestInteractorPartialState.NoData(
                verifierName = requesterName,
                verifierIsTrusted = requesterIsTrusted,
            )
        } else {
            ProximityRequestInteractorPartialState.Success(
                verifierName = requesterName,
                verifierIsTrusted = requesterIsTrusted,
                combinationsUi = combinationsUi,
                // multipaz builds the response from the claims the selection carries, so unticking a
                // row really does keep it out of the mdoc — see `CredentialPresentmentData.toSelection`.
                claimsAreSelectable = true,
            )
        }
    }

    //endregion
}
