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
// Phase 3b: the expandable-list model split out of WrapExpandableListItem.kt. Held directly by
// view-model state (transaction details, documents, filters), so it must be commonMain for those
// view-models to move. Package unchanged; the composable stays in :ui-logic.
package eu.europa.ec.uilogic.component.wrap

import eu.europa.ec.uilogic.component.ListItemDataUi

sealed class ExpandableListItemUi {
    abstract val header: ListItemDataUi

    data class SingleListItem(
        override val header: ListItemDataUi,
    ) : ExpandableListItemUi()

    data class NestedListItem(
        override val header: ListItemDataUi,
        val nestedItems: List<ExpandableListItemUi>,
        val isExpanded: Boolean,
    ) : ExpandableListItemUi()
}
