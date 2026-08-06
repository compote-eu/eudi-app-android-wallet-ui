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

// The PIN-lockout countdown message, shared by the two screens that can be locked out: the PIN screen
// itself and the biometric screen's PIN fallback. Both had a byte-identical `buildLockoutMessage`, so
// this exists to keep ONE copy of the `String.format` workaround below rather than a second one.
package eu.europa.ec.commonfeature.ui.pin

import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.quick_pin_locked_out

private fun Long.pad2(): String = toString().padStart(2, '0')

/**
 * Builds the "you have entered the wrong PIN N times … please wait MM:SS" message.
 *
 * @param remainingMs milliseconds left on the lockout; rounded UP to the next whole second, so a
 * countdown never displays 00:00 while still locked.
 * @param maxFailedPinAttempts the attempt allowance, as the message's first argument.
 */
internal fun buildPinLockoutMessage(remainingMs: Long, maxFailedPinAttempts: Int): UiText {
    val totalSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    // `String.format` is JVM-only; `padStart` is exactly equivalent to "%02d" here, since
    // `totalSeconds` is coerced non-negative above.
    val mmss = "${minutes.pad2()}:${seconds.pad2()}"
    return UiText.Resource(
        Res.string.quick_pin_locked_out,
        maxFailedPinAttempts,
        mmss,
    )
}
