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

// Phase 3b: the second feature view-model in commonMain, and the one that proves the Phase-3a string
// seam end to end — its screen title is a `UiText` resolved by the composable on both platforms, so
// no resolver is injected and `setInitialState()` stays synchronous.
//
// Upstream 7a47e46a rebuilt it around the shared transaction-log domain: per-type sections with
// expandable credential groups, an expandable metadata card, links, and deleting the record. Moving it
// required the UI model it holds to come along: `ContentErrorConfig`,
// `ListItemTrailingContentDataUi` and `ExpandableListItemUi` were extracted out of the :ui-logic
// composable files they were co-located with (they were always KMP-clean data), and the interactor
// contract split from its Android implementation. Package unchanged, so `TransactionDetailsScreen`
// and the dashboard `entryProvider` are untouched.
package eu.europa.ec.dashboardfeature.ui.transactions.detail

import androidx.lifecycle.viewModelScope
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorDeleteTransactionPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorPartialState
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsBodyUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsSectionUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsUi
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.shared.resources.transaction_details_screen_title
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.extension.withExpansionIcon
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

data class State(
    val isLoading: Boolean = false,
    val error: ContentErrorConfig? = null,
    val isDeleting: Boolean = false,
    val isBottomSheetOpen: Boolean = false,
    val sheetContent: TransactionDetailsBottomSheetContent = TransactionDetailsBottomSheetContent.DeleteTransactionConfirmation,

    val title: UiText = UiText.Resource(Res.string.transaction_details_screen_title),
    val transactionDetailsUi: TransactionDetailsUi? = null,
    val expandedGroupIds: Set<String> = emptySet(),
    val isCardExpanded: Boolean = false,
) : ViewState

sealed class Event : ViewEvent {
    data object Init : Event()
    data object Pop : Event()
    data object DismissError : Event()
    data object DeletePressed : Event()

    sealed class BottomSheet : Event() {
        data class UpdateBottomSheetState(val isOpen: Boolean) : BottomSheet()

        sealed class Delete : BottomSheet() {
            data object PrimaryButtonPressed : Delete()
            data object SecondaryButtonPressed : Delete()
        }
    }

    data class ExpandOrCollapseGroupItem(val itemId: String) : Event()
    data object ExpandOrCollapseCard : Event()
    data class OpenLink(val url: String) : Event()

}

sealed class Effect : ViewSideEffect {
    data object ShowBottomSheet : Effect()
    data object CloseBottomSheet : Effect()

    sealed class Navigation : Effect() {
        data object Pop : Navigation()
        data class OpenUrlExternally(val url: String) : Navigation()
    }
}

sealed class TransactionDetailsBottomSheetContent {
    data object DeleteTransactionConfirmation : TransactionDetailsBottomSheetContent()
}

