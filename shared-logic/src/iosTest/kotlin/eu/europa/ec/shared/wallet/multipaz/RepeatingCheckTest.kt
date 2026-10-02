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

// The schedule of the foreground re-checks: when they run, and that leaving and returning to the app keeps
// their cadence instead of re-running on every return.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RepeatingCheckTest {

    private class Counter { var runs = 0 }

    private fun TestScope.check(counter: Counter, run: suspend () -> Unit = { counter.runs++ }) = RepeatingCheck(
        scope = backgroundScope,
        initialDelay = 30.seconds,
        interval = 15.minutes,
        timeSource = testScheduler.timeSource,
        run = run,
    )

    private fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    @Test
    fun the_first_run_waits_for_the_initial_delay_then_repeats_every_interval() = runTest {
        val counter = Counter()
        check(counter).start()

        advance(29.seconds)
        assertEquals(0, counter.runs)
        advance(1.seconds)
        assertEquals(1, counter.runs)
        advance(15.minutes)
        assertEquals(2, counter.runs)
    }

    @Test
    fun stopping_ends_the_runs() = runTest {
        val counter = Counter()
        val check = check(counter)
        check.start()
        advance(30.seconds)

        check.stop()
        advance(1.hours)

        assertEquals(1, counter.runs)
    }

    @Test
    fun coming_back_soon_keeps_the_cadence_instead_of_running_again() = runTest {
        // Back from Safari during an issuance, say: the checks ran a minute ago and need not run again.
        val counter = Counter()
        val check = check(counter)
        check.start()
        advance(30.seconds)
        check.stop()
        advance(90.seconds)

        check.start()
        advance(13.minutes)
        assertEquals(1, counter.runs)
        advance(30.seconds)
        assertEquals(2, counter.runs, "15 minutes after the first run")
    }

    @Test
    fun coming_back_after_a_long_absence_runs_after_the_initial_delay() = runTest {
        val counter = Counter()
        val check = check(counter)
        check.start()
        advance(30.seconds)
        check.stop()
        advance(2.hours)

        check.start()
        advance(29.seconds)
        assertEquals(1, counter.runs)
        advance(1.seconds)
        assertEquals(2, counter.runs)
    }

    @Test
    fun a_failed_run_does_not_end_the_schedule() = runTest {
        val counter = Counter()
        check(counter) { counter.runs++; error("the issuer is unreachable") }.start()

        advance(30.seconds)
        advance(15.minutes)

        assertEquals(2, counter.runs)
    }

    @Test
    fun starting_again_while_running_does_not_start_a_second_schedule() = runTest {
        val counter = Counter()
        val check = check(counter)
        check.start()
        check.start()

        advance(30.seconds)

        assertEquals(1, counter.runs)
    }
}
