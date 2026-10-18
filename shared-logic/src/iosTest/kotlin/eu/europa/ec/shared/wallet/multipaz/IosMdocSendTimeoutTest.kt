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

// A reader that goes away in the middle of a response: multipaz's iOS BLE send never learns of it and waits
// for ever. These cases hold the send and the cleanup after it to their bounds.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.presentment.Iso18013PresentmentTimeoutException
import org.multipaz.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class IosMdocSendTimeoutTest {

    /** A transport whose sends take [sendTakes], or never finish when it is null: a reader that left. */
    private class Transport(private val sendTakes: Duration?) : MdocTransport() {
        var sendsCancelled = 0
        var sent = 0

        override val state: StateFlow<State> = MutableStateFlow(State.CONNECTED)
        override val role: MdocRole = MdocRole.MDOC
        override val connectionMethod: MdocConnectionMethod = MdocConnectionMethodBle(
            supportsPeripheralServerMode = true,
            supportsCentralClientMode = false,
            peripheralServerModeUuid = UUID.randomUUID(),
            centralClientModeUuid = null,
        )
        override val scanningTime: Duration? = null

        override suspend fun advertise() = Unit
        override suspend fun open(eSenderKey: EcPublicKey) = Unit
        override suspend fun waitForMessage(): ByteArray = awaitCancellation()
        override suspend fun close() = Unit

        override suspend fun sendMessage(message: ByteArray) {
            try {
                if (sendTakes == null) awaitCancellation() else kotlinx.coroutines.delay(sendTakes)
                sent++
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                sendsCancelled++
                throw cancelled
            }
        }
    }

    @Test
    fun a_response_the_reader_never_takes_fails_after_the_bound_instead_of_waiting_for_ever() = runTest {
        val transport = Transport(sendTakes = null)

        assertFailsWith<Iso18013PresentmentTimeoutException> {
            sendResponse(transport, ByteArray(23_789), timeout = 30.seconds)
        }

        assertEquals(30_000, currentTime)
        // Cancelled, which is what makes multipaz fail its transport so the cleanup cannot hang on it.
        assertEquals(1, transport.sendsCancelled)
    }

    @Test
    fun a_response_taken_in_time_is_sent() = runTest {
        val transport = Transport(sendTakes = 2.seconds)

        sendResponse(transport, ByteArray(23_789), timeout = 30.seconds)

        assertEquals(1, transport.sent)
    }

    @Test
    fun the_cleanup_does_not_wait_for_a_reader_that_is_gone() = runTest {
        val transport = Transport(sendTakes = null)

        terminateSession(transport, timeout = 5.seconds)

        // Returned, and within its bound: the exchange can end and the screen can say so.
        assertTrue(currentTime <= 5_000, "took ${currentTime} ms")
        assertEquals(1, transport.sendsCancelled)
    }
}
