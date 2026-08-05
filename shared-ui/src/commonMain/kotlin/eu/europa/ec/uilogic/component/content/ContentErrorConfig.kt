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

// The screen-fraction constants split out of :ui-logic's Constants.kt: they are plain Floats used
// as defaults by the serialized UI-model (SuccessUIConfig.ImageConfig.screenPercentageSize), which
// lives in commonMain. Package unchanged so call sites don't churn.
// Phase 3b: the error-screen config split out of ContentError.kt. Almost every view-model holds one
// in its state, so it gates their move to commonMain. Its text fields stay `String` rather than
// `UiText`: 21 of the 23 assignments in the app are runtime error messages produced by interactors,
// not string resources.
package eu.europa.ec.uilogic.component.content

data class ContentErrorConfig(
    val errorTitle: String? = null,
    val errorSubTitle: String? = null,
    val onCancel: () -> Unit,
    val onRetry: (() -> Unit)? = null
)
