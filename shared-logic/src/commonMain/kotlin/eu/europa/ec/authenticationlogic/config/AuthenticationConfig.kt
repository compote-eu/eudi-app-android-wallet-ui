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

// The *contract* only — see the note on `PinStorageController`. Both values are plain data (a count and
// `kotlin.time.Duration`s), so nothing here was ever Android's. `AuthenticationConfigImpl`, which sets this
// app's actual policy, stays in :authentication-logic. Package unchanged.
package eu.europa.ec.authenticationlogic.config

import kotlin.time.Duration

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
