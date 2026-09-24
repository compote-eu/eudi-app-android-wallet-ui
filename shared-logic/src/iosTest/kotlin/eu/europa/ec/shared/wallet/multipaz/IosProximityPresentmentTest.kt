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

import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.util.fromBase64Url
import org.multipaz.crypto.EcCurve
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.engagement.EngagementParser
import org.multipaz.mdoc.nfc.MdocNfcEngagementHelper
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.ResponseApdu
import org.multipaz.presentment.CredentialQueryResult
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.Iso18013Response
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.presentment.mdocPresentment
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

private const val PID_DOC_TYPE = "eu.europa.ec.eudi.pid.1"

/** The claim's own name, i.e. the last segment of its path. */
private fun ClaimPathDomain.leafName(): String = segments.last().toString()

/** A stand-in for the user's answer: what the wallet may release, given what the reader matched. */
private typealias Consent = (CredentialQueryResult) -> CredentialSelection?

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

    /** Everything the request matched, which is what a user who unchecks nothing agrees to. */
    private val acceptEverything: Consent = { data ->
        CredentialSelection(
            matches = data.credentialSets
                .flatMap { it.options }
                .flatMap { it.members }
                .mapNotNull { it.matches.firstOrNull() },
        )
    }

    private val declineEverything: Consent = { null }

    private fun source(store: MultipazWalletStore, consent: Consent) = SimplePresentmentSource(
        documentStore = store.documentStore,
        documentTypeRepository = DocumentTypeRepository(),
        domainsMdocSignature = listOf(store.documentManagerId),
        showConsentPromptFn = { _, _, data, _, _ -> consent(data.credentialQueryResult) },
    )

    private suspend fun present(
        store: MultipazWalletStore,
        request: DeviceRequest,
        consent: Consent = acceptEverything,
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

    private fun Iso18013Response.releasedClaims(): Set<String> =
        eventData.requestedDocuments.single().claims.values.map { it.displayName }.toSet()

    /** What the screens see, built by the same translation the presenter uses. */
    private fun CredentialQueryResult.asConsentView() =
        toPresentmentRequest(requesterName = null, requesterIsTrusted = false)

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
        // The fixture holds six elements; selective disclosure means the other four stay home.
        assertEquals(setOf("given_name", "family_name"), response.releasedClaims())
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
                consent = declineEverything,
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
                CredentialSelection(
                    matches = data.credentialQueryResult.credentialSets.flatMap { it.options }
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
    fun the_consent_view_names_the_credential_and_shows_what_would_leave_the_wallet() = runTest {
        val store = walletWithPid()
        lateinit var view: IosPresentmentRequest

        present(
            store = store,
            request = readerRequest(mapOf("given_name" to false, "portrait" to true)),
            consent = { data -> view = data.asConsentView(); acceptEverything(data) },
        )

        val document = view.combinations.single().documents.single()
        // documentId + credentialId are how the screens name their answer back, so an empty one here
        // would mean nothing the user chose could ever be matched up again.
        assertTrue(document.documentId.isNotEmpty())
        assertTrue(document.credentialId.isNotEmpty())
        assertEquals(PID_DOC_TYPE, document.docType)
        assertEquals(IosPresentmentFormat.MsoMdoc, document.format)

        val givenName = document.claims.single { it.claim.leafName() == "given_name" }
        // The stored value, so consent shows what is actually about to be shared rather than a label.
        assertEquals("Tester", givenName.value)
        assertEquals(ClaimType.MsoMdoc(PID_DOC_TYPE), givenName.claim.type)
        assertTrue(!givenName.intentToRetain)
        // The reader asked to keep the portrait; that is shown, and is never a reason to force-share it.
        assertTrue(document.claims.single { it.claim.leafName() == "portrait" }.intentToRetain)
    }

    @Test
    fun a_claim_the_user_unchecks_does_not_go_out() = runTest {
        val store = walletWithPid()

        // The real translation in both directions: multipaz's request becomes the consent view, the
        // user's answer becomes multipaz's selection. Here they keep the name and drop the birth date.
        val response = present(
            store = store,
            request = readerRequest(mapOf("given_name" to false, "birth_date" to false)),
            consent = { data ->
                val document = data.asConsentView().combinations.single().documents.single()
                data.toSelection(
                    listOf(
                        IosPresentmentDisclosure(
                            documentId = document.documentId,
                            credentialId = document.credentialId,
                            claims = document.claims
                                .filter { it.claim.leafName() == "given_name" }
                                .map { it.claim }
                                .toSet(),
                        )
                    )
                )
            },
        )

        assertEquals(setOf("given_name"), response.releasedClaims())
    }

    @Test
    fun keeping_nothing_selects_nothing_to_send() = runTest {
        val store = walletWithPid()
        lateinit var selection: CredentialSelection

        assertFailsWith<PresentmentCanceledException> {
            present(
                store = store,
                request = readerRequest(mapOf("given_name" to false)),
                consent = { data ->
                    val document = data.asConsentView().combinations.single().documents.single()
                    selection = data.toSelection(
                        listOf(
                            IosPresentmentDisclosure(
                                documentId = document.documentId,
                                credentialId = document.credentialId,
                                claims = emptySet(),
                            )
                        )
                    )
                    // Which is why the presenter turns an empty selection into a refusal rather than
                    // handing it on: an empty response would tell the reader the wallet had nothing.
                    null
                },
            )
        }

        assertTrue(selection.matches.isEmpty())
    }

    @Test
    fun a_second_document_the_reader_would_accept_is_offered_as_a_second_choice() = runTest {
        val store = walletWithPid()
        // A wallet holding two PIDs: the reader asks for one, and which one to share is the user's
        // call. Picking silently would share a document the user never chose.
        store.seedMdocDocument(
            docType = PID_DOC_TYPE,
            displayName = "Second PID",
            namespace = PID_DOC_TYPE,
            elements = samplePidElements(givenName = "Other", familyName = "Person"),
            policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
        )
        lateinit var view: IosPresentmentRequest

        present(
            store = store,
            request = readerRequest(mapOf("given_name" to false)),
            consent = { data -> view = data.asConsentView(); acceptEverything(data) },
        )

        assertEquals(2, view.combinations.size)
        assertEquals(
            listOf("Tester", "Other"),
            view.combinations.map { it.documents.single().claims.single().value },
        )
        // Each choice is one document, not both at once.
        assertTrue(view.combinations.all { it.documents.size == 1 })
    }

    @Test
    fun the_engagement_qr_is_an_mdoc_uri_a_reader_can_parse() = runTest {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val connectionMethod = presenter.bleConnectionMethod()

        val qr = presenter.deviceEngagement(key.publicKey, listOf(connectionMethod)).toQrPayload()

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

    @Test
    fun the_engagement_qr_advertises_both_ble_and_nfc() = runTest {
        // Not a claim about what startQrEngagement() itself advertises today — since wiki/IOS_NFC_PLAN.md
        // §9 Stage 3, QR engagement is BLE-only; NFC only ever arrives through cold-tap (Annex C)
        // engagement, which builds its own DeviceEngagement independently and isn't reachable from a test
        // either (it needs a real device). What this does check for real: deviceEngagement() — the same
        // builder both origins ultimately rely on — correctly encodes more than one connection method
        // type in a single engagement CBOR, using the same bleConnectionMethod()/nfcConnectionMethod()
        // builders both origins actually use, not just that each individually returns something
        // well-formed.
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val ble = presenter.bleConnectionMethod()
        val nfc = presenter.nfcConnectionMethod()

        val qr = presenter.deviceEngagement(key.publicKey, listOf(ble, nfc)).toQrPayload()

        val engagement = EngagementParser(qr.removePrefix("mdoc:").fromBase64Url()).parse()
        assertEquals(ble, engagement.connectionMethods.filterIsInstance<MdocConnectionMethodBle>().single())
        val advertisedNfc = engagement.connectionMethods.filterIsInstance<MdocConnectionMethodNfc>().single()
        assertEquals(nfc.commandDataFieldMaxLength, advertisedNfc.commandDataFieldMaxLength)
        assertEquals(nfc.responseDataFieldMaxLength, advertisedNfc.responseDataFieldMaxLength)
    }

    // Finding E of the current-state audit: setNfcEngagementEnabled had no reader.
    @Test
    fun isNfcEngagementEnabled_reflects_the_last_call_to_setNfcEngagementEnabled() {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())

        // The documented default (see the field's own KDoc): inert until something opts in.
        assertEquals(false, presenter.isNfcEngagementEnabled())

        presenter.setNfcEngagementEnabled(true)
        assertEquals(true, presenter.isNfcEngagementEnabled())

        presenter.setNfcEngagementEnabled(false)
        assertEquals(false, presenter.isNfcEngagementEnabled())
    }

    // Finding D of the current-state audit: the Simulator has no NFC radio, so this is the same real
    // CoreNFC gate `start()` itself would hit — not a mocked substitute for it. See
    // `IosNfcHceTransportTest`'s own `isSupported` test for the lower-level claim this rests on.
    @Test
    fun isNfcDataRetrievalSupported_reports_false_on_a_device_with_no_nfc_hardware() {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())

        assertEquals(false, presenter.isNfcDataRetrievalSupported())
    }

    // wiki/IOS_NFC_PLAN.md §9: real-device finding. isPresentmentActuallyInProgress() replaced
    // presentmentJob?.isActive as the guard in armColdTapEngagement()/onColdTapHandoverComplete() —
    // that job is assigned the instant startQrEngagement() launches it and stays "active" for the
    // entire time QR is displayed with nothing connected yet (waitForConnection has no timeout), which
    // a real device log showed blocking cold-tap arming on essentially every attempt. Testing this
    // function directly, not armColdTapEngagement()/onScreenEntered() end to end: on the Simulator,
    // "skipped by this guard" and "proceeded, then stopped at the next guard down" (no NFC hardware
    // either way) are indistinguishable from the outside — this is the actual piece of logic the fix
    // changed, and mutableState (internal for exactly this) lets a test drive it without a real
    // connection.
    @Test
    fun isPresentmentActuallyInProgress_is_false_while_merely_engaging() {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())
        presenter.mutableState.value = IosProximityState.Engaging(qrPayload = "mdoc:test")

        assertEquals(
            false,
            presenter.isPresentmentActuallyInProgress(),
            "QR merely advertising and waiting for a connection is not a real exchange to guard against",
        )
    }

    // The necessary counterpart: the guard must still correctly fire once a real exchange is
    // underway, or this fix would silently reopen the exact race it was protecting against (two
    // concurrent runPresentment() calls racing over transport/pendingConsent/pendingData/sharedDocuments).
    @Test
    fun isPresentmentActuallyInProgress_is_true_while_requesting_or_sending() {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())

        presenter.mutableState.value = IosProximityState.Requesting(
            request = IosPresentmentRequest(
                requesterName = "Reader",
                requesterIsTrusted = false,
                combinations = emptyList(),
            ),
        )
        assertEquals(true, presenter.isPresentmentActuallyInProgress())

        presenter.mutableState.value = IosProximityState.Sending
        assertEquals(true, presenter.isPresentmentActuallyInProgress())
    }

    // Option 4 (wiki/IOS_NFC_PLAN.md §9): cold-tap handover just completed and CardSession is being
    // torn down while the wallet waits for the reader's BLE connection — a presentment attempt already
    // owns presentmentJob/transport/pendingConsent/pendingData/sharedDocuments at this point, exactly as
    // much as it does once Requesting/Sending is reached. Omitting Connecting from the guard above would
    // reopen the same race isPresentmentActuallyInProgress_is_true_while_requesting_or_sending guards
    // against, just during a different window.
    //
    // Real hardware is still what proves the reader actually connects over BLE rather than NFC — that
    // remains untestable on the Simulator, same pre-existing limitation this class's own doc comment
    // states for the rest of the transport layer. This test covers the one piece of Option 4's own logic
    // that is reachable without hardware: the guard's inclusion of the new state.
    @Test
    fun isPresentmentActuallyInProgress_is_true_while_connecting() {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())

        presenter.mutableState.value = IosProximityState.Connecting

        assertEquals(true, presenter.isPresentmentActuallyInProgress())
    }

    // Real-device finding (wiki/IOS_NFC_PLAN.md §9, Option 4): onColdTapHandoverComplete used to call
    // disarmColdTapEngagement() immediately on a successful handover, nulling
    // IosNfcHceTransport.engagementHelper synchronously — before the reader's own trailing READ_BINARY
    // (fetching the Handover Select content the same tap's SELECT FILE had only just announced was
    // ready) could arrive. A real tap showed that READ_BINARY rejected with SW 6A82 ("no engagement
    // helper is armed"), ~29ms after the null, well before CoreNFC's own session would have stopped
    // responding anyway (confirmed separately, from NfcHceBridge.swift's own source: its stop() only
    // clears its own reference synchronously — the actual stopEmulation()/invalidate() calls that end the
    // session's ability to answer APDUs are deferred behind its existing ~3-second delay). Fixed to call
    // nfcTransport.stop() alone, mirroring the exact ndefHandoverCompleted pattern
    // IosNfcHceTransportTest's own `after handover completes a trailing READ_BINARY still succeeds...`
    // test already proves at the ApduDelegate level — this test proves it end to end through
    // IosProximityPresenter's own real onColdTapHandoverComplete, the actual site of the bug, using a
    // real MdocNfcEngagementHelper driven through the exact NFC Type-4-Tag APDU sequence a real tap
    // produces (not a spy standing in for it).
    @Test
    fun onColdTapHandoverComplete_keeps_the_engagement_helper_reachable_for_a_trailing_read() = runTest {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())
        val bleConnectionMethod = presenter.bleConnectionMethod()
        presenter.coldTapBleTransport = FakeMdocTransport(bleConnectionMethod)

        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        presenter.nfcTransport.engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            staticHandoverMethods = listOf(bleConnectionMethod),
            onHandoverComplete = { _, encodedDeviceEngagement, handover ->
                presenter.onColdTapHandoverComplete(eDeviceKey, encodedDeviceEngagement, handover)
            },
            onError = { error -> error("unexpected engagement error: ${error.message}") },
        )
        // Mirrors the routing IosNfcHceTransport builds internally (its own copy is private, so this
        // reads presenter.nfcTransport's live state the same way that one would) — not a spy standing in
        // for it, the actual ApduDelegate class production APDUs are routed through.
        val delegate = IosNfcHceTransport.ApduDelegate(
            engagementHelperProvider = { presenter.nfcTransport.engagementHelper },
            ndefHandoverCompletedProvider = { presenter.nfcTransport.ndefHandoverCompleted },
        )

        suspend fun send(command: CommandApdu): ByteArray {
            val response = CompletableDeferred<ByteArray>()
            delegate.processCommandApdu(command.encode().toNSData()) { responseApdu ->
                response.complete(responseApdu!!.toByteArray())
            }
            return response.await()
        }

        fun selectFile(fileId: Int) = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_SELECT,
            p1 = Nfc.INS_SELECT_P1_FILE,
            p2 = Nfc.INS_SELECT_P2_FILE,
            payload = ByteString((fileId shr 8).toByte(), (fileId and 0xff).toByte()),
            le = 0,
        )

        // The same sequence a real Type 4 Tag read performs once, and the same sequence tonight's real
        // tap produced: SELECT the NDEF application, the capability container, then the NDEF file itself
        // — the last one is where static handover completes and onColdTapHandoverComplete runs
        // (synchronously, from inside this same SELECT's own processing).
        send(
            CommandApdu(
                cla = 0x00,
                ins = Nfc.INS_SELECT,
                p1 = Nfc.INS_SELECT_P1_APPLICATION,
                p2 = 0x00,
                payload = Nfc.NDEF_APPLICATION_ID,
                le = 0,
            ),
        )
        send(selectFile(Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID))
        send(selectFile(0xe104))

        assertEquals(IosProximityState.Connecting, presenter.state.value)
        assertNotNull(
            presenter.nfcTransport.engagementHelper,
            "the fix: onColdTapHandoverComplete must not null this out on a successful handover",
        )

        // Tonight's real-device failure, reproduced: the reader's trailing READ_BINARY — fetching the
        // Handover Select content the SELECT FILE above only announced was ready — must still be
        // answered correctly, not rejected with 6A82, even though CardSession teardown has already been
        // requested.
        val trailingRead = ResponseApdu.decode(
            send(
                CommandApdu(
                    cla = 0x00,
                    ins = Nfc.INS_READ_BINARY,
                    p1 = 0x00,
                    p2 = 0x00,
                    payload = ByteString(),
                    le = 15,
                ),
            ),
        )
        assertEquals(
            Nfc.RESPONSE_STATUS_SUCCESS,
            trailingRead.status,
            "tonight's real-device regression: this used to come back 6A82",
        )
    }

    // Real-device finding (wiki/IOS_NFC_PLAN.md §9): cancelling the system NFC sheet (or letting it time
    // out) ends CardSession on its own, entirely outside disarmColdTapEngagement()'s own explicit call
    // sites (onScreenEntered's else branch, onScreenExited) — before this fix, nothing ever reset
    // nfcEngagementEnabled/engagementHelperArmed in that case, so the "Share over NFC" switch stayed on
    // while CardSession was actually gone, and every later tap was silently ignored. This drives the real
    // callback chain NfcHceBridge.swift would (IosNfcHceTransport.ApduDelegate.sessionEndedUnexpectedly ->
    // IosProximityPresenter.onCardSessionEndedUnexpectedly), not a stand-in for it — see
    // IosNfcHceTransport.delegate's own doc comment for why it's internal.
    @Test
    fun an_unexpected_card_session_end_turns_the_nfc_switch_off_and_signals_the_ui() = runTest {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())
        presenter.setNfcEngagementEnabled(true)

        // CoroutineStart.UNDISPATCHED, not the plain default: the collector must actually be subscribed
        // to the flow before the synchronous trigger below runs, or (replay = 0) it would miss an
        // emission that happened before it started collecting — same reasoning as the positive-control
        // discipline used elsewhere in this file for real-device regressions.
        var signalled = false
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            presenter.nfcEngagementDisabledUnexpectedly.collect { signalled = true }
        }

        presenter.nfcTransport.delegate.sessionEndedUnexpectedly()
        advanceUntilIdle()
        collector.cancel()

        assertEquals(true, signalled, "an unexpected CardSession end must signal the UI to follow suit")
        assertEquals(
            false,
            presenter.isNfcEngagementEnabled(),
            "an unexpected CardSession end must turn the switch off, not leave it standing on",
        )
    }

    // The other half of the same fix: an *expected* end — our own disarmColdTapEngagement() call sites,
    // e.g. onScreenExited() below — must not fire the unexpected-end signal at all. That path already
    // tears cold-tap engagement down correctly on its own; re-signalling here would be redundant at best
    // and would incorrectly flip a switch the user never touched at worst.
    @Test
    fun an_expected_card_session_end_does_not_signal_the_unexpected_flow() = runTest {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())
        presenter.setNfcEngagementEnabled(true)

        var signalled = false
        val collector = launch { presenter.nfcEngagementDisabledUnexpectedly.collect { signalled = true } }

        presenter.onScreenExited()
        advanceUntilIdle()
        collector.cancel()

        assertEquals(false, signalled, "disarmColdTapEngagement()'s own call sites are an expected end")
        assertEquals(
            true,
            presenter.isNfcEngagementEnabled(),
            "disarmColdTapEngagement() alone must not touch the switch's own state — only the " +
                "unexpected-end path does",
        )
    }

    // Real-device finding, wiki/IOS_NFC_PLAN.md §9: NFCPresentmentIntentAssertion.acquire()'s own
    // Apple-imposed ~15-second cool-down is reachable through an ordinary flow — cancel the system
    // sheet, then immediately re-enable "Share over NFC" before the cool-down has elapsed. CardSession
    // never started in that case, so beyond showing a message that names the real constraint (not the
    // generic transient-failure one), the switch itself must also turn off — same principle, and the
    // same turnNfcEngagementOffAndSignalUi() mechanism, as the unexpected-session-end fix above.
    @Test
    fun an_assertion_cooldown_start_failure_shows_the_cooldown_message_and_turns_the_switch_off() = runTest {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine())
        presenter.setNfcEngagementEnabled(true)

        var message: String? = null
        val noticeCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            presenter.nfcNotice.collect { message = it }
        }
        var signalled = false
        val disabledCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            presenter.nfcEngagementDisabledUnexpectedly.collect { signalled = true }
        }

        presenter.onNfcStartResult(NfcStartResult.AssertionCooldown)
        advanceUntilIdle()
        noticeCollector.cancel()
        disabledCollector.cancel()

        assertEquals(
            "NFC needs a moment to reset after the last attempt — please wait a few seconds and try " +
                "again. Sharing continues over Bluetooth.",
            message,
            "must name the real constraint, not the generic transient-failure message — mirrors " +
                "IosProximityPresenter's own private NFC_ASSERTION_COOLDOWN constant",
        )
        assertEquals(true, signalled, "the switch's UI state must be told to follow suit")
        assertEquals(
            false,
            presenter.isNfcEngagementEnabled(),
            "CardSession never started, so NFC was never active — the switch must not keep showing on",
        )
    }
}

