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

package eu.europa.ec.businesslogic.extension

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs on both Android (JVM) and iOS (Kotlin/Native) from the same source. */
class InstantExtensionsTest {

    @Test
    fun a_past_instant_is_expired_and_not_valid() {
        val past = Clock.System.now() - 1.days
        assertTrue(past.isExpired())
        assertFalse(past.isValid())
    }

    @Test
    fun a_near_future_instant_is_within_but_not_beyond_the_window() {
        val inThreeDays = Clock.System.now() + 3.days
        assertTrue(inThreeDays.isWithinNextDays(7))
        assertFalse(inThreeDays.isBeyondNextDays(7))
    }

    @Test
    fun a_far_future_instant_is_beyond_but_not_within_the_window() {
        val inTenDays = Clock.System.now() + 10.days
        assertTrue(inTenDays.isBeyondNextDays(7))
        assertFalse(inTenDays.isWithinNextDays(7))
    }
}
