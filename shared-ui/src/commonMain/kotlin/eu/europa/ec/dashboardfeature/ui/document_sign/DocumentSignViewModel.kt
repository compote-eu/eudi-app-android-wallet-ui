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

package eu.europa.ec.dashboardfeature.ui.document_sign

import eu.europa.ec.dashboardfeature.interactor.DocumentSignInteractor
import eu.europa.ec.dashboardfeature.ui.document_sign.model.DocumentSignButtonUi
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.document_sign_subtitle
import eu.europa.ec.shared.resources.document_sign_title
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import org.koin.core.annotation.KoinViewModel

data class State(
    val isLoading: Boolean = false,
    val error: ContentErrorConfig? = null,
    val title: UiText = UiText.Resource(Res.string.document_sign_title),
    val subtitle: UiText = UiText.Resource(Res.string.document_sign_subtitle),
    val buttonUi: DocumentSignButtonUi
) : ViewState

sealed class Event : ViewEvent {
    data object Pop : Event()
    data object OnSelectDocument : Event()

    /** What the platform's picker-and-handover did; see [DocumentSignOutcome]. */
    data class SignOutcomeReceived(val outcome: DocumentSignOutcome) : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()
    }

    /**
     * Run the platform trigger.
     *
     * Carries no mime type any more: which documents are offered is part of picking, and picking is
     * now wholly on the platform side. Android filtered to `application/pdf` through the launcher's
     * contract, iOS states the same restriction with a UTType.
     */
    data object SelectAndSign : Effect()
}

@KoinViewModel
class DocumentSignViewModel(
    private val documentSignInteractor: DocumentSignInteractor,
) : MviViewModel<Event, State, Effect>() {

    override fun setInitialState(): State = State(
        buttonUi = documentSignInteractor.getItemUi(),
    )

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.OnSelectDocument -> setEffect { Effect.SelectAndSign }

            is Event.Pop -> setEffect { Effect.Navigation.Pop }

            is Event.SignOutcomeReceived -> when (val outcome = event.outcome) {
                // The SDK owns the screen from here and reports its own failures; nothing to show.
                is DocumentSignOutcome.Started -> setState { copy(error = null) }

                // Backing out of the picker is not an error — the screen simply stays as it was.
                is DocumentSignOutcome.Cancelled -> setState { copy(error = null) }

                is DocumentSignOutcome.Failed -> setState {
                    copy(
                        error = ContentErrorConfig(
                            errorSubTitle = UiText.Raw(outcome.reason),
                            onRetry = { setEvent(Event.OnSelectDocument) },
                            onCancel = { setEvent(Event.Pop) },
                        )
                    )
                }
            }
        }
    }
}
