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

package eu.europa.ec.authenticationlogic.storage

import eu.europa.ec.authenticationlogic.config.AuthenticationConfig
import eu.europa.ec.authenticationlogic.controller.throttle.PinThrottleController
import eu.europa.ec.authenticationlogic.provider.PinLockoutState
import platform.Foundation.NSUserDefaults
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long iOS makes someone wait after wrong PINs — the same escalation Android applies, over
 * `NSUserDefaults`.
 *
 * **Not the Keychain, deliberately**, unlike the PIN verifier next door: a failure count and a wait-until
 * timestamp are not secrets, and putting them behind `WhenUnlockedThisDeviceOnly` would make the lockout
 * unreadable exactly when the app most needs it. Both are still device-local and survive a restart, which
 * is what a lockout has to do to mean anything.
 *
 * The semantics are `PrefsPinThrottleProvider`'s, kept deliberately identical: count failures until
 * [AuthenticationConfig.maxFailedPinAttempts], then lock for the duration at the current level, advance the
 * level, and evaluate the remaining time against the wall clock on every read. A [clock] is injectable so
 * the escalation can be tested without waiting out a real lockout.
 */
class IosPinThrottle(
    private val authenticationConfig: AuthenticationConfig,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : PinThrottleController {

    override suspend fun getState(): PinLockoutState {
        val startedAt = defaults.integerForKey(KEY_LOCKOUT_STARTED_AT)
        val endsAt = defaults.integerForKey(KEY_LOCKOUT_ENDS_AT)

        if (endsAt <= 0L || startedAt <= 0L) return PinLockoutState.Idle

        val total = (endsAt - startedAt).coerceAtLeast(0L).milliseconds
        val now = clock()

        // A clock moved backwards would otherwise read as "already over"; report the full wait instead.
        if (now < startedAt) return PinLockoutState.Active(remaining = total, total = total)
        if (now >= endsAt) return PinLockoutState.Idle

        return PinLockoutState.Active(remaining = (endsAt - now).milliseconds, total = total)
    }

    override suspend fun recordFailure(): PinLockoutState {
        val attempts = defaults.integerForKey(KEY_FAILED_ATTEMPTS).toInt() + 1
        if (attempts < authenticationConfig.maxFailedPinAttempts) {
            defaults.setInteger(attempts.toLong(), KEY_FAILED_ATTEMPTS)
            return PinLockoutState.Idle
        }

        val level = defaults.integerForKey(KEY_LOCKOUT_LEVEL).toInt()
        val durations = authenticationConfig.pinLockoutDurations
        val duration: Duration = if (durations.isEmpty()) {
            Duration.ZERO
        } else {
            // The last duration repeats once the levels run out, so the wait never shortens again.
            durations[level.coerceAtMost(durations.lastIndex)]
        }

        val now = clock()
        defaults.setInteger(0L, KEY_FAILED_ATTEMPTS)
        defaults.setInteger((level + 1).toLong(), KEY_LOCKOUT_LEVEL)
        defaults.setInteger(now, KEY_LOCKOUT_STARTED_AT)
        defaults.setInteger(now + duration.inWholeMilliseconds, KEY_LOCKOUT_ENDS_AT)

        return PinLockoutState.Active(remaining = duration, total = duration)
    }

    override suspend fun recordSuccess() {
        // The level resets too: a correct PIN means the escalation starts over, as on Android.
        defaults.setInteger(0L, KEY_FAILED_ATTEMPTS)
        defaults.setInteger(0L, KEY_LOCKOUT_LEVEL)
        defaults.setInteger(0L, KEY_LOCKOUT_STARTED_AT)
        defaults.setInteger(0L, KEY_LOCKOUT_ENDS_AT)
    }

    private companion object {
        const val KEY_FAILED_ATTEMPTS = "PinFailedAttempts"
        const val KEY_LOCKOUT_LEVEL = "PinLockoutLevel"
        const val KEY_LOCKOUT_STARTED_AT = "PinLockoutStartedAt"
        const val KEY_LOCKOUT_ENDS_AT = "PinLockoutEndsAt"
    }
}
