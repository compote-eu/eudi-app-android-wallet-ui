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

// The contract *and*, since 2026-08-28, the policy — see the note on `PinStorageController` for why the
// contract came here first. Both values are plain data (a count and `kotlin.time.Duration`s), so nothing
// here was ever Android's. Package unchanged.
//
// The policy had been written twice — `AuthenticationConfigImpl` in :authentication-logic and
// `IosAuthenticationConfig` in :shared-logic's iosMain — with identical numbers and a comment on the iOS
// side promising it matched "value for value". Nothing enforced that, and these numbers decide how long
// a locked-out user waits.
package eu.europa.ec.authenticationlogic.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

interface AuthenticationConfig {

    /**
     * Number of consecutive wrong PIN attempts allowed before the user is locked out.
     */
    val maxFailedPinAttempts: Int

    /**
     * Lockout durations applied each time the user reaches [maxFailedPinAttempts].
     * The list indexes by lockout level (0 = first lockout). Once the user exceeds the
     * size of the list, the last entry is reused for every subsequent lockout.
     */
    val pinLockoutDurations: List<Duration>
}

/**
 * The wallet's PIN lockout policy: **one declaration, both platforms.**
 *
 * An object rather than defaults on [AuthenticationConfig] so the interface stays a genuine seam — a
 * deployment that wants a different policy substitutes its own implementation in DI rather than
 * inheriting values it has to remember to override.
 *
 * It can live in commonMain because, unlike the settings in
 * [eu.europa.ec.shared.wallet.config.SharedWalletConfig], these vary by neither platform nor flavour:
 * Android's implementation sat in `src/main`, one class for both product flavours. That is what makes
 * this a complete de-duplication rather than only a shared name — there is no per-flavour value left
 * for the two sides to disagree about.
 *
 * ⚠️ Changing these changes how long a user is locked out after repeated wrong PINs. The ladder is
 * indexed by lockout level and its last entry repeats for every subsequent lockout, so the final value
 * is the steady state, not a maximum that stops mattering.
 */
object WalletAuthenticationConfig : AuthenticationConfig {

    override val maxFailedPinAttempts: Int = 3

    override val pinLockoutDurations: List<Duration> = listOf(
        30.seconds,
        90.seconds,
        5.minutes,
    )
}
