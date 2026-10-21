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

package eu.europa.ec.shared.wallet.multipaz

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecureRandomTest {

    /**
     * The control, and the reason [SecureRandom] exists: on this runtime the default generator gives the
     * PKCE verifier away through the `state` drawn after it, which travels back in the redirect URL.
     * Should Kotlin/Native ever make `Random.Default` cryptographic, this starts failing — and
     * [SecureRandom] can go.
     */
    @Test
    fun the_default_generator_gives_the_pkce_verifier_away_through_the_state() {
        // A few tries only because `Random.Default` is process-wide: another thread drawing between the two
        // values would shift the steps. One recovery is the whole point.
        val recovered = (1..3).any {
            val (verifier, state) = verifierThenState(Random.Default)
            verifierRecoveredFrom(state).any { it.contentEquals(verifier) }
        }

        assertTrue(recovered, "Random.Default is no longer the clock-seeded LCG")
    }

    @Test
    fun the_secure_generator_does_not() {
        repeat(16) {
            val (verifier, state) = verifierThenState(SecureRandom)

            assertFalse(verifierRecoveredFrom(state).any { it.contentEquals(verifier) })
        }
    }

    @Test
    fun only_the_requested_range_is_filled() {
        val array = ByteArray(64) { SENTINEL }

        SecureRandom.nextBytes(array, fromIndex = 16, toIndex = 48)

        assertTrue((0 until 16).all { array[it] == SENTINEL } && (48 until 64).all { array[it] == SENTINEL })
        assertFalse((16 until 48).all { array[it] == SENTINEL })
        assertFailsWith<IllegalArgumentException> { SecureRandom.nextBytes(array, fromIndex = 48, toIndex = 16) }
        assertFailsWith<IllegalArgumentException> { SecureRandom.nextBytes(array, fromIndex = 0, toIndex = 65) }
    }

    @Test
    fun bits_stay_within_their_width() {
        assertEquals(0, SecureRandom.nextBits(0))
        for (bitCount in 1 until Int.SIZE_BITS) {
            repeat(32) {
                assertTrue(SecureRandom.nextBits(bitCount).toLong() in 0L until (1L shl bitCount), "bitCount $bitCount")
            }
        }
        assertFailsWith<IllegalArgumentException> { SecureRandom.nextBits(Int.SIZE_BITS + 1) }
    }

    @Test
    fun draws_differ() {
        assertFalse(SecureRandom.nextBytes(PKCE_BYTES).contentEquals(SecureRandom.nextBytes(PKCE_BYTES)))
        assertNotEquals(SecureRandom.nextUuid(), SecureRandom.nextUuid())
    }

    /** What `IosOpenID4VciProvisioningClient.challenge()` draws, in its order. */
    private fun verifierThenState(random: Random) = random.nextBytes(PKCE_BYTES) to random.nextBytes(STATE_BYTES)

    /**
     * What an observer of the redirect URL can compute, modelled on Kotlin/Native 2.4.10's `NativeRandom`
     * (`seed * 0x5DEECE66D + 0xB` over 48 bits, `nextBits(n)` = the top n bits) and the common `nextBytes`
     * (one `nextInt()` per four bytes, little-endian, then `nextBits(remainder * 8)` — called even for none).
     *
     * The state's first four bytes are the top 32 bits of the generator; the low 16 are found by checking
     * each candidate against the next four. Ten steps back from there — the state's first draw, the
     * verifier's empty remainder and its eight `nextInt()`s — is where the verifier began.
     */
    private fun verifierRecoveredFrom(state: ByteArray): List<ByteArray> {
        val first = state.intAt(0)
        val second = state.intAt(4)
        return (0L until (1L shl 16))
            .map { low -> (first shl 16) or low }
            .filter { seed -> step(seed) ushr 16 == second }
            .map { seed ->
                var start = seed
                repeat(10) { start = stepBack(start) }
                ByteArray(PKCE_BYTES).also { verifier ->
                    var current = start
                    for (offset in 0 until PKCE_BYTES step Int.SIZE_BYTES) {
                        current = step(current)
                        val value = current ushr 16
                        for (i in 0 until Int.SIZE_BYTES) verifier[offset + i] = (value ushr (8 * i)).toByte()
                    }
                }
            }
    }

    private fun ByteArray.intAt(offset: Int): Long =
        (0 until Int.SIZE_BYTES).fold(0L) { value, i -> value or ((this[offset + i].toLong() and 0xFF) shl (8 * i)) }

    private fun step(seed: Long) = (seed * MULTIPLIER + INCREMENT) and MASK

    private fun stepBack(seed: Long) = ((seed - INCREMENT) * MULTIPLIER_INVERSE) and MASK

    private companion object {
        // IosOpenID4VciProvisioningClient's own sizes (its companion is private).
        const val PKCE_BYTES = 32
        const val STATE_BYTES = 15
        const val SENTINEL: Byte = 0x5A
        const val MULTIPLIER = 0x5DEECE66DL
        const val MULTIPLIER_INVERSE = 0xDFE05BCB1365L
        const val INCREMENT = 0xBL
        const val MASK = (1L shl 48) - 1
    }
}
