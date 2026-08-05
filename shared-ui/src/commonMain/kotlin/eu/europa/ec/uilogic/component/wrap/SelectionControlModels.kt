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
// Phase 3b: the selection-control models split out of their Wrap* composable files. Plain data
// (state + callback), referenced by `ListItemTrailingContentDataUi`, so they have to sit in
// commonMain for the list-item model to compile there. Packages unchanged.
package eu.europa.ec.uilogic.component.wrap

data class CheckboxDataUi(
    val isChecked: Boolean,
    val enabled: Boolean = true,
    val onCheckedChange: ((Boolean) -> Unit)? = null,
)

data class RadioButtonDataUi(
    val isSelected: Boolean,
    val enabled: Boolean = true,
    val onCheckedChange: (() -> Unit)? = null,
)

data class SwitchDataUi(
    val isChecked: Boolean,
    val enabled: Boolean = true,
)
