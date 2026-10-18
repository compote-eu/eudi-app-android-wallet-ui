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

package eu.europa.ec.authenticationlogic.secure

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What a [SecurePin] guarantees, checked through [securePinOf] so it runs against *both*
 * implementations — `SecurePinImpl` on Android and `NativeSecurePin` on iOS.
 *
 * These cases came from `:authentication-logic`'s `TestSecurePin` and moved with their subject: the
 * implementation moved to :shared-logic when the PIN field became shared UI, and the point of a second
 * implementation is that it behaves identically, which only a shared test can state.
 */
class SecurePinTest {

    @Test
    fun getAndClear_returns_characters_once() {
        val pin = securePinOf("123456")

        val data = pin.getAndClear()

        data.useChars {
            assertContentEquals(charArrayOf('1', '2', '3', '4', '5', '6'), it)
        }
        assertTrue(pin.isCleared)
        assertFailsWith<IllegalStateException> { pin.getAndClear() }
        data.close()
    }

    @Test
    fun getAndClearAsString_returns_the_string_once() {
        val typed = "123456"
        val pin = securePinOf(typed)

        assertEquals(typed, pin.getAndClearAsString())

        assertTrue(pin.isCleared)
        assertFailsWith<IllegalStateException> { pin.getAndClearAsString() }
    }

    @Test
    fun closing_the_data_clears_it() {
        val pin = securePinOf("1234")
        val data = pin.getAndClear()

        data.close()

        assertEquals(0, data.length)
        assertFailsWith<IllegalStateException> { data.useChars {} }
    }

    @Test
    fun contentEquals_compares_without_clearing_either_pin() {
        val pinA = securePinOf("1234")
        val pinB = securePinOf("1234")
        val pinC = securePinOf("4321")

        assertTrue(pinA.contentEquals(pinB))
        assertFalse(pinA.contentEquals(pinC))
        // Comparing must not consume: the PIN screens compare a confirmation against the first entry
        // and still need both afterwards.
        assertFalse(pinA.isCleared)
        assertFalse(pinB.isCleared)
        assertFalse(pinC.isCleared)

        pinA.close()
        pinB.close()
        pinC.close()
    }

    @Test
    fun a_pin_of_a_different_length_is_never_equal() {
        val pin = securePinOf("1234")
        val longer = securePinOf("12345")

        assertFalse(pin.contentEquals(longer))

        pin.close()
        longer.close()
    }

    @Test
    fun comparing_a_cleared_pin_fails_rather_than_reporting_a_match() {
        val pin = securePinOf("1234")
        val other = securePinOf("1234")
        pin.close()

        // Returning false would be worse than throwing: a cleared PIN is a bug at the call site, and
        // "not equal" reads as a wrong PIN.
        assertFailsWith<IllegalStateException> { pin.contentEquals(other) }

        other.close()
    }

    @Test
    fun neither_toString_nor_equals_exposes_the_pin() {
        val pin = securePinOf("987654")
        val sameValue = securePinOf("987654")

        assertFalse(pin.toString().contains("987654"))
        // Identity equality, deliberately: two pins with the same characters are different objects, and
        // an `equals` that compared content would leak through a log or an assertion message.
        assertNotEquals(pin, sameValue)

        val data = pin.getAndClear()
        assertFalse(data.toString().contains("987654"))

        data.close()
        pin.close()
        sameValue.close()
    }

    @Test
    fun the_characters_are_zeroed_rather_than_released() {
        val pin = securePinOf("1234")
        val data = pin.getAndClear()

        // The array handed to `useChars` is the live one, so what `close` does to it is observable —
        // which is the whole reason this type exists instead of a String.
        val chars: CharArray = data.useChars { it }
        data.close()

        assertContentEquals(CharArray(4) { '\u0000' }, chars)
    }
}
