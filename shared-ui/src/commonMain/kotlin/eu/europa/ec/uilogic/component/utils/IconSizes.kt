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

// The icon-size constants split out of :ui-logic's Constants.kt, following the same precedent as
// Percentages.kt: they are plain Ints used as defaults by `ListItemLeadingContentDataUi`, which now
// lives in commonMain. Package unchanged so call sites don't churn.
package eu.europa.ec.uilogic.component.utils

/** Value 24 */
const val DEFAULT_ICON_SIZE = 24

/** Value 80 */
const val DEFAULT_BIG_ICON_SIZE = 80

/** Value 40 */
const val ICON_SIZE_40 = 40
