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
// The one place production code may import `kotlin.random.Random`: detekt's `ForbiddenImport` refuses it
// everywhere else, and `org.multipaz.util.UUID` with it (see detekt.yml).
@file:Suppress("ForbiddenImport")

package eu.europa.ec.shared.wallet.multipaz

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.multipaz.util.UUID
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import kotlin.random.Random

/**
 * Random bytes from the platform's cryptographic generator, `SecRandomCopyBytes`, for every value an
 * attacker must not be able to guess: PKCE verifiers, OAuth `state`, `jti`s, JWE nonces, BLE service UUIDs.
 *
 * `kotlin.random.Random.Default` is not that. On Kotlin/Native (2.4.10, read in its runtime source) it is
 * a 48-bit linear congruential generator seeded from the clock, so one output gives away the ones before
 * and after it — the PKCE verifier could be computed from the `state` that follows it into the redirect URL.
 * `SecureRandomTest` shows exactly that on the real runtime, and that it fails against this object.
 *
 * multipaz has its own, `Crypto.secureRandom`, on `main` since 2026-09-14 (after 0.101.0); at the first
 * release with it this object can go. Until then multipaz 0.101.0 itself still draws a few values from
 * `Random.Default` (the OpenID4VP wallet nonce, the JWE IV unless one passes a generator), which only that
 * bump fixes.
 */
@OptIn(ExperimentalForeignApi::class)
internal object SecureRandom : Random() {

    override fun nextBits(bitCount: Int): Int {
        require(bitCount in 0..Int.SIZE_BITS) { "bitCount must be between 0 and 32, was $bitCount" }
        if (bitCount == 0) return 0
        val bytes = nextBytes(Int.SIZE_BYTES)
        val value = (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)
        return value ushr (Int.SIZE_BITS - bitCount)
    }

    override fun nextBytes(array: ByteArray, fromIndex: Int, toIndex: Int): ByteArray {
        require(fromIndex in 0..array.size && toIndex in 0..array.size && fromIndex <= toIndex) {
            "fromIndex ($fromIndex) or toIndex ($toIndex) out of range [0, ${array.size}]"
        }
        val length = toIndex - fromIndex
        if (length == 0) return array
        val status = array.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, length.convert(), pinned.addressOf(fromIndex))
        }
        // Never fall back to a weaker generator: a value nobody can trust is worse than a failed request.
        check(status == 0) { "SecRandomCopyBytes failed with status $status" }
        return array
    }

    /** A random UUID for a BLE service. multipaz 0.101.0's `UUID.randomUUID()` defaults to `Random.Default`. */
    fun nextUuid(): UUID = UUID.randomUUID(this)
}
