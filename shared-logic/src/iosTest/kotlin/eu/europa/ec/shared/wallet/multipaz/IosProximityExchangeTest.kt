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

// The presenter from the QR to the end of an exchange, with a scripted reader in place of Bluetooth: how long
// a QR waits for a reader, and what the presenter still holds once an exchange is over.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.engagement.EngagementParser
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.fromBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val PID_DOC_TYPE = "eu.europa.ec.eudi.pid.1"

class IosProximityExchangeTest {

    /** The wallet's end of a BLE link, driven by the test: it connects if [connects], and hears what it is told. */
    private class ScriptedReader(
        override val connectionMethod: MdocConnectionMethod,
        private val connects: Boolean,
        private val advertises: Boolean,
    ) : MdocTransport() {
        private val mutableState = MutableStateFlow(State.ADVERTISING)
        override val state: StateFlow<State> = mutableState
        override val role: MdocRole = MdocRole.MDOC
        override val scanningTime: Duration? = null

        /** What the reader says next; an empty message is BLE's own "end". */
        val says = Channel<ByteArray>(Channel.UNLIMITED)
        val heard = mutableListOf<ByteArray>()
        var closed = false

        override suspend fun advertise() {
            if (!advertises) awaitCancellation()
        }

        override suspend fun open(eSenderKey: EcPublicKey) {
            if (!connects) awaitCancellation()
            mutableState.value = State.CONNECTED
        }

        override suspend fun waitForMessage(): ByteArray = says.receive()
        override suspend fun sendMessage(message: ByteArray) {
            heard += message
        }

        override suspend fun close() {
            closed = true
            mutableState.value = State.CLOSED
        }
    }

    private suspend fun walletWithPid(): MultipazWalletStore {
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )
        store.seedMdocDocument(
            docType = PID_DOC_TYPE,
            displayName = "PID",
            namespace = PID_DOC_TYPE,
            elements = samplePidElements(),
            policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
        )
        return store
    }

    private fun TestScope.presenter(
        store: MultipazWalletStore,
        readers: MutableList<ScriptedReader>,
        connects: Boolean,
        advertises: Boolean = true,
    ) = IosProximityPresenter(
        walletEngine = IosWalletEngine { store },
        credentialDomain = store.documentManagerId,
        scope = backgroundScope,
        readerTrust = null,
        isRegistrationCheckEnabled = { false },
        transportFactory = object : MdocTransportFactory {
            override fun createTransport(
                connectionMethod: MdocConnectionMethod,
                role: MdocRole,
                options: MdocTransportOptions,
            ): MdocTransport = ScriptedReader(connectionMethod, connects, advertises).also { readers += it }
        },
    )

    /**
     * The reader's first message for the QR [engaging] shows: a request for the PID's given name, encrypted
     * for the session that QR opened, the way a reader that scanned it would send it.
     */
    private suspend fun firstRequestFor(engaging: IosProximityState.Engaging): ByteArray {
        val engagement = engaging.qrPayload.removePrefix("mdoc:").fromBase64Url()
        val eDeviceKey = EngagementParser(engagement).parse().eSenderKey
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val sessionTranscript = Cbor.encode(
            buildCborArray {
                add(Tagged(Tagged.ENCODED_CBOR, Bstr(engagement)))
                add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem()))))
                add(Simple.NULL)
            }
        )
        val deviceRequest = DeviceRequestGenerator(encodedSessionTranscript = sessionTranscript)
            .addDocumentRequest(
                docType = PID_DOC_TYPE,
                itemsToRequest = mapOf(PID_DOC_TYPE to mapOf("given_name" to false)),
                requestInfo = null,
                readerKey = null,
                signatureAlgorithm = Algorithm.UNSET,
                readerKeyCertificateChain = null,
            )
            .generate()
        return SessionEncryption(MdocRole.MDOC_READER, eReaderKey, eDeviceKey, sessionTranscript)
            .encryptMessage(messagePlaintext = deviceRequest, statusCode = null)
    }

    @Test
    fun a_qr_no_reader_connects_to_expires_and_stops_advertising() = runTest {
        val readers = mutableListOf<ScriptedReader>()
        val presenter = presenter(walletWithPid(), readers, connects = false)

        presenter.startQrEngagement()
        runCurrent()
        assertIs<IosProximityState.Engaging>(presenter.state.value)

        advanceTimeBy(QR_ENGAGEMENT_TIMEOUT - 1.seconds)
        runCurrent()
        assertIs<IosProximityState.Engaging>(presenter.state.value)
        assertTrue(readers.none { it.closed })

        advanceTimeBy(2.seconds)
        runCurrent()
        val expired = assertIs<IosProximityState.Failed>(presenter.state.value)
        assertEquals(IosProximityState.Failed.Reason.QR_EXPIRED, expired.reason)
        assertTrue(readers.isNotEmpty())
        assertTrue(readers.all { it.closed }, "a transport was left advertising")
    }

    /** What the QR screen words from the catalogue as "Bluetooth is off", not a sentence of the presenter's. */
    @Test
    fun a_qr_that_cannot_advertise_says_bluetooth_is_unavailable() = runTest {
        val presenter = presenter(walletWithPid(), mutableListOf(), connects = false, advertises = false)

        presenter.startQrEngagement()

        val failed = assertIs<IosProximityState.Failed>(presenter.state.value)
        assertEquals(IosProximityState.Failed.Reason.BLUETOOTH_UNAVAILABLE, failed.reason)
    }

    /**
     * Once the response is out and the reader has ended the session, the request and its matches are gone:
     * an answer arriving after that changes nothing, where it used to start sending again.
     */
    @Test
    fun an_ended_exchange_keeps_nothing_an_answer_could_be_applied_to() = runTest {
        val readers = mutableListOf<ScriptedReader>()
        val presenter = presenter(walletWithPid(), readers, connects = true)

        presenter.startQrEngagement()
        runCurrent()
        val reader = readers.single()
        reader.says.send(firstRequestFor(assertIs<IosProximityState.Engaging>(presenter.state.value)))
        val requesting = presenter.state.filterIsInstance<IosProximityState.Requesting>().first()
        val everything = requesting.request.combinations.single().documents.map { document ->
            IosPresentmentDisclosure(
                documentId = document.documentId,
                credentialId = document.credentialId,
                claims = document.claims.map { it.claim }.toSet(),
            )
        }

        presenter.accept(everything)
        runCurrent()
        assertEquals(1, reader.heard.size, "the response was not sent")
        reader.says.send(ByteArray(0))
        val sent = presenter.state.filterIsInstance<IosProximityState.Sent>().first()
        assertEquals(listOf("PID"), sent.sharedDocuments)

        presenter.accept(everything)
        runCurrent()

        assertEquals(sent, presenter.state.value)
        assertEquals(1, reader.heard.size)
    }
}
