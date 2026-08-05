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

// Phase 3b: the interactor *contract* moves to commonMain with `ProximitySuccessViewModel`, per the
// `SplashInteractor` pattern. It needed no retyping at all — the partial state was already built from
// shared UI models. `ProximitySuccessInteractorImpl` keeps wallet-core in :proximity-feature.
package eu.europa.ec.proximityfeature.interactor

import eu.europa.ec.commonfeature.interactor.ScopedPresentationInteractor
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.flow.Flow

sealed class ProximitySuccessInteractorGetUiItemsPartialState {
    data class Success(
        val documentsUi: List<ExpandableListItemUi.NestedListItem>,
        val headerConfig: ContentHeaderConfig,
    ) : ProximitySuccessInteractorGetUiItemsPartialState()

    data class Failed(
        val errorMessage: String,
    ) : ProximitySuccessInteractorGetUiItemsPartialState()
}

interface ProximitySuccessInteractor : ScopedPresentationInteractor {
    fun getUiItems(): Flow<ProximitySuccessInteractorGetUiItemsPartialState>
    fun stopPresentation()
}
