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

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * iOS's [SecurePin], a line-for-line counterpart of Android's `SecurePinImpl` with one difference: where
 * that one uses `@Synchronized`, this holds an explicit lock.
 *
 * **Why a lock at all**, when the PIN is typed and consumed on the main thread: because *this* type is
 * what makes clearing safe. Every one-shot operation here is read-then-null (`getAndClear`) or
 * fill-then-null (`close`), and those are exactly the sequences that must not interleave — otherwise two
 * callers can both take the characters, or one can zero the array another is reading. The invariant the
 * Android implementation states with `@Synchronized` is the same invariant here; only the mechanism
 * differs, so it is spelled out rather than dropped as "probably single-threaded".
 *
 * `kotlinx.atomicfu.locks.ReentrantLock` rather than a hand-rolled `pthread_mutex`: it is the
 * multiplatform lock the Kotlin ecosystem already standardises on — coroutines and multipaz both use it,
 * so it is already on this module's iOS classpath.
 */
internal class NativeSecurePin(text: CharSequence) : SecurePin {

    private val lock: ReentrantLock = reentrantLock()
    private var chars: CharArray? = CharArray(text.length) { index -> text[index] }

    override val length: Int = text.length

    override val isCleared: Boolean
        get() = lock.withLock { chars == null }

    override fun getAndClear(): SecurePinData = lock.withLock {
        val current = chars ?: throw IllegalStateException("PIN has already been cleared")
        chars = null
        NativeSecurePinData(current)
    }

    override fun getAndClearAsString(): String {
        val pinData = getAndClear()
        return pinData.use { data ->
            data.useChars { chars ->
                chars.concatToString()
            }
        }
    }

    override fun contentEquals(other: SecurePin): Boolean {
        if (length != other.length) return false
        if (other !is NativeSecurePin) return false

        val left = snapshot() ?: throw IllegalStateException("PIN has already been cleared")
        val right = other.snapshot() ?: throw IllegalStateException("PIN has already been cleared")

        // Constant-time, as on Android: comparing PINs must not leak where they start to differ.
        var diff = 0
        for (index in left.indices) {
            diff = diff or (left[index].code xor right[index].code)
        }
        return diff == 0
    }

    override fun close() {
        lock.withLock {
            chars?.fill(CLEARED_CHAR)
            chars = null
        }
    }

    private fun snapshot(): CharArray? = lock.withLock { chars }

    override fun toString(): String = "SecurePin[$length chars]"

    private companion object {
        const val CLEARED_CHAR = '\u0000'
    }
}

internal class NativeSecurePinData(
    private var chars: CharArray?,
) : SecurePinData {

    private val lock: ReentrantLock = reentrantLock()

    override val length: Int
        get() = lock.withLock { chars?.size ?: 0 }

    override fun <T> useChars(block: (CharArray) -> T): T = lock.withLock {
        val current = chars ?: throw IllegalStateException("PIN data has already been cleared")
        block(current)
    }

    override fun close() {
        lock.withLock {
            chars?.fill(CLEARED_CHAR)
            chars = null
        }
    }

    override fun toString(): String = "[redacted]"

    private companion object {
        const val CLEARED_CHAR = '\u0000'
    }
}
