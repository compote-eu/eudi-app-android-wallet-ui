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

// Phase 3b: moved with the `DocumentSuccessViewModel` base. Its only Android type was the pending
// intent it forwards, now the opaque `PlatformIntent`. Its interactor contract needed no retyping.
package eu.europa.ec.proximityfeature.ui.success

import androidx.lifecycle.viewModelScope
import eu.europa.ec.commonfeature.ui.document_success.DocumentSuccessViewModel
import eu.europa.ec.corelogic.di.getOrNullKoinScope
import eu.europa.ec.proximityfeature.interactor.ProximitySuccessInteractor
import eu.europa.ec.proximityfeature.interactor.ProximitySuccessInteractorGetUiItemsPartialState
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class ProximitySuccessViewModel(
    private val interactor: ProximitySuccessInteractor,
    @InjectedParam private val presentationScopeId: String
) : DocumentSuccessViewModel() {

    // Declared here rather than in DocumentSuccessViewModel because `doWork()` is abstract: calling
    // it from the base class's `init` would run this override before this subclass is constructed.
    // Tied to the ViewModel's lifetime, not the composition's — see the note on
    // `OneTimeLaunchedEffect`'s saveable guard in HomeViewModel.
    init {
        doWork()
    }

    override fun getNextScreenConfigNavigation(): ConfigNavigation {
        val popToDashboard = ConfigNavigation(
            navigationType = NavigationType.PopTo(DashboardRoute),
        )

        return popToDashboard
    }

    override fun doWork() {
        setState {
            copy(isLoading = true)
        }

        viewModelScope.launch {

            interactor.setScopeId(presentationScopeId)

            interactor.getUiItems().collect { response ->
                when (response) {
                    is ProximitySuccessInteractorGetUiItemsPartialState.Failed -> {
                        setState {
                            copy(
                                isLoading = false,
                            )
                        }
                    }

                    is ProximitySuccessInteractorGetUiItemsPartialState.Success -> {
                        setState {
                            copy(
                                headerConfig = response.headerConfig,
                                items = response.documentsUi,
                                isLoading = false,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun getPendingIntent(): PlatformIntent? {
        return null
    }

    override fun onCleared() {
        super.onCleared()
        interactor.stopPresentation()
        getOrNullKoinScope(presentationScopeId)?.close()
    }
}