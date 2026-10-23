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

// Who a proximity request is from when its document requests are signed differently. multipaz names the
// signer of the first signed document request and lets its trust stand for all of them, so one trusted
// signature could carry an unsigned request past the consent screen under the trusted name (upstream's
// eudi-lib-ios-iso18013-data-transfer #137). The same path serves ISO mdoc over the Digital Credentials API.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import eu.europa.ec.shared.wallet.trust.ReaderTrustSource
import eu.europa.ec.shared.wallet.trust.certChain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

private const val PID_DOC_TYPE = "eu.europa.ec.eudi.pid.1"
private const val MDL_DOC_TYPE = "org.iso.18013.5.1.mDL"
private const val MDL_NAMESPACE = "org.iso.18013.5.1"

class ReaderIdentityBindingTest {

    /** A reader's key and the self-signed certificate it presents. */
    private class Reader(val key: EcPrivateKey, val chain: X509CertChain) {
        companion object {
            suspend fun named(commonName: String): Reader {
                val key = Crypto.createEcPrivateKey(EcCurve.P256)
                val name = X500Name.fromName("CN=$commonName,C=EU")
                val certificate = X509Cert.Builder(
                    publicKey = key.publicKey,
                    signingKey = AsymmetricKey.AnonymousExplicit(privateKey = key),
                    serialNumber = ASN1Integer(1L),
                    subject = name,
                    issuer = name,
                    validFrom = Clock.System.now() - 1.days,
                    validUntil = Clock.System.now() + 30.days,
                ).build()
                return Reader(key, X509CertChain(listOf(certificate)))
            }
        }
    }

    /** One test's readers: two the trust lists vouch for by name, and one they do not know. */
    private class Readers(val trusted: Reader, val alsoTrusted: Reader, val untrusted: Reader) {
        val trustLists = ReaderTrustSource { requester ->
            when (requester.certChain) {
                trusted.chain -> TrustMetadata(displayName = "Trusted Reader")
                alsoTrusted.chain -> TrustMetadata(displayName = "Another Trusted Reader")
                else -> null
            }
        }

        companion object {
            suspend fun make() = Readers(
                trusted = Reader.named("Trusted Reader"),
                alsoTrusted = Reader.named("Another Trusted Reader"),
                untrusted = Reader.named("Untrusted Reader"),
            )
        }
    }

    /** One document request: one element of [docType], signed by [signer] or by nobody. */
    private data class Part(val docType: String, val namespace: String, val element: String, val signer: Reader?)

    private fun pid(signer: Reader?) = Part(PID_DOC_TYPE, PID_DOC_TYPE, "given_name", signer)
    private fun mdl(signer: Reader?) = Part(MDL_DOC_TYPE, MDL_NAMESPACE, "family_name", signer)

    private suspend fun walletWithPidAndMdl(): MultipazWalletStore {
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
        store.seedMdocDocument(
            docType = MDL_DOC_TYPE,
            displayName = "mDL",
            namespace = MDL_NAMESPACE,
            elements = listOf("family_name" to Tstr("Kotlin")),
            policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
        )
        return store
    }

    /** A request made of [parts], each document request signed as its part says, verified as a reader's would be. */
    private suspend fun request(vararg parts: Part): DeviceRequest {
        val generator = DeviceRequestGenerator(encodedSessionTranscript = Cbor.encode(Simple.NULL))
        parts.forEach { part ->
            generator.addDocumentRequest(
                docType = part.docType,
                itemsToRequest = mapOf(part.namespace to mapOf(part.element to false)),
                requestInfo = null,
                readerKey = part.signer?.key,
                signatureAlgorithm = if (part.signer == null) Algorithm.UNSET else Algorithm.ES256,
                readerKeyCertificateChain = part.signer?.chain,
            )
        }
        return DeviceRequest.fromDataItem(Cbor.decode(generator.generate()))
            .also { it.verifyReaderAuthentication(Simple.NULL) }
    }

    private fun presenter(store: MultipazWalletStore, scope: CoroutineScope, readers: Readers) = IosProximityPresenter(
        walletEngine = IosWalletEngine(),
        credentialDomain = store.documentManagerId,
        scope = scope,
        readerTrust = readers.trustLists,
        isRegistrationCheckEnabled = { false },
    )

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

    /** The request as the consent screen shows it; the user then declines. */
    private suspend fun TestScope.shownAs(readers: Readers, vararg parts: Part): IosPresentmentRequest {
        val store = walletWithPidAndMdl()
        val presenter = presenter(store, backgroundScope, readers)
        val exchange = async { runCatching { presentThrough(presenter, store, request(*parts)) } }
        val requesting = presenter.state.filterIsInstance<IosProximityState.Requesting>().first()
        presenter.decline()
        assertIs<PresentmentCanceledException>(exchange.await().exceptionOrNull())
        return requesting.request
    }

    /** What the presenter does with a request it must refuse: it never reaches the consent screen. */
    private suspend fun TestScope.refused(readers: Readers, vararg parts: Part) {
        val store = walletWithPidAndMdl()
        val presenter = presenter(store, backgroundScope, readers)
        assertFailsWith<UntrustedVerifierException> { presentThrough(presenter, store, request(*parts)) }
        assertEquals(IosProximityState.Idle, presenter.state.value)
    }

    /** The control: one trusted reader signing every document request is that reader. */
    @Test
    fun a_request_signed_throughout_by_a_trusted_reader_is_shown_as_that_reader() = runTest {
        val r = Readers.make()
        val shown = shownAs(r, pid(signer = r.trusted), mdl(signer = r.trusted))

        assertTrue(shown.requesterIsTrusted)
        assertEquals("Trusted Reader", shown.requesterName)
    }

    @Test
    fun an_unsigned_document_request_beside_a_trusted_one_is_not_shown_under_its_name() = runTest {
        val r = Readers.make()
        val shown = shownAs(r, pid(signer = r.trusted), mdl(signer = null))

        assertFalse(shown.requesterIsTrusted)
        assertNull(shown.requesterName)
    }

    /** The signed part asks for a document the wallet does not hold, so the screen would never show it. */
    @Test
    fun a_trusted_signature_on_a_document_the_wallet_does_not_hold_vouches_for_nothing() = runTest {
        val r = Readers.make()
        val shown = shownAs(r, Part("org.example.decoy", "org.example.decoy", "anything", r.trusted), pid(signer = null))

        assertFalse(shown.requesterIsTrusted)
        assertNull(shown.requesterName)
    }

    @Test
    fun a_document_request_signed_by_an_untrusted_reader_is_refused_after_a_trusted_one() = runTest {
        val r = Readers.make()
        refused(r, pid(signer = r.trusted), mdl(signer = r.untrusted))
    }

    @Test
    fun a_document_request_signed_by_an_untrusted_reader_is_refused_before_a_trusted_one() = runTest {
        val r = Readers.make()
        refused(r, pid(signer = r.untrusted), mdl(signer = r.trusted))
    }

    /** Each is trusted, but no one name stands for the whole request. */
    @Test
    fun document_requests_signed_by_two_different_trusted_readers_are_refused() = runTest {
        val r = Readers.make()
        refused(r, pid(signer = r.trusted), mdl(signer = r.alsoTrusted))
    }

    /** As before the change: a reader that signs nothing is allowed, with no name and no badge. */
    @Test
    fun a_request_nobody_signed_still_reaches_consent_unverified() = runTest {
        val shown = shownAs(Readers.make(), pid(signer = null), mdl(signer = null))

        assertFalse(shown.requesterIsTrusted)
        assertNull(shown.requesterName)
    }
}
