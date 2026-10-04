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
// out of reach here. Everything *below* the wire is not: `iosMdocPresentment` takes a `DeviceRequest` and
// returns a response with no transport involved, so these cases feed a reader's request straight in and
// check what comes back — that only the asked-for claims are released, that a refusal releases nothing,
// and that the wallet's own credential domain is what bounds the offer.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.trust.ReaderTrustSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.fromBase64Url
import org.multipaz.crypto.EcCurve
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.engagement.EngagementParser
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.presentment.CredentialQueryResult
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.Iso18013Response
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

private const val PID_DOC_TYPE = "eu.europa.ec.eudi.pid.1"
private const val MDL_DOC_TYPE = "org.iso.18013.5.1.mDL"
private const val MDL_NAMESPACE = "org.iso.18013.5.1"

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
    ) = iosMdocPresentment(
        deviceRequest = request,
        eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
        sessionTranscript = Simple.NULL,
        source = source(store, consent),
        keyAgreementPossible = listOf(EcCurve.P256),
        requesterAppId = null,
        requesterOrigin = null,
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

    //region elements the PID does not hold — matched the way Android matches them

    @Test
    fun an_element_the_pid_lacks_is_left_out_rather_than_refusing_the_pid() = runTest {
        val store = walletWithPid()
        lateinit var view: IosPresentmentRequest

        // The EU dev PID declares no `age_birth_year`, and neither does the fixture. A reader asking for it
        // beside the names used to get "nothing to share" for the whole PID.
        val response = present(
            store = store,
            request = readerRequest(
                mapOf("given_name" to false, "family_name" to false, "age_birth_year" to false),
            ),
            consent = { data -> view = data.asConsentView(); acceptEverything(data) },
        )

        assertEquals(
            setOf("given_name", "family_name"),
            view.combinations.single().documents.single().claims.map { it.claim.leafName() }.toSet(),
        )
        assertEquals(setOf("given_name", "family_name"), response.releasedClaims())
    }

    @Test
    fun a_request_for_nothing_the_pid_holds_is_refused() = runTest {
        val store = walletWithPid()

        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            present(
                store = store,
                request = readerRequest(mapOf("age_birth_year" to false, "nationality" to false)),
            )
        }
    }

    @Test
    fun every_document_the_reader_asks_for_is_offered_not_only_the_first() = runTest {
        val store = walletWithPid()
        store.seedMdocDocument(
            docType = MDL_DOC_TYPE,
            displayName = "mDL",
            namespace = MDL_NAMESPACE,
            elements = listOf("family_name" to Tstr("Kotlin")),
            policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
        )
        val encoded = DeviceRequestGenerator(encodedSessionTranscript = Cbor.encode(Simple.NULL))
            .addDocumentRequest(
                docType = PID_DOC_TYPE,
                itemsToRequest = mapOf(PID_DOC_TYPE to mapOf("given_name" to false)),
                requestInfo = null,
                readerKey = null,
                signatureAlgorithm = Algorithm.UNSET,
                readerKeyCertificateChain = null,
            )
            .addDocumentRequest(
                docType = MDL_DOC_TYPE,
                itemsToRequest = mapOf(MDL_NAMESPACE to mapOf("family_name" to false)),
                requestInfo = null,
                readerKey = null,
                signatureAlgorithm = Algorithm.UNSET,
                readerKeyCertificateChain = null,
            )
            .generate()
        val request = DeviceRequest.fromDataItem(Cbor.decode(encoded)).also { it.verifyReaderAuthentication(Simple.NULL) }
        lateinit var view: IosPresentmentRequest

        // Android answers both. multipaz reads only the first document request of an ISO 18013-5:2021
        // request, so the mDL would have been dropped without a word.
        val response = present(
            store = store,
            request = request,
            consent = { data -> view = data.asConsentView(); acceptEverything(data) },
        )

        assertEquals(setOf("PID", "mDL"), view.combinations.single().documents.map { it.documentName }.toSet())
        assertEquals(2, response.eventData.requestedDocuments.size)
    }

    //endregion

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
            iosMdocPresentment(
                deviceRequest = readerRequest(mapOf("given_name" to false)),
                eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
                sessionTranscript = Simple.NULL,
                source = foreign,
                keyAgreementPossible = listOf(EcCurve.P256),
                requesterAppId = null,
                requesterOrigin = null,
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
    fun the_exchange_runs_on_the_main_thread_where_core_bluetooth_answers() {
        // Off it, multipaz's BLE sender can miss CoreBluetooth's "ready to write" and stall mid-response.
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine(), isRegistrationCheckEnabled = { false })

        assertEquals<Any?>(Dispatchers.Main, presenter.scope.coroutineContext[ContinuationInterceptor])
    }

    @Test
    fun the_engagement_qr_is_an_mdoc_uri_a_reader_can_parse() = runTest {
        val presenter = IosProximityPresenter(walletEngine = IosWalletEngine(), isRegistrationCheckEnabled = { false })
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

    //region a reader that authenticates — Android's EnforceIfPresent, through the presenter's own source

    /** The same request as [readerRequest], signed by a reader presenting a self-signed certificate. */
    private suspend fun authenticatedReaderRequest(elements: Map<String, Boolean>): DeviceRequest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val name = X500Name.fromName("CN=Test Reader,C=EU")
        val certificate = X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(privateKey = key),
            serialNumber = ASN1Integer(1L),
            subject = name,
            issuer = name,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
        ).build()
        val encoded = DeviceRequestGenerator(encodedSessionTranscript = Cbor.encode(Simple.NULL))
            .addDocumentRequest(
                docType = PID_DOC_TYPE,
                itemsToRequest = mapOf(PID_DOC_TYPE to elements),
                requestInfo = null,
                readerKey = key,
                signatureAlgorithm = Algorithm.ES256,
                readerKeyCertificateChain = X509CertChain(listOf(certificate)),
            )
            .generate()
        // Verified for real here, unlike [readerRequest]: a signature that failed would end the exchange
        // before consent for a reason that has nothing to do with trust.
        return DeviceRequest.fromDataItem(Cbor.decode(encoded)).also { it.verifyReaderAuthentication(Simple.NULL) }
    }

    private fun presenter(store: MultipazWalletStore, readerTrust: ReaderTrustSource, scope: CoroutineScope) =
        IosProximityPresenter(
            walletEngine = IosWalletEngine(),
            credentialDomain = store.documentManagerId,
            scope = scope,
            readerTrust = readerTrust,
            isRegistrationCheckEnabled = { false },
        )

    /** What the presenter's own source does with [request] — consent included, transport not. */
    private suspend fun presentThrough(presenter: IosProximityPresenter, store: MultipazWalletStore, request: DeviceRequest) =
        iosMdocPresentment(
            deviceRequest = request,
            eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
            sessionTranscript = Simple.NULL,
            source = presenter.presentmentSource(store),
            keyAgreementPossible = listOf(EcCurve.P256),
            requesterAppId = null,
            requesterOrigin = null,
        )

    @Test
    fun a_reader_with_an_untrusted_certificate_is_blocked_before_the_request_is_shown() = runTest {
        val store = walletWithPid()
        val presenter = presenter(store, ReaderTrustSource { null }, backgroundScope)

        val refusal = assertFailsWith<UntrustedVerifierException> {
            presentThrough(presenter, store, authenticatedReaderRequest(mapOf("given_name" to false)))
        }

        // Never published: a screen collecting the state would not have seen the request at all.
        assertEquals(IosProximityState.Idle, presenter.state.value)
        assertEquals(IosProximityState.VerifierNotTrusted, presenter.endedBy(refusal))
    }

    /**
     * The control for the case above: the same signed request, vouched for, reaches the consent step and
     * is shown as trusted. Without it, the block could be passing because the signature never verified.
     */
    @Test
    fun a_reader_with_a_trusted_certificate_reaches_consent() = runTest {
        val store = walletWithPid()
        val presenter = presenter(store, ReaderTrustSource { TrustMetadata() }, backgroundScope)

        val exchange = async {
            runCatching { presentThrough(presenter, store, authenticatedReaderRequest(mapOf("given_name" to false))) }
        }
        val requesting = presenter.state.filterIsInstance<IosProximityState.Requesting>().first()
        presenter.decline()

        assertTrue(requesting.request.requesterIsTrusted)
        assertIs<PresentmentCanceledException>(exchange.await().exceptionOrNull())
    }

    /** A reader that does not authenticate is allowed even with nothing vouching for it, as on Android. */
    @Test
    fun a_reader_that_does_not_authenticate_is_not_blocked() = runTest {
        val store = walletWithPid()
        val presenter = presenter(store, ReaderTrustSource { null }, backgroundScope)

        val exchange = async {
            runCatching { presentThrough(presenter, store, readerRequest(mapOf("given_name" to false))) }
        }
        val requesting = presenter.state.filterIsInstance<IosProximityState.Requesting>().first()
        presenter.decline()

        assertFalse(requesting.request.requesterIsTrusted)
        assertIs<PresentmentCanceledException>(exchange.await().exceptionOrNull())
    }

    //endregion
}
