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

// Phase 3b: the interactor *contract* moves to commonMain with `PresentationSuccessViewModel`, per the
// `SplashInteractor` pattern; `PresentationSuccessInteractorImpl` keeps wallet-core in
// :presentation-feature.
//
// Two members were retyped, each the way its kind of value should cross: `redirectUri` is a `String`
// rather than a `java.net.URI` (the view-model already called `.toString()` on it to build the deep
// link, so this only removes that hop), and the pending intent is the opaque `PlatformIntent` handle,
// which on Android is a typealias so the implementation is unchanged.
package eu.europa.ec.presentationfeature.interactor

import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.commonfeature.interactor.ScopedPresentationInteractor
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.flow.Flow

sealed class PresentationSuccessInteractorGetUiItemsPartialState {
    data class Success(
        val documentsUi: List<ExpandableListItemUi.NestedListItem>,
        val headerConfig: ContentHeaderConfig,
        val bannerText: UiText,
    ) : PresentationSuccessInteractorGetUiItemsPartialState()

    data class Failed(
        val errorMessage: String
    ) : PresentationSuccessInteractorGetUiItemsPartialState()
}

interface PresentationSuccessInteractor : ScopedPresentationInteractor {
    val initiatorRoute: String
    val redirectUri: String?
    fun getPendingIntent(): PlatformIntent?
    fun getUiItems(): Flow<PresentationSuccessInteractorGetUiItemsPartialState>
    fun stopPresentation()
}
