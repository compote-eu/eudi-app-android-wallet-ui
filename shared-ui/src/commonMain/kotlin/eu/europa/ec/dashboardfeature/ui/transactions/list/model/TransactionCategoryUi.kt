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

package eu.europa.ec.dashboardfeature.ui.transactions.list.model

import eu.europa.ec.businesslogic.util.endOfDay
import eu.europa.ec.businesslogic.util.endOfMonth
import eu.europa.ec.businesslogic.util.endOfWeek
import eu.europa.ec.businesslogic.util.MONTH_YEAR_DATETIME_PATTERN
import eu.europa.ec.businesslogic.util.formatLocalDateTime
import eu.europa.ec.businesslogic.util.startOfDay
import eu.europa.ec.businesslogic.util.startOfMonth
import eu.europa.ec.businesslogic.util.startOfWeek
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.transaction_category_month_year
import eu.europa.ec.shared.resources.transaction_category_this_week
import eu.europa.ec.shared.resources.transaction_category_today
import org.jetbrains.compose.resources.StringResource
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

sealed class TransactionCategoryUi(
    val nameRes: StringResource,
    val id: Int,
    val order: Int,
    val dateRange: ClosedRange<LocalDateTime>? = null,
    val displayName: String? = null
) {
    data object Today : TransactionCategoryUi(
        nameRes = Res.string.transaction_category_today,
        id = 1,
        order = Int.MAX_VALUE,
        dateRange = nowLocal().startOfDay()..nowLocal().endOfDay()
    )

    data object ThisWeek : TransactionCategoryUi(
        nameRes = Res.string.transaction_category_this_week,
        id = 2,
        order = Int.MAX_VALUE - 1,
        dateRange = nowLocal().startOfWeek()..nowLocal().endOfWeek()
    )

    class Month(dateTime: LocalDateTime) : TransactionCategoryUi(
        nameRes = Res.string.transaction_category_month_year,
        id = generateMonthId(dateTime),
        order = calculateMonthOrder(dateTime),
        dateRange = dateTime.startOfMonth()..dateTime.endOfMonth(),
        displayName = dateTime.formatLocalDateTime(MONTH_YEAR_DATETIME_PATTERN).uppercase()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Month) return false

            val thisStart = this.dateRange?.start
            val otherStart = other.dateRange?.start

            return thisStart?.year == otherStart?.year &&
                    thisStart?.month == otherStart?.month
        }

        override fun hashCode(): Int {
            return dateRange?.let {
                it.start.year * 100 + it.start.month.number
            } ?: 0
        }
    }
}

private fun generateMonthId(dateTime: LocalDateTime): Int =
    dateTime.year * 100 + dateTime.month.number

private fun calculateMonthOrder(dateTime: LocalDateTime): Int {
    return dateTime.year * 100 + dateTime.month.number
}

/**
 * `LocalDateTime.now()` has no kotlinx-datetime counterpart; the device's current local date-time is
 * derived from the clock and the system zone instead.
 */
private fun nowLocal(): LocalDateTime =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
