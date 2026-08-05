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

// Phase 3b: the `SecurePin` / `SecurePinData` *interfaces* now live in :shared-logic/commonMain so the
// PIN view-models can move there. These implementations stay Android-side: their mutual exclusion is
// `@Synchronized`, which is JVM-only, and picking a Kotlin/Native equivalent for a security primitive
// is a decision worth making when iOS actually needs one. Package unchanged.
package eu.europa.ec.authenticationlogic.secure

class SecurePinImpl(text: CharSequence) : SecurePin {
    private var chars: CharArray? = CharArray(text.length) { index -> text[index] }

    override val length: Int = text.length

    override val isCleared: Boolean
        @Synchronized get() = chars == null

    @Synchronized
    override fun getAndClear(): SecurePinData {
        val current = chars ?: throw IllegalStateException("PIN has already been cleared")
        chars = null
        return SecurePinDataImpl(current)
    }

    override fun getAndClearAsString(): String {
        val pinData = getAndClear()
        return pinData.use { pinData ->
            pinData.useChars { chars ->
                String(chars)
            }
        }
    }

    override fun contentEquals(other: SecurePin): Boolean {
        if (length != other.length) return false
        if (other !is SecurePinImpl) return false

        val left = snapshot() ?: throw IllegalStateException("PIN has already been cleared")
        val right = other.snapshot() ?: throw IllegalStateException("PIN has already been cleared")

        var diff = 0
        for (index in left.indices) {
            diff = diff or (left[index].code xor right[index].code)
        }
        return diff == 0
    }

    @Synchronized
    override fun close() {
        chars?.fill(CLEARED_CHAR)
        chars = null
    }

    @Synchronized
    private fun snapshot(): CharArray? = chars

    override fun toString(): String = "SecurePin[$length chars]"

    private companion object {
        const val CLEARED_CHAR = '\u0000'
    }
}

internal class SecurePinDataImpl(
    private var chars: CharArray?
) : SecurePinData {

    override val length: Int
        @Synchronized get() = chars?.size ?: 0

    @Synchronized
    override fun <T> useChars(block: (CharArray) -> T): T {
        val current = chars ?: throw IllegalStateException("PIN data has already been cleared")
        return block(current)
    }

    @Synchronized
    override fun close() {
        chars?.fill(CLEARED_CHAR)
        chars = null
    }

    override fun toString(): String = "[redacted]"

    private companion object {
        const val CLEARED_CHAR = '\u0000'
    }
}