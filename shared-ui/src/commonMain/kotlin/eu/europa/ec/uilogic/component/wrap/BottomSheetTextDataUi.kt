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

// Extracted from WrapModalBottomSheet so view-models in commonMain can build sheet content. It was
// only co-located with those composables — every field is a UiText or a Boolean. Package unchanged.
package eu.europa.ec.uilogic.component.wrap

import eu.europa.ec.shared.resources.UiText

/**
 * Data class representing the text content for a bottom sheet.
 *
 * This class holds the title, message, and button texts for a bottom sheet.
 * It also includes flags to indicate if a button should be styled as a warning.
 *
 * @property title The title of the bottom sheet.
 * @property message The message displayed in the bottom sheet.
 * @property positiveButtonText The text for the positive button (e.g., "OK", "Confirm"). Can be null if no positive button is needed.
 * @property isPositiveButtonWarning A flag indicating if the positive button should be styled as a warning (e.g., red color). Defaults to false.
 * @property negativeButtonText The text for the negative button (e.g., "Cancel", "Dismiss"). Can be null if no negative button is needed.
 * @property isNegativeButtonWarning A flag indicating if the negative button should be styled as a warning (e.g., red color). Defaults to false.
 */
data class BottomSheetTextDataUi(
    val title: UiText,
    val message: UiText,
    val positiveButtonText: UiText? = null,
    val isPositiveButtonWarning: Boolean = false,
    val negativeButtonText: UiText? = null,
    val isNegativeButtonWarning: Boolean = false,
)
