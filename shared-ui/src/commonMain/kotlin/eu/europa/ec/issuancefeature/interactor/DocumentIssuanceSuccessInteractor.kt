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

// Phase 3b: the interactor *contract* moves to commonMain with `DocumentIssuanceSuccessViewModel`, per
// the `SplashInteractor` pattern; `DocumentIssuanceSuccessInteractorImpl` keeps wallet-core in
// :issuance-feature.
//
// `documentIds` is declared `List<String>` rather than `List<DocumentId>`: `DocumentId` is
// `typealias DocumentId = String` in the Android-only wallet-document-manager library, so this is the
// same type at runtime and every `List<DocumentId>` call site still compiles. `IssuanceSuccessUiConfig`
// already made exactly this call for the same reason.
package eu.europa.ec.issuancefeature.interactor

import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.flow.Flow

sealed class DocumentIssuanceSuccessInteractorGetUiItemsPartialState {
    data class Success(
        val documentsUi: List<ExpandableListItemUi.NestedListItem>,
        val headerConfig: ContentHeaderConfig,
        val bannerText: UiText,
    ) : DocumentIssuanceSuccessInteractorGetUiItemsPartialState()

    data class Failed(
        val errorMessage: String,
    ) : DocumentIssuanceSuccessInteractorGetUiItemsPartialState()
}

interface DocumentIssuanceSuccessInteractor {
    fun getUiItems(documentIds: List<String>): Flow<DocumentIssuanceSuccessInteractorGetUiItemsPartialState>
}
