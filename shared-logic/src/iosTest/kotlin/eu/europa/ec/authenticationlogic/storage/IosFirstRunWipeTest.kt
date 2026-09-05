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

import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Keychain itself is faked, for the same reason [IosPinStorageTest] fakes it: a unit-test binary is
 * not an app and has no keychain at all — every real `SecItem` call returns `errSecNotAvailable`. What
 * is under test here is the *decision*, which is the part that can be wrong: when to wipe, when to leave
 * well alone, and when to refuse to record that the job is done.
 *
 * That the items genuinely outlive an uninstall is a separate, measured fact and not something a test
 * could show: written by one install, read back by the next, on a simulator.
 */
class IosFirstRunWipeTest {

    private val defaults = NSUserDefaults.standardUserDefaults

    @BeforeTest
    fun setUp() = clearFlag()

    @AfterTest
    fun tearDown() = clearFlag()

    private fun clearFlag() = defaults.removeObjectForKey(RUN_AT_LEAST_ONCE)

    @Test
    fun a_fresh_install_drops_everything_the_previous_one_left() {
        val keychain = InMemoryKeychain(
            IosPinStorage.KEY_SALT to byteArrayOf(1),
            IosPinStorage.KEY_HASH to byteArrayOf(2),
            IosPinStorage.KEY_ITERATIONS to "210000".encodeToByteArray(),
        )
        var forgotBiometrics = false

        val wiped = clearSecretsLeftByAPreviousInstall(
            defaults = defaults,
            pinSecrets = keychain,
            forgetBiometricEnrolment = { forgotBiometrics = true },
        )

        assertTrue(wiped, "the first run after an install has to wipe")
        assertNull(keychain.read(IosPinStorage.KEY_SALT))
        assertNull(keychain.read(IosPinStorage.KEY_HASH))
        assertNull(keychain.read(IosPinStorage.KEY_ITERATIONS))
        assertTrue(forgotBiometrics, "biometric enrolment outlives an uninstall too")
    }

    @Test
    fun the_second_launch_leaves_the_users_own_pin_alone() {
        val keychain = InMemoryKeychain()
        clearSecretsLeftByAPreviousInstall(defaults, keychain) {}

        // The user sets a PIN, as they would after the wipe above.
        keychain.write(IosPinStorage.KEY_HASH, byteArrayOf(9))
        var forgotBiometrics = false

        val wiped = clearSecretsLeftByAPreviousInstall(
            defaults = defaults,
            pinSecrets = keychain,
            forgetBiometricEnrolment = { forgotBiometrics = true },
        )

        assertFalse(wiped, "an install that has run before must not wipe again")
        assertEquals(1, keychain.read(IosPinStorage.KEY_HASH)?.size)
        assertFalse(forgotBiometrics, "and must not silently turn biometrics off either")
    }

    @Test
    fun a_wipe_that_did_not_take_is_retried_on_the_next_launch() {
        // A keychain that accepts the delete and keeps the item anyway — the shape of any Security
        // framework failure, which IosSecretStore.delete reports by not reporting.
        val stubborn = InMemoryKeychain(IosPinStorage.KEY_HASH to byteArrayOf(2)).apply {
            ignoreDeletes = true
        }

        assertFalse(
            clearSecretsLeftByAPreviousInstall(defaults, stubborn) {},
            "a failed wipe must not claim to have succeeded",
        )
        assertFalse(
            defaults.boolForKey(RUN_AT_LEAST_ONCE),
            "and must not record the install as clean, or it would never try again",
        )

        stubborn.ignoreDeletes = false
        assertTrue(
            clearSecretsLeftByAPreviousInstall(defaults, stubborn) {},
            "the next launch gets another go",
        )
    }
}

private class InMemoryKeychain(vararg initial: Pair<String, ByteArray>) : IosSecretStore {
    private val values = initial.toMap().toMutableMap()
    var ignoreDeletes = false

    override fun read(account: String): ByteArray? = values[account]

    override fun write(account: String, value: ByteArray) {
        values[account] = value
    }

    override fun delete(account: String) {
        if (!ignoreDeletes) values.remove(account)
    }
}
