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

import eu.europa.ec.authenticationlogic.controller.storage.PinStorageController
import eu.europa.ec.authenticationlogic.secure.SecurePin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS's PIN store: the same verifier Android keeps, in the Keychain instead of an encrypted DataStore.
 *
 * **Deliberately the identical scheme** — PBKDF2-HMAC-SHA256, a 32-byte random salt, 210 000 iterations,
 * a 256-bit derived key, and a constant-time comparison — because a PIN check that is cheaper on one
 * platform than the other is a weaker wallet on that platform, not a faster one. Only the two things that
 * *must* differ do: `CCKeyDerivationPBKDF` performs the derivation (CommonCrypto is iOS's
 * `PBKDF2WithHmacSHA256`), and the salt, hash and iteration count live in the Keychain.
 *
 * **Why the Keychain rather than `NSUserDefaults` or multipaz's storage:** Android encrypts this datastore
 * with a Tink keyset wrapped by a Keystore master key, so the verifier is protected at rest by hardware.
 * The Keychain is iOS's equivalent of that guarantee — and `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`
 * additionally keeps it off backups and off other devices, which is right for a wallet's local PIN.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPinStorage(
    private val keychain: IosSecretStore = IosKeychain(service = KEYCHAIN_SERVICE),
) : PinStorageController {

    override suspend fun hasPin(): Boolean {
        val salt = keychain.read(KEY_SALT)
        val hash = keychain.read(KEY_HASH)
        val iterations = keychain.read(KEY_ITERATIONS)?.decodeToString()?.toIntOrNull() ?: 0
        return salt != null && hash != null && iterations > 0
    }

    override suspend fun setPin(pin: SecurePin) {
        val salt = randomBytes(SALT_SIZE_BYTES)
        val data = pin.getAndClear()
        var hash: ByteArray? = null
        try {
            hash = data.useChars { chars ->
                derive(chars = chars, salt = salt, iterations = DEFAULT_ITERATIONS)
            }
            keychain.write(KEY_SALT, salt)
            keychain.write(KEY_HASH, hash)
            keychain.write(KEY_ITERATIONS, DEFAULT_ITERATIONS.toString().encodeToByteArray())
        } finally {
            data.close()
            salt.fill(0)
            hash?.fill(0)
        }
    }

    override suspend fun isPinValid(pin: SecurePin): Boolean {
        if (pin.length == 0) {
            pin.close()
            return false
        }

        val salt = keychain.read(KEY_SALT)
        val expected = keychain.read(KEY_HASH)
        val iterations = keychain.read(KEY_ITERATIONS)?.decodeToString()?.toIntOrNull() ?: 0
        if (salt == null || expected == null || iterations <= 0) {
            pin.close()
            return false
        }

        val data = pin.getAndClear()
        var candidate: ByteArray? = null
        try {
            candidate = data.useChars { chars ->
                derive(chars = chars, salt = salt, iterations = iterations)
            }
            return candidate.constantTimeEquals(expected)
        } finally {
            data.close()
            candidate?.fill(0)
            expected.fill(0)
        }
    }

    /** Drops the stored PIN. Not on the contract; used by tests and a future "reset wallet". */
    suspend fun clear() {
        listOf(KEY_SALT, KEY_HASH, KEY_ITERATIONS).forEach(keychain::delete)
    }

    /**
     * PBKDF2-HMAC-SHA256, `internal` so a test can hold it to published vectors: everything else here is
     * plumbing, and this is the part where being wrong would be silent.
     */
    internal fun derive(chars: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        // PBKDF2 takes the password as bytes; the PIN is ASCII digits, so this is UTF-8 of the same
        // characters Android hashes.
        val password = chars.concatToString().encodeToByteArray()
        val derived = ByteArray(HASH_SIZE_BITS / 8)
        try {
            val status = salt.usePinned { pinnedSalt ->
                derived.usePinned { pinnedDerived ->
                    CCKeyDerivationPBKDF(
                        algorithm = kCCPBKDF2,
                        // `const char *` in C, which Kotlin/Native binds as `String?`: it makes its own
                        // temporary copy of the characters, so unlike the arrays here that copy cannot be
                        // wiped afterwards. Android's `PBEKeySpec` copies too; noted rather than hidden.
                        password = chars.concatToString(),
                        passwordLen = password.size.convert(),
                        salt = pinnedSalt.addressOf(0).reinterpret(),
                        saltLen = salt.size.convert(),
                        prf = kCCPRFHmacAlgSHA256,
                        rounds = iterations.convert(),
                        derivedKey = pinnedDerived.addressOf(0).reinterpret(),
                        derivedKeyLen = derived.size.convert(),
                    )
                }
            }
            check(status == 0) { "PBKDF2 failed with status $status" }
            return derived.copyOf()
        } finally {
            password.fill(0)
            derived.fill(0)
        }
    }

    private fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        val status = bytes.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
        }
        check(status == 0) { "SecRandomCopyBytes failed with status $status" }
        return bytes
    }

    /**
     * `internal` rather than private since 2026-09-05: [clearSecretsLeftByAPreviousInstall] deletes
     * these exact accounts, and it has to name them from here or the two lists drift apart.
     */
    internal companion object {
        const val KEYCHAIN_SERVICE = "eu.europa.ec.eudi.wallet.pin"
        const val KEY_SALT = "PinSalt"
        const val KEY_HASH = "PinHash"
        const val KEY_ITERATIONS = "PinIterations"

        // The three numbers that must match Android's `PrefsPinStorageProvider`.
        const val SALT_SIZE_BYTES = 32
        const val HASH_SIZE_BITS = 256
        const val DEFAULT_ITERATIONS = 210_000
    }
}

/** Compares without an early exit, so the time taken says nothing about where two hashes differ. */
private fun ByteArray.constantTimeEquals(other: ByteArray): Boolean {
    if (size != other.size) return false
    var difference = 0
    for (index in indices) {
        difference = difference or (this[index].toInt() xor other[index].toInt())
    }
    return difference == 0
}
