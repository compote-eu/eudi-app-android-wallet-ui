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

// Leaving the QR before any reader connects: multipaz cancels its wait for a reader and closes nothing,
// so the presenter has to close what it advertised.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

class IosProximityCancelTest {

    /** A BLE peripheral advertising its QR, waiting in `open()` for a reader that never comes. */
    private class WaitingForReader(override val connectionMethod: MdocConnectionMethod) : MdocTransport() {
        var closed = false

        override val state: StateFlow<State> = MutableStateFlow(State.ADVERTISING)
        override val role: MdocRole = MdocRole.MDOC
        override val scanningTime: Duration? = null

        override suspend fun advertise() = Unit
        override suspend fun open(eSenderKey: EcPublicKey) = awaitCancellation()
        override suspend fun waitForMessage(): ByteArray = awaitCancellation()
        override suspend fun sendMessage(message: ByteArray) = Unit
        override suspend fun close() {
            closed = true
        }
    }

    @Test
    fun leaving_the_qr_before_a_reader_connects_closes_what_was_advertised() = runTest {
        val advertised = mutableListOf<WaitingForReader>()
        val presenter = IosProximityPresenter(
            walletEngine = IosWalletEngine(),
            credentialDomain = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
            scope = backgroundScope,
            readerTrust = null,
            isRegistrationCheckEnabled = { false },
            transportFactory = object : MdocTransportFactory {
                override fun createTransport(
                    connectionMethod: MdocConnectionMethod,
                    role: MdocRole,
                    options: MdocTransportOptions,
                ): MdocTransport = WaitingForReader(connectionMethod).also { advertised += it }
            },
        )

        presenter.startQrEngagement()
        runCurrent()
        assertIs<IosProximityState.Engaging>(presenter.state.value)

        presenter.cancel()
        runCurrent()

        assertTrue(advertised.isNotEmpty())
        assertTrue(advertised.all { it.closed }, "a transport was left advertising")
    }
}
