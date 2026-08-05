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
// Moving it required the UI model it holds to come along: `ContentErrorConfig`,
// `ListItemTrailingContentDataUi` and `ExpandableListItemUi` were extracted out of the :ui-logic
// composable files they were co-located with (they were always KMP-clean data), and the interactor
// contract split from its Android implementation. Package unchanged, so `TransactionDetailsScreen`
// and the dashboard `entryProvider` are untouched.
package eu.europa.ec.dashboardfeature.ui.transactions.detail

import androidx.lifecycle.viewModelScope
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorPartialState
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsUi
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.transaction_details_screen_title
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.extension.toggleExpansionState
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

data class State(
    val isLoading: Boolean = false,
    val error: ContentErrorConfig? = null,

    val title: UiText = UiText.Resource(Res.string.transaction_details_screen_title),
    val transactionDetailsUi: TransactionDetailsUi? = null,
) : ViewState

sealed class Event : ViewEvent {
    data object Init : Event()
    data object Pop : Event()
    data object DismissError : Event()

    data class ExpandOrCollapseGroupItem(val itemId: String) : Event()

    data object RequestDataDeletionPressed : Event()
    data object ReportSuspiciousTransactionPressed : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()
    }
}

@KoinViewModel
// Was `internal` while it lived in :dashboard-feature; now that it is in :shared-ui the screen
// consumes it across a module boundary.
class TransactionDetailsViewModel(
    private val interactor: TransactionDetailsInteractor,
    @InjectedParam private val transactionId: String,
) : MviViewModel<Event, State, Effect>() {
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
                setState { copy(error = null) }
                setEffect { Effect.Navigation.Pop }
            }

            is Event.RequestDataDeletionPressed -> {
                viewModelScope.launch {
                    interactor.requestDataDeletion(transactionId = transactionId).collect()
                }
            }

            is Event.ReportSuspiciousTransactionPressed -> {
                viewModelScope.launch {
                    interactor.reportSuspiciousTransaction(transactionId = transactionId).collect()
                }
            }

            is Event.ExpandOrCollapseGroupItem -> {
                expandOrCollapseGroupItem(event.itemId)
            }
        }
    }

    private fun getTransactionDetails(event: Event) {
        setState {
            copy(
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            interactor.getTransactionDetails(
                transactionId = transactionId,
            ).collect { response ->
                when (response) {
                    is TransactionDetailsInteractorPartialState.Success -> {
                        val transactionDetailsUi = response.transactionDetailsUi
                        setState {
                            copy(
                                isLoading = false,
                                error = null,
                                transactionDetailsUi = transactionDetailsUi
                            )
                        }
                    }

                    is TransactionDetailsInteractorPartialState.Failure -> {
                        setState {
                            copy(
                                isLoading = false,
                                error = ContentErrorConfig(
                                    onRetry = { setEvent(event) },
                                    errorSubTitle = response.error,
                                    onCancel = { setEvent(Event.Pop) }
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun expandOrCollapseGroupItem(itemId: String) {
        viewState.value.transactionDetailsUi?.let { safeTransactionDetailsUi ->

            val updatedItems =
                safeTransactionDetailsUi.transactionDetailsDataShared.dataSharedItems.map { dataSharedItem ->
                    val newHeader = if (dataSharedItem.header.itemId == itemId) {
                        val newIsExpanded = !dataSharedItem.isExpanded
                        val newCollapsed = dataSharedItem.header.copy(
                            trailingContentData = ListItemTrailingContentDataUi.Icon(
                                iconData = if (newIsExpanded) {
                                    AppIcons.KeyboardArrowUp
                                } else {
                                    AppIcons.KeyboardArrowDown
                                }
                            )
                        )

                        dataSharedItem.copy(
                            header = newCollapsed,
                            isExpanded = newIsExpanded
                        )
                    } else {
                        dataSharedItem
                    }

                    dataSharedItem.copy(
                        header = newHeader.header,
                        isExpanded = newHeader.isExpanded,
                        nestedItems = newHeader.nestedItems.toggleExpansionState(itemId)
                    )
                }

            setState {
                copy(
                    transactionDetailsUi = safeTransactionDetailsUi.copy(
                        transactionDetailsDataShared = safeTransactionDetailsUi.transactionDetailsDataShared.copy(
                            dataSharedItems = updatedItems
                        )
                    )
                )
            }
        }
    }

}