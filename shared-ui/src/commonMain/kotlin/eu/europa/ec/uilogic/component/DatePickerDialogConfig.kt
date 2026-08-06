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

// Extracted from FiltersDatePickerDialog.kt so `TransactionsViewModel` can hold this in commonMain; the
// composable that renders it stays in :ui-logic. The limits are kotlinx-datetime `LocalDate`s now, and
// their defaults are the app's own open bounds because kotlinx-datetime's MIN/MAX are `internal`.
// Package unchanged.
package eu.europa.ec.uilogic.component

import eu.europa.ec.businesslogic.util.OPEN_END_DATE
import eu.europa.ec.businesslogic.util.OPEN_START_DATE
import kotlinx.datetime.LocalDate

enum class DatePickerDialogType {
    SelectStartDate, SelectEndDate
}

data class DatePickerDialogConfig(
    val type: DatePickerDialogType,
    val lowerLimit: LocalDate? = OPEN_START_DATE,
    val upperLimit: LocalDate? = OPEN_END_DATE,
    val selectedUtcDateMillis: Long? = null,
)
