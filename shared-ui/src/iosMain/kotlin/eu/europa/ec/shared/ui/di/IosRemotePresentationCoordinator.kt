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

import eu.europa.ec.shared.resources.document_success_banner_text
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.corelogic.model.RelyingPartyDomain
import eu.europa.ec.corelogic.model.RegistrationStatusDomain
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.extension.toExpandableListItems
import eu.europa.ec.commonfeature.ui.request.model.DocumentPayloadDomain
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.corelogic.model.ClaimItemId
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingObserveResponsePartialState
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingSendRequestedDocumentPartialState
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractorPartialState
import eu.europa.ec.presentationfeature.interactor.PresentationSuccessInteractorGetUiItemsPartialState
import eu.europa.ec.shared.navigation.AppRouteCodec
import eu.europa.ec.shared.navigation.DashboardRoute
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
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentRequest
import eu.europa.ec.shared.wallet.multipaz.IosRemotePresentationState
import eu.europa.ec.shared.wallet.multipaz.IosRemotePresenter
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemSupportingContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.RelyingPartyDataUi
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * The single object the three remote-presentation screens on iOS talk to.
 *
 * The proximity twin is [IosProximityCoordinator] and the shape is deliberately identical: the screens
 * are the shared ones, so each asks its own interactor a narrow question — "what was asked for", "did it
 * send", "what went out" — but all three questions are about *one* exchange. Android answers them from a
 * `WalletCorePresentationController` held in a Koin presentation scope; iOS answers them from one
 * [IosRemotePresenter] and the state that outlives a single screen.
 *
 * There is no QR screen here, which is the one structural difference: an OpenID4VP link arrives already
 * carrying the verifier's address, so the flow starts on the request screen.
 */
