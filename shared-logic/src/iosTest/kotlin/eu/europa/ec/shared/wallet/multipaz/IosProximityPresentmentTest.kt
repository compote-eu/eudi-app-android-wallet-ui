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

// Proximity presentation as far as a machine without a Bluetooth radio can take it.
//
// The iOS Simulator has neither BLE nor NFC, and those are multipaz's only mdoc transports, so the wire is
// out of reach here. Everything *below* the wire is not: `mdocPresentment` takes a `DeviceRequest` and
// returns a response with no transport involved, so these cases feed a reader's request straight in and
// check what comes back — that only the asked-for claims are released, that a refusal releases nothing,
// and that the wallet's own credential domain is what bounds the offer.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.multipaz.spike.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.spike.seedMdocDocument
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.util.fromBase64Url
import org.multipaz.crypto.EcCurve
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.engagement.EngagementParser
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.presentment.mdocPresentment
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val PID_DOC_TYPE = "eu.europa.ec.eudi.pid.1"

class IosProximityPresentmentTest {

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

    /** A reader asking for specific data elements, exactly as one would over the wire. */
    private suspend fun readerRequest(
        elements: Map<String, Boolean>,
        docType: String = PID_DOC_TYPE,
    ): DeviceRequest {
        val encoded = DeviceRequestGenerator(encodedSessionTranscript = Cbor.encode(Simple.NULL))
            .addDocumentRequest(
                docType = docType,
                itemsToRequest = mapOf(PID_DOC_TYPE to elements),
                requestInfo = null,
                readerKey = null,
                signatureAlgorithm = Algorithm.UNSET,
                readerKeyCertificateChain = null,
            )
            .generate()
        return DeviceRequest.fromDataItem(Cbor.decode(encoded)).also {
            // A parsed request refuses to be read until reader authentication has been *considered* —
            // multipaz guards `readerAuthAll` behind this. Nothing here is signed (no reader key), so
            // this establishes "unauthenticated reader", which is the ordinary case for these dev
            // verifiers and exactly what the wallet must still handle.
            it.verifyReaderAuthentication(Simple.NULL)
        }
    }

    private fun source(store: MultipazWalletStore, consent: ConsentAnswer) = SimplePresentmentSource(
        documentStore = store.documentStore,
        documentTypeRepository = DocumentTypeRepository(),
        domainsMdocSignature = listOf(store.documentManagerId),
        showConsentPromptFn = { _, _, data, _, _ ->
            when (consent) {
                ConsentAnswer.ACCEPT -> CredentialPresentmentSelection(
                    matches = data.credentialSets
                        .flatMap { it.options }
                        .flatMap { it.members }
                        .mapNotNull { it.matches.firstOrNull() },
                )

                ConsentAnswer.DECLINE -> null
            }
        },
    )

    private enum class ConsentAnswer { ACCEPT, DECLINE }

    private suspend fun present(
        store: MultipazWalletStore,
        request: DeviceRequest,
        consent: ConsentAnswer = ConsentAnswer.ACCEPT,
    ) = mdocPresentment(
        deviceRequest = request,
        eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
        sessionTranscript = Simple.NULL,
        source = source(store, consent),
        keyAgreementPossible = listOf(EcCurve.P256),
        requesterAppId = null,
        requesterOrigin = null,
        onDocumentsInFocus = {},
    )

    @Test
    fun only_the_claims_the_reader_asked_for_are_released() = runTest {
        val store = walletWithPid()

        val response = present(
            store = store,
            request = readerRequest(mapOf("given_name" to false, "family_name" to false)),
        )

        // Read from what multipaz reports it released rather than by re-parsing the response:
        // `DeviceResponseParser` verifies issuer authentication, and the fixture's MSO is signed by an
        // anonymous ephemeral key with no certificate chain — a limit of the fixture, not of the response.
        // `deviceResponse.documents` is guarded behind a verify() the *reader* performs, so what is
        // readable here is what multipaz reports having released.
        assertEquals(1, response.eventData.requestedDocuments.size)
        val released = response.eventData.requestedDocuments.single().claims.values.map { it.displayName }.toSet()
        // The fixture holds six elements; selective disclosure means the other four stay home.
        assertEquals(setOf("given_name", "family_name"), released)
    }

    @Test
    fun a_refusal_releases_nothing() = runTest {
        val store = walletWithPid()

        // Declining raises rather than sending an empty response, so the presenter must treat this as
        // "the user said no" and not as a protocol failure — the difference the screens show.
        assertFailsWith<PresentmentCanceledException> {
            present(
                store = store,
                request = readerRequest(mapOf("given_name" to false)),
                consent = ConsentAnswer.DECLINE,
            )
        }
    }

    @Test
    fun a_document_type_the_wallet_does_not_hold_is_refused_rather_than_answered_emptily() = runTest {
        val store = walletWithPid()

        // multipaz raises rather than returning an empty response — worth pinning, because the screen
        // has to turn this into "you have nothing this reader asked for" instead of a blank success.
        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            present(
                store = store,
                request = readerRequest(
                    elements = mapOf("given_name" to false),
                    docType = "org.iso.18013.5.1.mDL",
                ),
            )
        }
    }

    @Test
    fun credentials_outside_the_wallets_own_domain_are_never_offered() = runTest {
        val store = walletWithPid()
        // A source scoped to some other component's domain, as `SimplePresentmentSource` would be if the
        // domain were wrong: the same store, the same request, and nothing to offer from it.
        val foreign = SimplePresentmentSource(
            documentStore = store.documentStore,
            documentTypeRepository = DocumentTypeRepository(),
            domainsMdocSignature = listOf("some-other-component"),
            showConsentPromptFn = { _, _, data, _, _ ->
                CredentialPresentmentSelection(
                    matches = data.credentialSets.flatMap { it.options }
                        .flatMap { it.members }.mapNotNull { it.matches.firstOrNull() },
                )
            },
        )

        // Same store, same request, wrong domain: nothing is offerable, so the request cannot be met.
        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            mdocPresentment(
                deviceRequest = readerRequest(mapOf("given_name" to false)),
                eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
                sessionTranscript = Simple.NULL,
                source = foreign,
                keyAgreementPossible = listOf(EcCurve.P256),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {},
            )
        }
    }

    @Test
    fun the_engagement_qr_is_an_mdoc_uri_a_reader_can_parse() = runTest {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val connectionMethod = presenter.bleConnectionMethod()

        val qr = presenter.deviceEngagement(key.publicKey, connectionMethod).toQrPayload()

        // ISO 18013-5 §8.2.2.3: base64url device engagement behind an `mdoc:` scheme. A reader that
        // cannot parse this never connects, and nothing else in the flow would explain why.
        assertTrue(qr.startsWith("mdoc:"), "was: ${qr.take(16)}")
        val engagement = EngagementParser(qr.removePrefix("mdoc:").fromBase64Url()).parse()
        assertEquals("1.0", engagement.version)
        assertEquals(key.publicKey, engagement.eSenderKey)

        val advertised = engagement.connectionMethods.filterIsInstance<MdocConnectionMethodBle>().single()
        // Peripheral-server mode only: that is the side an iOS app can be.
        assertTrue(advertised.supportsPeripheralServerMode)
        assertTrue(!advertised.supportsCentralClientMode)
        assertEquals(connectionMethod.peripheralServerModeUuid, advertised.peripheralServerModeUuid)
    }
}