/**
 * A minimal [MdocTransport] stub — only what [IosProximityPresenter.onColdTapHandoverComplete]'s own
 * synchronous logic needs to proceed past its "no BLE transport was armed" guard. The presentment job
 * it gets handed into is never awaited by the test above, so nothing here needs to behave realistically
 * beyond not throwing; [waitForMessage] deliberately never returns rather than fabricate bytes that
 * could be mistaken for something real.
 */
private class FakeMdocTransport(
    override val connectionMethod: MdocConnectionMethod,
) : MdocTransport() {
    private val mutableState = MutableStateFlow(State.IDLE)
    override val state: StateFlow<State> = mutableState
    override val role: MdocRole = MdocRole.MDOC
    override val scanningTime: Duration? = null
    override suspend fun advertise() = Unit
    override suspend fun open(eSenderKey: EcPublicKey) = Unit
    override suspend fun sendMessage(message: ByteArray) = Unit
    override suspend fun waitForMessage(): ByteArray = kotlinx.coroutines.awaitCancellation()
    override suspend fun close() = Unit
}

// A file-private copy of the same NSData<->ByteArray idiom IosNfcHceTransportTest.kt itself uses —
// deliberately not reused from there: that file's own copies are `private` (file-scoped) on purpose,
// for the exact reason its own comment gives (an unrelated same-shaped private extension collision in
// KeychainWalletStorage.kt, in this same package) — this file needs its own copy for the same reason.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) {
        bytes.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), this.bytes, length)
        }
    }
    return bytes
}
