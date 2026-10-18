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

// The iOS half of the privacy dashboard's data: what a presentation records about its relying party
// (wallet-core's rules, from the registration certificate) and the deletion requests and reports kept under
// it (Android's controller rules). Every case runs against a real store over ephemeral storage.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.CommunicationMethodDomain
import eu.europa.ec.corelogic.model.DpaContactDomain
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Tstr
import org.multipaz.claim.MdocClaim
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
import org.multipaz.provisioning.Display
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class IosPresentationPrivacyTest {

    /** The EU dev verifier's TEST-01 registration certificate payload, as read on 2026-10-03. */
    private val euDevVerifierPayload = Json.parseToJsonElement(
        """
        {
          "sub": "LEIXG-123456789",
          "name": "Verifier Signer dev",
          "sub_ln": "Niscy",
          "country": "EU",
          "registry_uri": "https://registry.example/wrp",
          "privacy_policy": "http://data.europa.eu/eudi/policy/trust-service-practice-statement",
          "info_uri": "https://dev.kotlinIssuerSigner.com/support",
          "support_uri": "https://dev.verifier-backend.eudiw.dev/",
          "purpose": [{"lang": "en", "value": "For testing purposes only"}],
          "supervisory_authority": {"email": "geral@cnpd.pt", "phone": "+351213928400", "uri": "https://www.cnpd.pt/contactos"}
        }
        """.trimIndent()
    ).jsonObject

    private suspend fun storeOver(storage: EphemeralStorage = EphemeralStorage()) =
        storage to MultipazWalletStore.build(storage = storage, secureAreas = listOf(SoftwareSecureArea.create(storage)))

    private suspend fun signer(commonName: String): X509CertChain {
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

    private fun presentation(chain: X509CertChain?, identifier: String = "") = EventPresentmentUriSchemeOpenID4VP(
        identifier = identifier,
        timestamp = Clock.System.now(),
        presentmentData = EventPresentmentData(
            requesterName = null,
            requesterCertChain = chain,
            trustMetadata = null,
            requestedDocuments = listOf(
                EventPresentmentDataDocument(
                    documentId = "doc-1",
                    documentName = "PID",
                    claims = mapOf(
                        MdocRequestedClaim(
                            docType = "eu.europa.ec.eudi.pid.1",
                            namespaceName = "eu.europa.ec.eudi.pid.1",
                            dataElementName = "family_name",
                            intentToRetain = false,
                        ) to MdocClaim(
                            displayName = "Family name",
                            attribute = null,
                            docType = "eu.europa.ec.eudi.pid.1",
                            namespaceName = "eu.europa.ec.eudi.pid.1",
                            dataElementName = "family_name",
                            value = Tstr("Doe"),
                        )
                    ),
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

    private fun provisioning() = EventProvisioning(
        identifier = "",
        issuerData = EventProvisioningIssuerDataOpenID4VCI(
            display = Display(text = "Issuer", logo = null),
            url = "https://issuer.test",
            credentialId = "cred-1",
        ),
        initialProvisioning = true,
        documentId = "doc-2",
        documentName = "PID",
        display = null,
        credentialsFetched = emptyMap(),
    )

    // --- What a presentation records about its relying party ---

    @Test
    fun a_registration_is_recorded_as_wallet_core_records_it() {
        val record = issuerRegistrationFrom(euDevVerifierPayload).toPresentationPartyRecord()

        assertEquals("Niscy", record.name)
        assertEquals(QualifiedIdentifierRecord("http://data.europa.eu/eudi/id/LEI", "123456789"), record.identifier)
        // Country first, then support and info, as wallet-core lists them; the country is no contact.
        assertEquals(
            listOf("EU", "https://dev.verifier-backend.eudiw.dev/", "https://dev.kotlinIssuerSigner.com/support"),
            record.contacts,
        )
        assertEquals(listOf("geral@cnpd.pt", "+351213928400", "https://www.cnpd.pt/contactos"), record.authorityContacts)
        assertNull(record.authorityName)
        assertEquals("https://registry.example/wrp", record.registrarUrl)
        assertEquals(listOf("http://data.europa.eu/eudi/policy/trust-service-practice-statement"), record.privacyPolicyUrls)
    }

    @Test
    fun the_name_falls_back_from_legal_to_given_and_family_to_name() {
        fun nameOf(json: String) = issuerRegistrationFrom(Json.parseToJsonElement(json).jsonObject)
            .toPresentationPartyRecord().name

        assertEquals("Niscy", nameOf("""{"sub_ln":"Niscy","sub_gn":"Jane","sub_fn":"Doe","name":"Shop"}"""))
        assertEquals("Jane Doe", nameOf("""{"sub_gn":"Jane","sub_fn":"Doe","name":"Shop"}"""))
        assertEquals("Shop", nameOf("""{"name":"Shop"}"""))
        assertNull(nameOf("""{}"""))
    }

    @Test
    fun identifiers_follow_the_etsi_prefixes() {
        assertEquals(QualifiedIdentifierRecord("http://data.europa.eu/eudi/id/VATIN", "123"), "VATDE-123".toQualifiedIdentifierOrNull())
        assertEquals(QualifiedIdentifierRecord("http://data.europa.eu/eudi/id/EUID", "B-1"), "NTRBE-B-1".toQualifiedIdentifierOrNull())
        assertNull("XYZ-123".toQualifiedIdentifierOrNull())
        assertNull("LEI123".toQualifiedIdentifierOrNull())
    }

    @Test
    fun an_intermediary_is_recorded_only_with_its_identifier() {
        fun of(json: String) = issuerRegistrationFrom(Json.parseToJsonElement(json).jsonObject).toPresentationPartyRecord()

        val named = of("""{"intermediary":{"sub":"LEIXG-987","sname":"Broker"}}""")
        assertEquals("Broker", named.intermediaryName)
        assertEquals("987", named.intermediaryIdentifier?.value)
        assertNull(of("""{"intermediary":{"sname":"Broker"}}""").intermediaryName)
    }

    @Test
    fun the_consent_record_reaches_the_presentation_and_its_history() = runTest {
        val (_, store) = storeOver()
        val chain = signer("Verifier Signer dev")
        store.presentationPartyRecords.remember(
            chain.certificates.first(),
            issuerRegistrationFrom(euDevVerifierPayload).toPresentationPartyRecord(),
        )

        store.eventLogger().addEvent(presentation(chain))

        val logged = assertIs<TransactionLogDomain.Presentation>(store.transactionLogs().single())
        assertEquals("Niscy", logged.party.name?.text)
        assertEquals("123456789", logged.party.identifier?.value)
        assertEquals(3, logged.party.contacts.size)
        val registration = logged.registration!!
        assertEquals(listOf("geral@cnpd.pt", "+351213928400", "https://www.cnpd.pt/contactos"), registration.dpa?.contacts)
        assertEquals("For testing purposes only", registration.purpose)
        assertEquals("https://registry.example/wrp", registration.registrarUrl)
    }

    @Test
    fun a_record_is_handed_over_once_and_only_to_the_verifier_that_signed() = runTest {
        val (_, store) = storeOver()
        val consented = signer("Consented")
        val other = signer("Other")
        store.presentationPartyRecords.remember(
            consented.certificates.first(),
            issuerRegistrationFrom(euDevVerifierPayload).toPresentationPartyRecord(),
        )

        val forOther = store.eventLogger().addEvent(presentation(other))!!
        val forConsented = store.eventLogger().addEvent(presentation(consented))!!
        val again = store.eventLogger().addEvent(presentation(consented))!!

        assertNull(forOther.presentationPartyRecord())
        assertEquals("Niscy", forConsented.presentationPartyRecord()?.name)
        assertNull(again.presentationPartyRecord())
    }

    @Test
    fun without_a_record_a_presentation_has_no_registration_and_no_contacts() = runTest {
        val (_, store) = storeOver()

        store.eventLogger().addEvent(presentation(signer("Plain")))

        val logged = assertIs<TransactionLogDomain.Presentation>(store.transactionLogs().single())
        assertEquals("Plain", logged.party.name?.text)
        assertTrue(logged.party.contacts.isEmpty())
        assertNull(logged.registration)
    }

    // --- The deletion requests and reports kept under a presentation ---

    private suspend fun MultipazWalletStore.loggedPresentation(): TransactionLogDomain.Presentation {
        eventLogger().addEvent(presentation(signer("Verifier")))
        return transactionLogs().filterIsInstance<TransactionLogDomain.Presentation>().single()
    }

    private val launchedAt = Instant.parse("2026-10-03T11:11:00Z")

    @Test
    fun a_deletion_request_is_stored_once_and_read_back_with_what_was_shared() = runTest {
        val (_, store) = storeOver()
        val parent = store.loggedPresentation()
        val record = parent.toDataDeletionRequestRecord("attempt-1", launchedAt, CommunicationMethodDomain.Website)

        assertTrue(store.recordPresentationAction(record))
        // The same attempt again — a save retried after a failure — is accepted without a second row.
        assertTrue(store.recordPresentationAction(record))
        // A different attempt under the same id is refused, and the stored one is untouched.
        assertFalse(store.recordPresentationAction(record.copy(communicationMethod = "email")))

        val request = assertIs<TransactionLogDomain.DataDeletionRequest>(store.presentationActions(parent.id).single())
        assertEquals("attempt-1", request.id)
        assertEquals(parent.id, request.parentPresentationId)
        assertEquals(CommunicationMethodDomain.Website, request.communicationMethod)
        assertEquals(parent.party.name, request.party.name)
        assertEquals(
            listOf(ClaimPathSegment.Key("eu.europa.ec.eudi.pid.1"), ClaimPathSegment.Key("family_name")),
            request.claims.single().claims.single().segments,
        )
    }

    @Test
    fun a_report_keeps_the_authority_and_reads_back_newest_first() = runTest {
        val (_, store) = storeOver()
        val parent = store.loggedPresentation()
        val authority = DpaContactDomain(LocalizedTextDomain("und", "CNPD"), null, listOf("tel:+351213928400"))

        store.recordPresentationAction(authority.toDpaReportRecord("old", launchedAt, parent.id, CommunicationMethodDomain.Phone))
        store.recordPresentationAction(
            authority.toDpaReportRecord("new", launchedAt + 1.days, parent.id, CommunicationMethodDomain.Email)
        )

        val reports = store.presentationActions(parent.id)
        assertEquals(listOf("new", "old"), reports.map { it.id })
        assertEquals("CNPD", assertIs<TransactionLogDomain.DpaReport>(reports.first()).dpaName?.text)
    }

    @Test
    fun an_attempt_needs_a_presentation_that_exists_and_is_not_itself() = runTest {
        val (_, store) = storeOver()
        val parent = store.loggedPresentation()
        val issuance = store.eventLogger().addEvent(provisioning())!!
        val record = parent.toDataDeletionRequestRecord("attempt", launchedAt, CommunicationMethodDomain.Email)

        assertFalse(store.recordPresentationAction(record.copy(parentPresentationId = "missing")))
        assertFalse(store.recordPresentationAction(record.copy(parentPresentationId = issuance.identifier)))
        assertFalse(store.recordPresentationAction(record.copy(id = parent.id)))
        assertTrue(store.presentationActions(parent.id).isEmpty())
    }

    @Test
    fun deleting_the_presentation_deletes_its_attempts() = runTest {
        val (_, store) = storeOver()
        val parent = store.loggedPresentation()
        store.recordPresentationAction(parent.toDataDeletionRequestRecord("attempt", launchedAt, CommunicationMethodDomain.Website))

        store.deleteTransactionLog(parent.id)

        // The table first: reading the attempts would itself clear a presentation's orphans, which would
        // hide a delete that left them behind.
        assertTrue(store.presentationActionsTable().enumerate(partitionId = parent.id).isEmpty())
        assertTrue(store.presentationActions(parent.id).isEmpty())
    }

    @Test
    fun an_open_screen_sees_an_attempt_as_soon_as_it_is_recorded() = runTest {
        val (_, store) = storeOver()
        val parent = store.loggedPresentation()
        val seen = mutableListOf<List<TransactionLogDomain.PresentationAction>>()
        val watching = launch { store.observePresentationActions(parent.id).take(2).toList(seen) }
        testScheduler.advanceUntilIdle()

        store.recordPresentationAction(parent.toDataDeletionRequestRecord("attempt", launchedAt, CommunicationMethodDomain.Phone))
        watching.join()

        assertEquals(listOf(0, 1), seen.map { it.size })
    }
}
