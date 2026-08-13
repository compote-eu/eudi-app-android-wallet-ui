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

package eu.europa.ec.uilogic.component

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import eu.europa.ec.businesslogic.util.OPEN_END_DATE
import eu.europa.ec.businesslogic.util.OPEN_START_DATE
import eu.europa.ec.businesslogic.util.utcMillisToLocalDate
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.generic_cancel
import eu.europa.ec.shared.resources.generic_ok

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersDatePickerDialog(
    datePickerDialogConfig: DatePickerDialogConfig,
    onDateSelected: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val customSelectableDates = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
            // The config's limits are kotlinx-datetime now, so compare in those terms; the shared
            // helper is the same conversion this used to do inline.
            val date = utcMillisToLocalDate(utcTimeMillis)
            val min = datePickerDialogConfig.lowerLimit ?: OPEN_START_DATE
            val max = datePickerDialogConfig.upperLimit ?: OPEN_END_DATE
            return date >= min && date <= max
        }
    }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = datePickerDialogConfig.selectedUtcDateMillis,
        selectableDates = customSelectableDates
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onDateSelected(datePickerState.selectedDateMillis)
                    onDismiss()
                }
            ) {
                Text(stringResource(Res.string.generic_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.generic_cancel))
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}