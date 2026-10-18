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

package eu.europa.ec.shared.platform

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The in-process bus behind `SystemBroadcastReceiver` on iOS, and what a receiver can read off it. */
class IosBroadcastsTest {

    private fun TestScope.received(actions: List<String>): List<PlatformIntent> {
        val received = mutableListOf<PlatformIntent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            IosBroadcasts.receive(actions).toList(received)
        }
        return received
    }

    @Test
    fun a_receiver_gets_only_the_actions_it_listens_for() = runTest {
        val received = received(listOf("test.wanted"))

        IosBroadcasts.send(PlatformIntent(action = "test.other"))
        IosBroadcasts.send(PlatformIntent(action = "test.wanted"))

        assertEquals(listOf("test.wanted"), received.map { it.platformAction() })
    }

    @Test
    fun a_broadcast_sent_before_anyone_listens_is_not_replayed() = runTest {
        IosBroadcasts.send(PlatformIntent(action = "test.early"))

        val received = received(listOf("test.early"))

        // A screen composed after the event must not act on it, exactly as with an Android broadcast.
        assertTrue(received.isEmpty())
    }

    @Test
    fun the_accessors_read_what_the_broadcast_carries() {
        val intent = PlatformIntent(
            action = "test.action",
            stringExtras = mapOf("uri" to "eu.europa.ec.euidi://authorization?code=1"),
            stringListExtras = mapOf("ids" to listOf("a", "b")),
        )

        assertEquals("test.action", intent.platformAction())
        assertEquals("eu.europa.ec.euidi://authorization?code=1", intent.platformStringExtra("uri"))
        assertEquals(listOf("a", "b"), intent.platformStringListExtra("ids"))
    }

    @Test
    fun an_absent_extra_reads_as_null_or_empty_as_on_android() {
        val intent = PlatformIntent()

        assertNull(intent.platformAction())
        assertNull(intent.platformStringExtra("uri"))
        assertEquals(emptyList(), intent.platformStringListExtra("ids"))
    }
}
