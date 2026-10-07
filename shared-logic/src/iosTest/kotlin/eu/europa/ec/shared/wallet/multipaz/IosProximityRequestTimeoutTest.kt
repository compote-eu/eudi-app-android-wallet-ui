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

// A reader that connects and then sends nothing: multipaz's iOS BLE peripheral gives no sign that it left,
// so the bound on the wait for its request is what ends the exchange.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.presentment.Iso18013PresentmentTimeoutException
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

class IosProximityRequestTimeoutTest {

    /** A connected reader that never sends a message. */
    private class SilentReader : MdocTransport() {
        var closed = false

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
        override suspend fun sendMessage(message: ByteArray) = Unit
        override suspend fun close() {
            closed = true
        }
    }

    @Test
    fun a_reader_that_connects_and_sends_nothing_is_given_up_on_after_fifteen_seconds() = runTest {
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )
        val transport = SilentReader()

        assertFailsWith<Iso18013PresentmentTimeoutException> {
            iosIso18013Presentment(
                transport = transport,
                eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256),
                deviceEngagement = Simple.NULL,
                handover = Simple.NULL,
                source = SimplePresentmentSource(
                    documentStore = store.documentStore,
                    documentTypeRepository = DocumentTypeRepository(),
                    domainsMdocSignature = listOf(store.documentManagerId),
                    showConsentPromptFn = { _, _, _, _, _ -> null },
                ),
                keyAgreementPossible = listOf(EcCurve.P256),
                timeout = PROXIMITY_REQUEST_TIMEOUT,
            )
        }

        assertEquals(15_000, currentTime)
        // Closed on the way out, as at the end of every exchange.
        assertTrue(transport.closed)
    }
}