internal class IosRemotePresentationCoordinator(
    private val presenter: IosRemotePresenter,
    private val strings: StringCatalog,
) {

    /** The user's current answer, in the presenter's terms. Read when the loading screen sends. */
    private var disclosures: List<IosPresentmentDisclosure> = emptyList()

    /** The same answer in the UI's terms, so the success screen can list what actually went out. */
    private var disclosed: List<DocumentPayloadDomain> = emptyList()

    private var verifierName: String? = null
    private var verifierIsTrusted: Boolean = false

    /** Whether the app is sending, which is what makes a return to [IosRemotePresentationState.Idle] a failure. */
    private var sending: Boolean = false

    /** Where the verifier asked the user to be sent afterwards, read by the success screen. */
    var redirectUri: String? = null
        private set

    /**
     * The screen this presentation started from, as the encoded token the success view-model decodes.
     *
     * The contract types it as a `String` because Android's is one — it travels through `:core-logic`,
     * which must not depend on the UI layer, so [AppRouteCodec] makes it opaque there. iOS has no such
     * seam, but the *reader* is the shared view-model, which decodes it — so it has to be encoded here
     * rather than handed over as some other string that would silently decode to null.
     */
    var initiatorRoute: String = AppRouteCodec.encode(DashboardRoute)
        private set

    //region The request screen

    /**
     * Starts the exchange the link describes, then reports what happens to it.
     *
     * The URI is taken from the config rather than from a field of this class, because the config is
     * what the route carries: a link the app was opened with reaches the request screen as
     * [PresentationMode.OpenId4Vp], and everything downstream follows from it.
     */
    fun start(config: RequestUriConfig) {
        val mode = config.mode
        if (mode !is PresentationMode.OpenId4Vp) return
        redirectUri = null
        initiatorRoute = AppRouteCodec.encode(mode.initiatorRoute)
        presenter.start(mode.uri)
    }

    fun requestEvents(): Flow<PresentationRequestInteractorPartialState> =
        presenter.state.mapNotNull { state ->
            when (state) {
                is IosRemotePresentationState.Requesting -> state.request.toPartialState()

                is IosRemotePresentationState.Failed ->
                    PresentationRequestInteractorPartialState.Failure(error = state.message)

                // The user backed out, or the exchange was abandoned before anything was asked.
                is IosRemotePresentationState.Idle ->
                    PresentationRequestInteractorPartialState.Disconnect

                // Resolving is the gap between the link and the request; the screen shows its own
                // spinner meanwhile, and there is nothing truer to say than nothing.
                is IosRemotePresentationState.Resolving,
                is IosRemotePresentationState.Sending,
                is IosRemotePresentationState.Sent,
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
     * The first emission is [PresentationLoadingObserveResponsePartialState.RequestReadyToBeSent],
     * because by the time this screen exists the user has already consented on the previous one — that
     * state is the view-model's cue to call [send]. iOS never reports `UserAuthenticationRequired`:
     * multipaz's `SecureEnclaveSecureArea` raises its own LocalAuthentication prompt when it signs, so
     * there is no separate prompt for the app to schedule.
     */
    fun sendEvents(): Flow<PresentationLoadingObserveResponsePartialState> = flow {
        presenter.state.collect { state ->
            when (state) {
                is IosRemotePresentationState.Requesting ->
                    emit(PresentationLoadingObserveResponsePartialState.RequestReadyToBeSent)

                is IosRemotePresentationState.Sending -> sending = true

                is IosRemotePresentationState.Sent -> {
                    redirectUri = state.redirectUri
                    emit(PresentationLoadingObserveResponsePartialState.Success)
                }

                is IosRemotePresentationState.Failed ->
                    emit(PresentationLoadingObserveResponsePartialState.Failure(error = state.message))

                // Idle mid-send means the exchange ended without a response — the verifier went away, or
                // a refusal the presenter turned into one. Before the send it is simply not our turn.
                is IosRemotePresentationState.Idle ->
                    if (sending) {
                        emit(
                            PresentationLoadingObserveResponsePartialState.Failure(
                                error = strings[Res.string.generic_error_message],
                            )
                        )
                    }

                is IosRemotePresentationState.Resolving -> Unit
            }
        }
    }

    /**
     * Hands the user's answer to the presenter. Success here means "sent to multipaz", not "the verifier
     * has it" — the wire outcome arrives through [sendEvents], exactly as on Android.
     */
    fun send(): PresentationLoadingSendRequestedDocumentPartialState {
        if (disclosures.isEmpty()) {
            return PresentationLoadingSendRequestedDocumentPartialState.Failure(
                error = strings[Res.string.generic_error_message],
            )
        }

        presenter.accept(disclosures)
        return PresentationLoadingSendRequestedDocumentPartialState.Success
    }

    //endregion

    //region The success screen

    /** What was released, listed from the answer the user gave rather than from the response. */
    fun successItems(): PresentationSuccessInteractorGetUiItemsPartialState {
        val documentsUi = disclosed.map { payload ->
            ExpandableListItemUi.NestedListItem(
                header = ListItemDataUi(
                    itemId = ClaimItemId.DocumentHeader(
                        docId = payload.docId,
                        queryId = payload.queryId,
                    ).encode(),
                    mainContentData = ListItemMainContentDataUi.Text(text = payload.docName),
                    supportingContentData = ListItemSupportingContentDataUi.Text(
                        text = strings[Res.string.document_success_collapsed_supporting_text],
                    ),
                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                        iconData = AppIcons.KeyboardArrowDown,
                    ),
                ),
                nestedItems = payload.toExpandableListItems(),
                isExpanded = false,
            )
        }

        return PresentationSuccessInteractorGetUiItemsPartialState.Success(
            documentsUi = documentsUi,
            bannerText = UiText.Resource(Res.string.document_success_banner_text),
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

    private fun IosPresentmentRequest.toPartialState(): PresentationRequestInteractorPartialState {
        verifierName = requesterName
        verifierIsTrusted = requesterIsTrusted

        val combinationsUi = toCombinationsUi(strings)

        return if (combinationsUi.isEmpty()) {
            PresentationRequestInteractorPartialState.NoData(
                relyingParty = relyingPartyDomain(),
            )
        } else {
            PresentationRequestInteractorPartialState.Success(
                relyingParty = relyingPartyDomain(),
                combinationsUi = combinationsUi,
                // multipaz builds the response from the claims the selection carries, so unticking a
                // row really does keep it out of the response — see `CredentialPresentmentData.toSelection`.
                claimsAreSelectable = true,
            )
        }
    }

    //endregion
}

/**
 * The requester as iOS knows it. [RegistrationStatusDomain.NotEvaluated] is the honest value, not a
 * placeholder: the issuer and relying-party registration policies live in
 * `eudi-lib-android-wallet-core`, which has no iOS counterpart and no multipaz equivalent, so no
 * registration certificate is ever evaluated here. `hasTrustedAccessCertificate` follows what
 * multipaz reports, which is false today because `resolveTrustFn` is never supplied.
 */
private fun IosPresentmentRequest.relyingPartyDomain(): RelyingPartyDomain = RelyingPartyDomain(
    name = requesterName,
    uniqueId = null,
    hasTrustedAccessCertificate = requesterIsTrusted,
    logoUri = null,
    registration = RegistrationStatusDomain.NotEvaluated,
)
