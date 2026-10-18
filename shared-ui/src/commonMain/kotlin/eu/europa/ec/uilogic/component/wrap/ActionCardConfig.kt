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

// Phase 3b: the action-card model split out of WrapActionCard.kt. Held directly by `HomeViewModel`
// state (both of Home's cards), so it must be commonMain for that view-model to move. Its two fields
// were already KMP — `UiText` since the Phase-3a string seam, `IconDataUi` since the AppIconKey strip
// — so it was merely co-located with the composable. Package unchanged; `WrapActionCard` stays in
// :ui-logic.
package eu.europa.ec.uilogic.component.wrap

import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.uilogic.component.IconDataUi

data class ActionCardConfig(
    val title: UiText,
    val icon: IconDataUi,
    val primaryButtonText: UiText,
    val secondaryButtonText: UiText,
)
