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

// Where the registration-check switch is kept: the app group, which the Digital Credentials extension can read,
// carrying over a value the app stored in its own defaults before 2026-10-02.
package eu.europa.ec.shared.wallet.platform

import platform.Foundation.NSUserDefaults
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationCheckStoreTest {

    private val suites = mutableListOf<String>()

    /** A throwaway defaults domain, removed after the test. */
    private fun defaults(): NSUserDefaults {
        val name = "eu.europa.ec.test.registration-check.${Random.nextLong().toULong()}"
        suites += name
        return NSUserDefaults(suiteName = name)
    }

    @AfterTest
    fun removeDomains() {
        suites.forEach { NSUserDefaults.standardUserDefaults.removePersistentDomainForName(it) }
    }

    @Test
    fun the_check_is_off_until_the_user_turns_it_on() {
        assertFalse(RegistrationCheckStore(shared = defaults(), own = defaults()).isEnabled())
    }

    @Test
    fun turning_it_on_is_stored_where_the_extension_reads_it() {
        val shared = defaults()
        val own = defaults()

        RegistrationCheckStore(shared, own).setEnabled(true)

        // What the extension does: its own defaults are empty, the group's are the app's.
        assertTrue(RegistrationCheckStore(shared = shared, own = defaults()).isEnabled())
        assertNull(own.objectForKey("RegistrationCheckEnabled"))
    }

    @Test
    fun a_switch_turned_on_before_the_move_stays_on_and_is_carried_over() {
        val shared = defaults()
        val own = defaults()
        own.setBool(true, "RegistrationCheckEnabled")

        assertTrue(RegistrationCheckStore(shared, own).isEnabled(), "the app still reads it as on")
        assertTrue(RegistrationCheckStore(shared = shared, own = defaults()).isEnabled(), "and so does the extension")
    }

    @Test
    fun once_in_the_group_the_groups_answer_wins() {
        val shared = defaults()
        val own = defaults()
        own.setBool(true, "RegistrationCheckEnabled")
        val store = RegistrationCheckStore(shared, own)
        store.isEnabled()

        store.setEnabled(false)

        // The old value in the app's own defaults must not bring the check back on.
        assertFalse(store.isEnabled())
    }

    @Test
    fun without_an_app_group_the_apps_own_defaults_are_used() {
        val own = defaults()
        val store = RegistrationCheckStore(shared = null, own = own)

        store.setEnabled(true)

        assertTrue(store.isEnabled())
        assertTrue(own.boolForKey("RegistrationCheckEnabled"))
    }
}