@KoinViewModel
// Was `internal` while it lived in :dashboard-feature; now that it is in :shared-ui the screen
// consumes it across a module boundary.
class TransactionDetailsViewModel(
    private val interactor: TransactionDetailsInteractor,
    @InjectedParam private val transactionId: String,
) : MviViewModel<Event, State, Effect>() {
    private var transactionDetailsJob: Job? = null

    init {
        // Tied to the ViewModel's lifetime, not the composition's — see the note on
        // `OneTimeLaunchedEffect`'s saveable guard in HomeViewModel. The `Event.Init` branch stays:
        // the error state's `onRetry` re-sends this same event.
        getTransactionDetails(Event.Init)
    }

    override fun setInitialState(): State = State()

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Init -> {
                getTransactionDetails(event)
            }

            is Event.DismissError -> {
                setState { copy(error = null) }
            }

            is Event.Pop -> {
                if (viewState.value.isDeleting) return
                setState { copy(error = null) }
                setEffect { Effect.Navigation.Pop }
            }

            is Event.DeletePressed -> {
                if (viewState.value.isLoading || viewState.value.isDeleting ||
                    viewState.value.transactionDetailsUi == null
                ) return
                setState { copy(error = null) }
                showBottomSheet(
                    sheetContent = TransactionDetailsBottomSheetContent.DeleteTransactionConfirmation,
                )
            }

            is Event.BottomSheet.UpdateBottomSheetState -> {
                setState { copy(isBottomSheetOpen = event.isOpen) }
            }

            is Event.BottomSheet.Delete.PrimaryButtonPressed -> {
                if (!viewState.value.isBottomSheetOpen || viewState.value.error != null) return
                deleteTransaction()
            }

            is Event.BottomSheet.Delete.SecondaryButtonPressed -> {
                hideBottomSheet()
            }

            is Event.ExpandOrCollapseGroupItem -> {
                setState {
                    val details = transactionDetailsUi ?: return@setState this
                    val updatedIds = if (event.itemId in expandedGroupIds) {
                        expandedGroupIds - event.itemId
                    } else {
                        expandedGroupIds + event.itemId
                    }
                    copy(
                        expandedGroupIds = updatedIds,
                        transactionDetailsUi = details.copy(
                            body = details.body.withExpansionIcons(updatedIds),
                        ),
                    )
                }
            }

            is Event.ExpandOrCollapseCard -> {
                if (viewState.value.isLoading) return
                val card = viewState.value.transactionDetailsUi?.transactionDetailsCardUi ?: return
                if (card.metadata.isEmpty()) return
                setState { copy(isCardExpanded = !isCardExpanded) }
            }

            is Event.OpenLink -> {
                if (viewState.value.isLoading || viewState.value.transactionDetailsUi == null) return
                setEffect { Effect.Navigation.OpenUrlExternally(event.url) }
            }
        }
    }

    private fun TransactionDetailsBodyUi.withExpansionIcons(
        expandedGroupIds: Set<String>,
    ): TransactionDetailsBodyUi = when (this) {
        is TransactionDetailsBodyUi.Presentation -> copy(
            requested = requested.withExpansionIcons(expandedGroupIds),
            shared = shared.withExpansionIcons(expandedGroupIds),
        )

        is TransactionDetailsBodyUi.DataDeletionRequest -> copy(
            claims = claims.withExpansionIcons(expandedGroupIds),
        )

        is TransactionDetailsBodyUi.Issuance,
        is TransactionDetailsBodyUi.Reissuance,
        is TransactionDetailsBodyUi.Deletion,
        is TransactionDetailsBodyUi.Signing,
        is TransactionDetailsBodyUi.DpaReport -> this
    }

    private fun TransactionDetailsSectionUi.withExpansionIcons(
        expandedGroupIds: Set<String>,
    ): TransactionDetailsSectionUi = copy(
        groups = groups.map { group ->
            group.copy(
                header = group.header.withExpansionIcon(isExpanded = group.header.itemId in expandedGroupIds),
            )
        },
    )

    private fun deleteTransaction() {
        if (viewState.value.isLoading || viewState.value.isDeleting) return

        setState {
            copy(
                isLoading = true,
                isDeleting = true,
                error = null
            )
        }

        hideBottomSheet()

        viewModelScope.launch {
            interactor.deleteTransaction(transactionId = transactionId)
                .collect { response ->
                    when (response) {
                        is TransactionDetailsInteractorDeleteTransactionPartialState.Success -> {
                            setEffect { Effect.Navigation.Pop }
                        }

                        is TransactionDetailsInteractorDeleteTransactionPartialState.Failure -> {
                            setState {
                                copy(
                                    isLoading = false,
                                    isDeleting = false,
                                    error = ContentErrorConfig(
                                        onRetry = { setEvent(Event.DeletePressed) },
                                        errorSubTitle = response.errorMessage.asUiText(),
                                        onCancel = { setEvent(Event.DismissError) },
                                    )
                                )
                            }
                        }
                    }
                }
        }
    }

    private fun showBottomSheet(sheetContent: TransactionDetailsBottomSheetContent) {
        setState { copy(sheetContent = sheetContent) }
        setEffect { Effect.ShowBottomSheet }
    }

    private fun hideBottomSheet() {
        if (!viewState.value.isBottomSheetOpen) return
        setEffect { Effect.CloseBottomSheet }
    }

    private fun getTransactionDetails(event: Event) {
        transactionDetailsJob?.cancel()

        setState {
            copy(
                isLoading = true,
                error = null
            )
        }

        transactionDetailsJob = viewModelScope.launch {
            interactor.getTransactionDetails(
                transactionId = transactionId,
            ).collect { response ->
                when (response) {
                    is TransactionDetailsInteractorPartialState.Success -> {
                        val details = response.transactionDetailsUi
                        setState {
                            val isSameTransaction =
                                transactionDetailsUi?.transactionId == details.transactionId
                            val retainedGroupIds =
                                if (isSameTransaction) expandedGroupIds else emptySet()
                            copy(
                                isLoading = false,
                                error = null,
                                transactionDetailsUi = details.copy(
                                    body = details.body.withExpansionIcons(retainedGroupIds),
                                ),
                                expandedGroupIds = retainedGroupIds,
                                isCardExpanded = isSameTransaction && isCardExpanded,
                            )
                        }
                    }

                    is TransactionDetailsInteractorPartialState.Failure -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = ContentErrorConfig(
                                    onRetry = { setEvent(event) },
                                    errorSubTitle = response.error.asUiText(),
                                    onCancel = { setEvent(Event.Pop) }
                                )
                            )
                        }
                    }
                }
            }
        }
    }

}