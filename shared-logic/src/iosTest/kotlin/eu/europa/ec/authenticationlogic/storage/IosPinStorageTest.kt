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

// iOS's PIN verifier. The first case is the one that matters most: it holds CommonCrypto's derivation to
// published PBKDF2-HMAC-SHA256 vectors, so "iOS hashes the PIN the same way Android does" is a checked
// claim rather than an intention. The rest cover what the store does around it.
//
// The Keychain itself is faked here on purpose — a unit-test binary has no keychain-access entitlement and
// `SecItemAdd` answers -34018 — so the real one is exercised in-app by the wallet probe instead.
package eu.europa.ec.authenticationlogic.storage

import eu.europa.ec.authenticationlogic.secure.securePinOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class InMemorySecretStore : IosSecretStore {
    private val entries = mutableMapOf<String, ByteArray>()

    var writes: Int = 0
        private set

    override fun read(account: String): ByteArray? = entries[account]?.copyOf()

    override fun write(account: String, value: ByteArray) {
        writes++
        entries[account] = value.copyOf()
    }

    override fun delete(account: String) {
        entries.remove(account)
    }

    fun keys(): Set<String> = entries.keys.toSet()
}

class IosPinStorageTest {

    private fun ByteArray.hex(): String = joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    @Test
    fun the_derivation_matches_published_pbkdf2_hmac_sha256_vectors() {
        val storage = IosPinStorage(InMemorySecretStore())

        // password="password", salt="salt", 32-byte output.
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            storage.derive(
                chars = "password".toCharArray(),
                salt = "salt".encodeToByteArray(),
                iterations = 1,
            ).hex(),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            storage.derive(
                chars = "password".toCharArray(),
                salt = "salt".encodeToByteArray(),
                iterations = 4096,
            ).hex(),
        )
    }

    @Test
    fun the_wallets_own_parameters_derive_what_they_should() {
        val storage = IosPinStorage(InMemorySecretStore())

        // A four-digit PIN at this wallet's iteration count, against a reference computed independently.
        // Pins both the iteration count and that the PIN is hashed as UTF-8 of its digits.
        assertEquals(
            "3b834492cb7b11b39c23bca601ebb9a41862f5c74e42e7c1492f5876304f3740",
            storage.derive(
                chars = "1234".toCharArray(),
                salt = ByteArray(16),
                iterations = 210_000,
            ).hex(),
        )
    }

    @Test
    fun a_stored_pin_is_accepted_and_a_wrong_one_is_not() = runTest {
        val store = InMemorySecretStore()
        val storage = IosPinStorage(store)

        storage.setPin(securePinOf("123456"))

        assertTrue(storage.hasPin())
        assertTrue(storage.isPinValid(securePinOf("123456")))
        assertFalse(storage.isPinValid(securePinOf("654321")))
    }

    @Test
    fun the_pin_itself_is_never_stored() = runTest {
        val store = InMemorySecretStore()

        IosPinStorage(store).setPin(securePinOf("123456"))

        // Only a salt, a hash and the iteration count — the check that this is a verifier, not a vault.
        assertEquals(setOf("PinSalt", "PinHash", "PinIterations"), store.keys())
        assertFalse(
            store.read("PinHash")!!.decodeToString().contains("123456"),
            "the hash must not contain the PIN",
        )
        assertEquals("210000", store.read("PinIterations")!!.decodeToString())
    }

    @Test
    fun each_pin_gets_its_own_salt() = runTest {
        val first = InMemorySecretStore().also { IosPinStorage(it).setPin(securePinOf("123456")) }
        val second = InMemorySecretStore().also { IosPinStorage(it).setPin(securePinOf("123456")) }

        // Same PIN, different salts, therefore different hashes — otherwise a stolen store would say
        // which users share a PIN.
        assertFalse(first.read("PinSalt")!!.contentEquals(second.read("PinSalt")!!))
        assertFalse(first.read("PinHash")!!.contentEquals(second.read("PinHash")!!))
    }

    @Test
    fun nothing_is_valid_before_a_pin_is_set() = runTest {
        val storage = IosPinStorage(InMemorySecretStore())

        assertFalse(storage.hasPin())
        assertFalse(storage.isPinValid(securePinOf("123456")))
    }

    @Test
    fun an_empty_pin_is_refused_without_touching_the_store() = runTest {
        val store = InMemorySecretStore()
        val storage = IosPinStorage(store)
        storage.setPin(securePinOf("123456"))
        val writesAfterSetup = store.writes

        assertFalse(storage.isPinValid(securePinOf("")))
        assertEquals(writesAfterSetup, store.writes)
    }

    @Test
    fun changing_the_pin_replaces_what_was_stored() = runTest {
        val store = InMemorySecretStore()
        val storage = IosPinStorage(store)
        storage.setPin(securePinOf("123456"))

        storage.setPin(securePinOf("654321"))

        assertTrue(storage.isPinValid(securePinOf("654321")))
        assertFalse(storage.isPinValid(securePinOf("123456")))
    }

    @Test
    fun clearing_leaves_no_pin_behind() = runTest {
        val store = InMemorySecretStore()
        val storage = IosPinStorage(store)
        storage.setPin(securePinOf("123456"))

        storage.clear()

        assertFalse(storage.hasPin())
        assertTrue(store.keys().isEmpty())
    }
}
