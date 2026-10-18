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

// Phase 3b: the error-screen config split out of ContentError.kt. Almost every view-model holds one
// in its state, so it gates their move to commonMain.
package eu.europa.ec.uilogic.component.content

import eu.europa.ec.shared.resources.UiText

/**
 * The error banner/screen a view-model puts in its state.
 *
 * Both text fields are [UiText] even though most assignments are runtime interactor messages
 * (`response.error`, `response.errorMessage`), which arrive as `UiText.Raw`. The reason is the
 * minority: the two loading view-models fill [errorSubTitle] with a generic-error *resource*, and
 * that single use was the last thing keeping a string resolver in their constructors. Not
 * `@Serializable` — the lambdas rule that out — so this is state only, never a route payload.
 */
data class ContentErrorConfig(
    val errorTitle: UiText? = null,
    val errorSubTitle: UiText? = null,
    val onCancel: () -> Unit,
    val onRetry: (() -> Unit)? = null
)
