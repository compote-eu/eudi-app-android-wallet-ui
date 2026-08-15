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
import eu.europa.ec.commonfeature.extension.toSelectiveExpandableListItems
import eu.europa.ec.commonfeature.ui.request.model.DocumentFormatDomain
import eu.europa.ec.commonfeature.ui.request.model.DocumentPayloadDomain
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.commonfeature.ui.request.model.RequestDocumentItemUi
import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.ClaimItemId
import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.corelogic.model.PresentationMatchDomain
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
import eu.europa.ec.shared.resources.request_collapsed_supporting_text
import eu.europa.ec.shared.wallet.multipaz.IosProximityClaimRef
import eu.europa.ec.shared.wallet.multipaz.IosProximityDisclosure
import eu.europa.ec.shared.wallet.multipaz.IosProximityPresenter
import eu.europa.ec.shared.wallet.multipaz.IosProximityRequest
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
 * It is also where multipaz's request becomes the app's consent model and back again. The presenter
 * deliberately speaks neither `RequestCombinationUi` nor multipaz types across that seam — it publishes
 * [IosProximityRequest] and takes [IosProximityDisclosure] — so this translation is the only place the
 * two vocabularies meet.
 */
internal class IosProximityCoordinator(
    private val presenter: IosProximityPresenter,
    private val strings: StringCatalog,
) {

    /** The user's current answer, in the presenter's terms. Read when the loading screen sends. */
    private var disclosures: List<IosProximityDisclosure> = emptyList()

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
            IosProximityDisclosure(
                documentId = document.match.documentId,
                credentialId = document.match.credentialId,
                claims = document.payload.docClaimsDomain.map { it.path.toClaimRef() }.toSet(),
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

    private fun IosProximityRequest.toPartialState(): ProximityRequestInteractorPartialState {
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
                // row really does keep it out of the mdoc — see the presenter's `toSelection`.
                claimsAreSelectable = true,
            )
        }
    }

    //endregion
}

// The two translations between multipaz's request and the shared consent model, at file level because
// neither touches the coordinator's state — and because out here they can be tested. Nothing else about
// proximity can be, on a platform whose simulator has no Bluetooth radio, and the round trip is exactly
// where a silent mismatch would cost the most: a claim whose row id no longer matches its path is a
// claim the user ticks and the wallet never sends.

/** What the request screen renders: one card per document, in one card set per alternative. */
internal fun IosProximityRequest.toCombinationsUi(strings: StringCatalog): List<RequestCombinationUi> =
    combinations
        .map { combination ->
            RequestCombinationUi(
                documents = combination.documents.map { it.toItemUi(strings) },
                matches = combination.documents.map { document ->
                    PresentationMatchDomain(
                        documentId = document.documentId,
                        credentialId = document.credentialId,
                        // DCQL only; ISO 18013-5 has no query ids, and proximity is 18013-5.
                        queryId = null,
                        requestedClaims = document.claims.map { it.claim.toPath() },
                    )
                },
            )
        }
        .filter { it.documents.isNotEmpty() }

/**
 * The inverse: the cards as the user left them, back to the credentials and claims they kept.
 *
 * A document with nothing ticked is dropped rather than sent empty, which is also how the presenter
 * reads an empty answer. Ticked rows are matched to stored claims by re-encoding each claim's own path,
 * never by parsing the row id — the same rule `RequestTransformer` follows on Android, and for the same
 * reason: the id format is [ClaimItemId]'s business.
 */
internal fun RequestCombinationUi.keptDocuments(): List<KeptDocument> {
    val matchesByDocument = matches.associateBy { it.documentId }

    return documents.mapNotNull { document ->
        val match = matchesByDocument[document.domainPayload.docId] ?: return@mapNotNull null
        val claims = document.keptClaims()
        if (claims.isEmpty()) {
            null
        } else {
            KeptDocument(
                match = match,
                // Narrowed here rather than by the caller: a "kept document" carrying every claim the
                // card offered would be a trap for whoever reads it next.
                payload = document.domainPayload.copy(docClaimsDomain = claims),
            )
        }
    }
}

/** One document the user is about to share, holding only the claims they left ticked. */
internal data class KeptDocument(
    val match: PresentationMatchDomain,
    val payload: DocumentPayloadDomain,
)

private fun RequestDocumentItemUi.keptClaims(): List<ClaimDomain.Primitive> {
    val checkedIds = headerUi.nestedItems.flatMap { it.checkedItemIds() }.toSet()

    return domainPayload.docClaimsDomain.filterIsInstance<ClaimDomain.Primitive>()
        .filter { claim ->
            ClaimItemId.Claim(
                docId = domainPayload.docId,
                queryId = domainPayload.queryId,
                path = claim.path,
            ).encode() in checkedIds
        }
}

private fun ExpandableListItemUi.checkedItemIds(): List<String> = when (this) {
    is ExpandableListItemUi.SingleListItem -> {
        val checkbox = header.trailingContentData as? ListItemTrailingContentDataUi.Checkbox
        if (checkbox?.checkboxData?.isChecked == true) listOf(header.itemId) else emptyList()
    }

    is ExpandableListItemUi.NestedListItem -> nestedItems.flatMap { it.checkedItemIds() }
}

private fun IosProximityRequest.RequestedDocument.toItemUi(
    strings: StringCatalog,
): RequestDocumentItemUi {
    val payload = DocumentPayloadDomain(
        docName = documentName,
        docId = documentId,
        docFormatDomain = DocumentFormatDomain.MsoMdoc,
        docClaimsDomain = claims.map { it.toClaimDomain() },
        queryId = null,
    )

    return RequestDocumentItemUi(
        domainPayload = payload,
        headerUi = ExpandableListItemUi.NestedListItem(
            header = ListItemDataUi(
                itemId = ClaimItemId.DocumentHeader(docId = documentId, queryId = null).encode(),
                mainContentData = ListItemMainContentDataUi.Text(text = documentName),
                supportingText = strings[Res.string.request_collapsed_supporting_text],
                trailingContentData = ListItemTrailingContentDataUi.Icon(
                    iconData = AppIcons.KeyboardArrowDown,
                ),
            ),
            nestedItems = payload.toSelectiveExpandableListItems(),
            isExpanded = false,
        ),
    )
}

/**
 * One row per requested data element.
 *
 * `isRequired` is false even when the reader set `intentToRetain`: retention is what the reader intends
 * to do with the data, not permission the wallet has already given. Marking it required would disable
 * the checkbox and force-share exactly the claims a user is most likely to refuse.
 */
private fun IosProximityRequest.RequestedClaimInfo.toClaimDomain() = ClaimDomain.Primitive(
    key = claim.identifier,
    displayTitle = displayName,
    path = claim.toPath(),
    value = value,
    isRequired = false,
)

/**
 * The claim's path, carrying the namespace verbatim so [toClaimRef] can reverse it exactly. An mdoc
 * claim always has a namespace; substituting a document type for a missing one would produce a path that
 * no longer names the claim the presenter offered, and the disclosure would silently drop it.
 */
internal fun IosProximityClaimRef.toPath() = ClaimPathDomain(
    segments = listOf(ClaimPathSegment.Key(identifier)),
    type = ClaimType.MsoMdoc(namespace = namespace.orEmpty()),
)

internal fun ClaimPathDomain.toClaimRef() = IosProximityClaimRef(
    namespace = (type as? ClaimType.MsoMdoc)?.namespace?.ifEmpty { null },
    identifier = segments.filterIsInstance<ClaimPathSegment.Key>().joinToString(".") { it.name },
)
