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

// What wrong PINs cost on iOS. Worth pinning because the escalation is stateful and easy to get subtly
// wrong — a level that fails to advance, or a lockout that a restart forgets, is a wallet that can be
// brute-forced — and because these numbers must stay in step with Android's `PrefsPinThrottleProvider`.
package eu.europa.ec.authenticationlogic.storage

import eu.europa.ec.authenticationlogic.config.AuthenticationConfig
import eu.europa.ec.authenticationlogic.provider.PinLockoutState
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class IosPinThrottleTest {

    private val config = object : AuthenticationConfig {
        override val maxFailedPinAttempts: Int = 3
        override val pinLockoutDurations: List<Duration> = listOf(30.seconds, 90.seconds, 5.minutes)
    }

    private var now: Long = 1_000_000L

    /** The app's own defaults, cleared around each case — the same store the real throttle writes to. */
    private val defaults = NSUserDefaults.standardUserDefaults

    private fun throttle() = IosPinThrottle(
        authenticationConfig = config,
        defaults = defaults,
        clock = { now },
    )

    @BeforeTest
    fun setUp() = clearKeys()

    @AfterTest
    fun tearDown() = clearKeys()

    private fun clearKeys() {
        listOf(
            "PinFailedAttempts",
            "PinLockoutLevel",
            "PinLockoutStartedAt",
            "PinLockoutEndsAt",
        ).forEach { defaults.removeObjectForKey(it) }
    }

    @Test
    fun failures_below_the_limit_do_not_lock_anything() = runTest {
        val throttle = throttle()

        assertEquals(PinLockoutState.Idle, throttle.recordFailure())
        assertEquals(PinLockoutState.Idle, throttle.recordFailure())
        assertEquals(PinLockoutState.Idle, throttle.getState())
    }

    @Test
    fun the_limit_locks_for_the_first_configured_duration() = runTest {
        val throttle = throttle()
        repeat(2) { throttle.recordFailure() }

        val state = assertIs<PinLockoutState.Active>(throttle.recordFailure())

        assertEquals(30.seconds, state.remaining)
        assertEquals(30.seconds, state.total)
    }

    @Test
    fun the_remaining_time_counts_down_and_then_clears() = runTest {
        val throttle = throttle()
        repeat(3) { throttle.recordFailure() }

        now += 10.seconds.inWholeMilliseconds
        assertEquals(20.seconds, assertIs<PinLockoutState.Active>(throttle.getState()).remaining)

        now += 25.seconds.inWholeMilliseconds
        assertEquals(PinLockoutState.Idle, throttle.getState())
    }

    @Test
    fun each_lockout_lasts_longer_than_the_last() = runTest {
        val throttle = throttle()

        repeat(3) { throttle.recordFailure() }
        now += 1.minutes.inWholeMilliseconds
        repeat(3) { throttle.recordFailure() }
        val second = assertIs<PinLockoutState.Active>(throttle.getState())
        assertEquals(90.seconds, second.total)

        now += 2.minutes.inWholeMilliseconds
        repeat(3) { throttle.recordFailure() }
        assertEquals(5.minutes, assertIs<PinLockoutState.Active>(throttle.getState()).total)

        // Past the end of the list the longest wait repeats, so it never gets cheaper to keep guessing.
        now += 6.minutes.inWholeMilliseconds
        repeat(3) { throttle.recordFailure() }
        assertEquals(5.minutes, assertIs<PinLockoutState.Active>(throttle.getState()).total)
    }

    @Test
    fun a_correct_pin_clears_the_count_and_the_escalation() = runTest {
        val throttle = throttle()
        repeat(3) { throttle.recordFailure() }

        throttle.recordSuccess()

        assertEquals(PinLockoutState.Idle, throttle.getState())
        // Level reset too: the next lockout starts at 30 seconds again, as on Android.
        repeat(3) { throttle.recordFailure() }
        assertEquals(30.seconds, assertIs<PinLockoutState.Active>(throttle.getState()).total)
    }

    @Test
    fun a_lockout_survives_a_restart() = runTest {
        repeat(3) { throttle().recordFailure() }
        now += 5.seconds.inWholeMilliseconds

        // A fresh instance reads the same defaults — a lockout an app restart forgets is no lockout.
        assertEquals(25.seconds, assertIs<PinLockoutState.Active>(throttle().getState()).remaining)
    }

    @Test
    fun a_clock_moved_backwards_reports_the_whole_wait_rather_than_none() = runTest {
        val throttle = throttle()
        repeat(3) { throttle.recordFailure() }

        now -= 1.minutes.inWholeMilliseconds

        // Winding the clock back must not be a way out of the lockout.
        val state = assertIs<PinLockoutState.Active>(throttle.getState())
        assertEquals(30.seconds, state.remaining)
    }
}
