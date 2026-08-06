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

// The last stage of the deferred date migration: the calendar helpers the transactions list needs,
// on kotlinx-datetime instead of java.time. These are *moves* from :business-logic's DateUtils, not
// duplicates — each had exactly one consumer and all of them moved to commonMain with the
// transactions view-model. Package unchanged, so call sites read the same.
//
// kotlinx-datetime has no `TemporalAdjusters` and no `MIN`/`MAX` (they exist but are `internal`), so
// both are expressed here directly.
package eu.europa.ec.businesslogic.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Midnight of this date-time's day. Was `withHour(0).withMinute(0).withSecond(0)`. */
fun LocalDateTime.startOfDay(): LocalDateTime = LocalDateTime(date, LocalTime(0, 0, 0))

/** 23:59:59 of this date-time's day. */
fun LocalDateTime.endOfDay(): LocalDateTime = LocalDateTime(date, LocalTime(23, 59, 59))

/**
 * Midnight of the Monday of this date-time's week — `previousOrSame(MONDAY)`, computed from the ISO
 * day number (Monday = 1) since kotlinx-datetime has no temporal adjusters.
 */
fun LocalDateTime.startOfWeek(): LocalDateTime =
    LocalDateTime(date.minusDays(date.dayOfWeek.isoDayNumber - 1), LocalTime(0, 0, 0))

/** End of the Sunday of this date-time's week — `nextOrSame(SUNDAY)`. */
fun LocalDateTime.endOfWeek(): LocalDateTime =
    LocalDateTime(date.plusDays(7 - date.dayOfWeek.isoDayNumber), LocalTime(23, 59, 59))

/** Midnight of the first day of this date-time's month. */
fun LocalDateTime.startOfMonth(): LocalDateTime =
    LocalDateTime(LocalDate(date.year, date.month.number, 1), LocalTime(0, 0, 0))

/**
 * End of the last day of this date-time's month. Derived by stepping to the first of the next month
 * and back one day, which is how the length of a month is found without a calendar API.
 */
fun LocalDateTime.endOfMonth(): LocalDateTime {
    val firstOfThisMonth = LocalDate(date.year, date.month.number, 1)
    val lastOfThisMonth = firstOfThisMonth.plusMonths(1).minusDays(1)
    return LocalDateTime(lastOfThisMonth, LocalTime(23, 59, 59))
}

/** This date at midnight, i.e. the previous `atTime(LocalTime.MIDNIGHT)`. */
fun LocalDate.toLocalDateTime(): LocalDateTime = LocalDateTime(this, LocalTime(0, 0, 0))

/**
 * The local date at [utcMillis], in the device's time zone — the date picker speaks epoch millis.
 */
fun utcMillisToLocalDate(utcMillis: Long): LocalDate =
    Instant.fromEpochMilliseconds(utcMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date

/**
 * [localDate] as epoch millis at **UTC** midnight. Deliberately UTC and not the device zone: this
 * feeds the Material date picker, whose selection is defined in UTC, and the previous
 * `localDateToUtcMillis` passed `ZoneOffset.UTC` for the same reason.
 */
fun localDateToUtcMillis(localDate: LocalDate): Long =
    localDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

/**
 * This date as `dd/MM/yyyy`, or an empty string when absent.
 *
 * Formatting goes through [formatInstant] rather than a second expect/actual: both platform
 * formatters there resolve in the device's time zone, so a date converted to an instant in that same
 * zone formats back to the identical calendar fields.
 */
fun LocalDate?.toDisplayedDate(): String =
    this?.atStartOfDayIn(TimeZone.currentSystemDefault())
        ?.formatInstant(pattern = DAY_MONTH_YEAR_TEXT_FIELD_PATTERN)
        .orEmpty()

/** Previous DAY_MONTH_YEAR_TEXT_FIELD_PATTERN. */
const val DAY_MONTH_YEAR_TEXT_FIELD_PATTERN: String = "dd/MM/yyyy"

/** Previous MONTH_YEAR_DATETIME_PATTERN, used for the transactions list's month headings. */
const val MONTH_YEAR_DATETIME_PATTERN: String = "MMMM yyyy"

/**
 * This date-time formatted with [pattern]. See [toDisplayedDate] for why routing through
 * [formatInstant] is equivalent to formatting the local fields directly.
 */
fun LocalDateTime.formatLocalDateTime(pattern: String): String =
    toInstant(TimeZone.currentSystemDefault()).formatInstant(pattern = pattern)

/**
 * Open bounds for a date range the user has not narrowed yet, standing in for `java.time.LocalDate`'s
 * `MIN`/`MAX` (kotlinx-datetime's equivalents are `internal`). Year 1 to year 9999 brackets every date
 * a wallet can hold — see also `FilterElement.DateTimeRangeFilterItem.OPEN_START`.
 */
val OPEN_START_DATE: LocalDate = LocalDate(1, 1, 1)
val OPEN_END_DATE: LocalDate = LocalDate(9999, 12, 31)

private fun LocalDate.minusDays(days: Int): LocalDate =
    LocalDate.fromEpochDays(toEpochDays() - days)

private fun LocalDate.plusDays(days: Int): LocalDate =
    LocalDate.fromEpochDays(toEpochDays() + days)

private fun LocalDate.plusMonths(months: Int): LocalDate {
    val zeroBased = (year * 12) + (month.number - 1) + months
    return LocalDate(zeroBased / 12, (zeroBased % 12) + 1, day)
}
