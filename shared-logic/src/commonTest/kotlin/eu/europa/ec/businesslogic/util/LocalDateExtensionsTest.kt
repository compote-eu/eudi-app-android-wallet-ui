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

// The week/month boundary arithmetic here is hand-written, because kotlinx-datetime has no
// `TemporalAdjusters` — so it is worth pinning at the awkward cases: mid-week, on the boundary days
// themselves, across a month end, across a year end, and in a leap February.
package eu.europa.ec.businesslogic.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDateExtensionsTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 13, minute: Int = 37) =
        LocalDateTime(year, month, day, hour, minute)

    //region day

    @Test
    fun start_of_day_is_midnight_and_end_of_day_is_one_second_before_the_next() {
        val subject = at(2026, 8, 6)
        assertEquals(LocalDateTime(2026, 8, 6, 0, 0, 0), subject.startOfDay())
        assertEquals(LocalDateTime(2026, 8, 6, 23, 59, 59), subject.endOfDay())
    }

    //endregion

    //region week — Monday to Sunday

    @Test
    fun the_week_of_a_thursday_runs_from_its_monday_to_its_sunday() {
        // 2026-08-06 is a Thursday.
        val thursday = at(2026, 8, 6)
        assertEquals(LocalDate(2026, 8, 3), thursday.startOfWeek().date)
        assertEquals(LocalDate(2026, 8, 9), thursday.endOfWeek().date)
    }

    @Test
    fun a_monday_is_its_own_start_of_week() {
        // previousOrSame, not previous.
        val monday = at(2026, 8, 3)
        assertEquals(LocalDate(2026, 8, 3), monday.startOfWeek().date)
        assertEquals(LocalDate(2026, 8, 9), monday.endOfWeek().date)
    }

    @Test
    fun a_sunday_is_its_own_end_of_week() {
        // nextOrSame, not next — and its week began the *previous* Monday.
        val sunday = at(2026, 8, 9)
        assertEquals(LocalDate(2026, 8, 3), sunday.startOfWeek().date)
        assertEquals(LocalDate(2026, 8, 9), sunday.endOfWeek().date)
    }

    @Test
    fun a_week_can_span_a_month_boundary() {
        // Tuesday 1 September 2026; its Monday is in August.
        val tuesday = at(2026, 9, 1)
        assertEquals(LocalDate(2026, 8, 31), tuesday.startOfWeek().date)
        assertEquals(LocalDate(2026, 9, 6), tuesday.endOfWeek().date)
    }

    @Test
    fun a_week_can_span_a_year_boundary() {
        // Thursday 31 December 2026; its week ends in January.
        val newYearsEve = at(2026, 12, 31)
        assertEquals(LocalDate(2026, 12, 28), newYearsEve.startOfWeek().date)
        assertEquals(LocalDate(2027, 1, 3), newYearsEve.endOfWeek().date)
    }

    @Test
    fun the_week_carries_the_right_times_not_just_the_right_days() {
        val thursday = at(2026, 8, 6)
        assertEquals(LocalDateTime(2026, 8, 3, 0, 0, 0), thursday.startOfWeek())
        assertEquals(LocalDateTime(2026, 8, 9, 23, 59, 59), thursday.endOfWeek())
    }

    //endregion

    //region month

    @Test
    fun the_month_runs_from_the_first_to_the_last_day() {
        val subject = at(2026, 8, 6)
        assertEquals(LocalDateTime(2026, 8, 1, 0, 0, 0), subject.startOfMonth())
        assertEquals(LocalDateTime(2026, 8, 31, 23, 59, 59), subject.endOfMonth())
    }

    @Test
    fun a_thirty_day_month_ends_on_the_thirtieth() {
        assertEquals(LocalDate(2026, 9, 30), at(2026, 9, 15).endOfMonth().date)
    }

    @Test
    fun february_ends_on_the_twenty_eighth_in_a_common_year() {
        assertEquals(LocalDate(2026, 2, 28), at(2026, 2, 10).endOfMonth().date)
    }

    @Test
    fun february_ends_on_the_twenty_ninth_in_a_leap_year() {
        // The next-month-minus-a-day derivation has to get this right without a calendar API.
        assertEquals(LocalDate(2028, 2, 29), at(2028, 2, 10).endOfMonth().date)
    }

    @Test
    fun december_rolls_into_the_next_year_when_finding_its_end() {
        // Exercises the year carry in the month arithmetic.
        assertEquals(LocalDate(2026, 12, 31), at(2026, 12, 5).endOfMonth().date)
    }

    //endregion

    //region date picker millis

    @Test
    fun a_date_survives_a_round_trip_through_utc_millis() {
        // The picker hands back what it was given, so this pair has to be each other's inverse.
        val date = LocalDate(2026, 8, 6)
        assertEquals(date, utcMillisToLocalDate(localDateToUtcMillis(date)))
    }

    @Test
    fun utc_millis_are_midnight_utc_not_local_midnight() {
        // 2026-08-06T00:00:00Z. Pinned because the picker defines its selection in UTC, so using the
        // device zone here would shift the selected day for anyone east or west of Greenwich.
        assertEquals(1785974400000L, localDateToUtcMillis(LocalDate(2026, 8, 6)))
    }

    //endregion

    //region conversions and open bounds

    @Test
    fun a_date_becomes_a_date_time_at_midnight() {
        assertEquals(LocalDateTime(2026, 8, 6, 0, 0, 0), LocalDate(2026, 8, 6).toLocalDateTime())
    }

    @Test
    fun an_absent_date_displays_as_an_empty_string() {
        assertEquals("", (null as LocalDate?).toDisplayedDate())
    }

    @Test
    fun the_open_bounds_bracket_every_realistic_date() {
        assertEquals(true, OPEN_START_DATE < LocalDate(1970, 1, 1))
        assertEquals(true, OPEN_END_DATE > LocalDate(2100, 1, 1))
    }

    //endregion
}
