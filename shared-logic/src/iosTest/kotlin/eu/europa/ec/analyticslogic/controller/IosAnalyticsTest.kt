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

// The fan-out, and the two properties that decide whether analytics is safe to put on the navigation
// path: nothing registered must be a silent no-op, and a provider that throws must not take the app
// down on a screen change.
package eu.europa.ec.analyticslogic.controller

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosAnalyticsTest {

    private class Recording : IosAnalyticsProvider {
        val initializedWith: MutableList<String> = mutableListOf()
        val screens: MutableList<Pair<String, Map<String, String>>> = mutableListOf()
        val events: MutableList<Pair<String, Map<String, String>>> = mutableListOf()

        override fun initialize(key: String) {
            initializedWith += key
        }

        override fun logScreen(name: String, arguments: Map<String, String>) {
            screens += name to arguments
        }

        override fun logEvent(eventName: String, arguments: Map<String, String>) {
            events += eventName to arguments
        }
    }

    private class Exploding : IosAnalyticsProvider {
        override fun initialize(key: String) = Unit
        override fun logScreen(name: String, arguments: Map<String, String>): Unit =
            throw IllegalStateException("provider is broken")

        override fun logEvent(eventName: String, arguments: Map<String, String>): Unit =
            throw IllegalStateException("provider is broken")
    }

    // The object is process-wide, so every test starts and ends from a known state.
    @BeforeTest
    fun setUp() = IosAnalytics.resetForTest()

    @AfterTest
    fun tearDown() = IosAnalytics.resetForTest()

    @Test
    fun with_nothing_registered_reporting_is_a_no_op() {
        // The state of every build in this repo, and of both reference wallets. It must not throw,
        // because it sits on the navigation path.
        IosAnalytics.logScreen("DASHBOARD", mapOf("a" to "b"))
        IosAnalytics.logEvent("something")
        IosAnalytics.initialize()
    }

    @Test
    fun a_registered_provider_receives_screens_and_events() {
        val provider = Recording()
        IosAnalytics.register("firebase", provider)

        IosAnalytics.logScreen("DOCUMENT_DETAILS", mapOf("documentId" to "abc"))
        IosAnalytics.logEvent("deleted", mapOf("kind" to "pid"))

        assertEquals(listOf("DOCUMENT_DETAILS" to mapOf("documentId" to "abc")), provider.screens)
        assertEquals(listOf("deleted" to mapOf("kind" to "pid")), provider.events)
    }

    @Test
    fun initialize_hands_each_provider_the_key_it_was_registered_under() {
        // The key is how both reference wallets pass a provider its token: they hold a
        // `[String: AnalyticsProvider]` map and pass the map key into `initialize`.
        val first = Recording()
        val second = Recording()
        IosAnalytics.register("firebase", first)
        IosAnalytics.register("other", second)

        IosAnalytics.initialize()

        assertEquals(listOf("firebase"), first.initializedWith)
        assertEquals(listOf("other"), second.initializedWith)
    }

    @Test
    fun registering_the_same_key_twice_replaces_rather_than_duplicates() {
        val replaced = Recording()
        val winner = Recording()
        IosAnalytics.register("firebase", replaced)
        IosAnalytics.register("firebase", winner)

        IosAnalytics.logScreen("DASHBOARD")

        assertTrue(replaced.screens.isEmpty(), "the replaced provider still received a screen")
        assertEquals(1, winner.screens.size)
    }

    @Test
    fun a_provider_that_throws_is_ignored_and_does_not_stop_the_others() {
        // This is the property that makes it safe to call from the navigation host: third-party code
        // on the navigation path must not be able to crash a screen change.
        val healthy = Recording()
        IosAnalytics.register("broken", Exploding())
        IosAnalytics.register("healthy", healthy)

        IosAnalytics.logScreen("DASHBOARD")
        IosAnalytics.logEvent("tapped")

        assertEquals(1, healthy.screens.size)
        assertEquals(1, healthy.events.size)
    }

    @Test
    fun arguments_default_to_empty_rather_than_being_required_at_every_call_site() {
        val provider = Recording()
        IosAnalytics.register("firebase", provider)

        IosAnalytics.logScreen("SPLASH")

        assertEquals(listOf("SPLASH" to emptyMap()), provider.screens)
    }
}
