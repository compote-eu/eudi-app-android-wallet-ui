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

// Reading the History tab back out of multipaz's event log.
//
// The write half needs no test of ours — multipaz writes the events, and a live run proves it — but the
// read half makes three decisions a user would notice if they were wrong: which events become rows,
// which way round the rows go, and what names the row. The last is the one that bit: multipaz records a
// *blank* requester for a URI-scheme presentation, so a row would say nothing where the consent screen
// had named the verifier.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.eventlogger.EventPresentmentData
import org.multipaz.eventlogger.EventPresentmentDataDocument
import org.multipaz.eventlogger.EventPresentmentUriSchemeOpenID4VP
import org.multipaz.eventlogger.EventProvisioning
import org.multipaz.eventlogger.EventProvisioningIssuerDataOpenID4VCI
import org.multipaz.eventlogger.EventSimple
import org.multipaz.eventlogger.SimpleEventLogger
import org.multipaz.provisioning.Display
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class IosTransactionLogTest {

    private suspend fun emptyWallet(): MultipazWalletStore {
        val storage = EphemeralStorage()
        return MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )
    }

    private suspend fun certificateNamed(commonName: String): X509CertChain {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val name = X500Name.fromName("CN=$commonName,C=EU")
        return X509CertChain(
            listOf(
                X509Cert.Builder(
                    publicKey = key.publicKey,
                    signingKey = AsymmetricKey.AnonymousExplicit(privateKey = key),
                    serialNumber = ASN1Integer(1L),
                    subject = name,
                    issuer = name,
                    validFrom = Clock.System.now() - 1.days,
                    validUntil = Clock.System.now() + 30.days,
                ).build()
            )
        )
    }

    private suspend fun presentation(
        requesterName: String?,
        certificateCommonName: String? = null,
        documentName: String? = "PID",
    ) = EventPresentmentUriSchemeOpenID4VP(
        presentmentData = EventPresentmentData(
            requesterName = requesterName,
            requesterCertChain = certificateCommonName?.let { certificateNamed(it) },
            trustMetadata = null,
            requestedDocuments = listOf(
                EventPresentmentDataDocument(
                    documentId = "doc-1",
                    documentName = documentName,
                    claims = emptyMap(),
                )
            ),
        ),
        uri = "openid4vp://?request_uri=https%3A%2F%2Fv.test%2Fr",
        appId = null,
        origin = null,
        requestJwt = "",
        vpToken = "",
        redirectUri = null,
        state = null,
    )

    private fun issuance(documentName: String?) = EventProvisioning(
        issuerData = EventProvisioningIssuerDataOpenID4VCI(
            display = Display(text = "Test Issuer", logo = null),
            url = "https://issuer.test",
            credentialId = "cred-1",
        ),
        initialProvisioning = true,
        documentId = "doc-1",
        documentName = documentName,
        display = null,
        credentialsFetched = emptyMap(),
    )

    @Test
    fun a_presentation_is_named_after_the_party_that_asked() = runTest {
        val store = emptyWallet()
        store.eventLogger().addEvent(presentation(requesterName = "Trusted Verifier"))

        val transaction = store.transactions().single()

        assertEquals(IosTransactionKind.Presentation, transaction.kind)
        assertEquals("Trusted Verifier", transaction.relyingPartyName)
        assertEquals(listOf("PID"), transaction.documentNames)
    }

    @Test
    fun a_blank_requester_falls_back_to_the_verifiers_certificate() = runTest {
        // The case that actually happens. multipaz fills `requesterName` only from trust metadata or a
        // web origin, and `uriSchemePresentment` passes `origin = ""` — so a real URI-scheme
        // presentation records an empty string, and the History row would be blank where the consent
        // screen had said "Verifier Signer dev".
        val store = emptyWallet()
        store.eventLogger().addEvent(
            presentation(requesterName = "", certificateCommonName = "Verifier Signer dev")
        )

        assertEquals("Verifier Signer dev", store.transactions().single().relyingPartyName)
    }

    @Test
    fun a_presentation_with_neither_name_nor_certificate_is_left_unnamed() = runTest {
        val store = emptyWallet()
        store.eventLogger().addEvent(presentation(requesterName = null))

        // Null rather than a placeholder: naming the verifier is the bridge's decision, and it has a
        // string catalog to do it with.
        assertNull(store.transactions().single().relyingPartyName)
    }

    @Test
    fun an_issuance_has_no_counterparty_and_shares_nothing() = runTest {
        val store = emptyWallet()
        store.eventLogger().addEvent(issuance(documentName = "PID (MSO MDoc)"))

        val transaction = store.transactions().single()

        assertEquals(IosTransactionKind.Issuance, transaction.kind)
        assertNull(transaction.relyingPartyName)
        assertEquals(listOf("PID (MSO MDoc)"), transaction.documentNames)
        assertTrue(transaction.sharedDocuments.isEmpty())
    }

    @Test
    fun the_newest_transaction_comes_first() = runTest {
        // The log's own order is chronological by storage key, i.e. oldest first, and every reader of
        // this wants the opposite — the History tab leads with what just happened.
        val store = emptyWallet()
        store.eventLogger().addEvent(presentation(requesterName = "First"))
        store.eventLogger().addEvent(issuance(documentName = "Second"))

        assertEquals(
            listOf(IosTransactionKind.Issuance, IosTransactionKind.Presentation),
            store.transactions().map { it.kind },
        )
    }

    @Test
    fun an_event_that_names_nothing_the_user_did_is_not_a_transaction() = runTest {
        val store = emptyWallet()
        store.eventLogger().addEvent(
            EventSimple(data = ByteString("something happened".encodeToByteArray()))
        )
        store.eventLogger().addEvent(presentation(requesterName = "Verifier"))

        // Only the presentation: a free-text event rendered as a row would put a transaction in the
        // list that corresponds to nothing the user recognises.
        assertEquals(1, store.transactions().size)
    }

    @Test
    fun a_document_the_log_could_not_name_is_left_out_rather_than_shown_by_its_id() = runTest {
        val store = emptyWallet()
        store.eventLogger().addEvent(presentation(requesterName = "Verifier", documentName = null))

        val transaction = store.transactions().single()

        // An opaque identifier in a "you shared X with Y" row reads as a bug to a user.
        assertTrue(transaction.documentNames.isEmpty())
        assertTrue(transaction.sharedDocuments.isEmpty())
    }

    @Test
    fun a_transaction_can_be_found_again_by_the_id_its_row_carries() = runTest {
        // What the details route does with the id the list put in it.
        val store = emptyWallet()
        store.eventLogger().addEvent(presentation(requesterName = "Verifier"))
        val logged = store.transactions().single()

        assertEquals(logged, store.transaction(logged.id))
        // An entry that has aged out of the log is absent rather than an error.
        assertNull(store.transaction("no-such-transaction"))
    }

    @Test
    fun the_logger_is_the_same_one_every_caller_gets() = runTest {
        // Two loggers over one table would each keep their own initialization state for no benefit,
        // and the presenters resolve it independently of the reader.
        val store = emptyWallet()

        val first: SimpleEventLogger = store.eventLogger()

        assertTrue(first === store.eventLogger())
    }
}
