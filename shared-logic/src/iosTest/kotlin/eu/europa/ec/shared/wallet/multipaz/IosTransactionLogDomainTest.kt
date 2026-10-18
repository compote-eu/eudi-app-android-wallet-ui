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

// multipaz's events read as the shared transaction domain. The decisions a user would see if they were
// wrong: an mdoc claim recorded as namespace + element (what Android's entries hold, and what the
// details screen renders as ["ns"]["el"]), the credential named by the type the verifier asked for, the
// verifier named even when multipaz recorded a blank name, issuance told apart from re-issuance, and
// nothing that is not a transaction.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Tstr
import org.multipaz.claim.Claim
import org.multipaz.claim.JsonClaim
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
import org.multipaz.eventlogger.EventSimple
import org.multipaz.provisioning.Display
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class IosTransactionLogDomainTest {

    private val pidDocType = "eu.europa.ec.eudi.pid.1"
    private val pidNamespace = "eu.europa.ec.eudi.pid.1"
    private val pidVct = "urn:eudi:pid:1"

    /** A store holding none of the documents an event names, as after their deletion. */
    private val noStoredFormat: suspend (String) -> String? = { null }

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

    private fun mdocFamilyName(): Pair<RequestedClaim, Claim> =
        MdocRequestedClaim(
            docType = pidDocType,
            namespaceName = pidNamespace,
            dataElementName = "family_name",
            intentToRetain = false,
        ) to MdocClaim(
            displayName = "Family name",
            attribute = null,
            docType = pidDocType,
            namespaceName = pidNamespace,
            dataElementName = "family_name",
            value = Tstr("Doe"),
        )

    private fun sdJwtStreet(): Pair<RequestedClaim, Claim> {
        val path = buildJsonArray {
            add("address")
            add(JsonPrimitive(0))
            add("street_address")
        }
        return JsonRequestedClaim(vctValues = listOf(pidVct), claimPath = path) to
                JsonClaim(
                    displayName = "Street",
                    attribute = null,
                    vct = pidVct,
                    claimPath = path,
                    value = JsonPrimitive("Main 1"),
                )
    }

    private fun presentation(
        requesterName: String?,
        certChain: X509CertChain? = null,
        claims: Map<RequestedClaim, Claim> = mapOf(mdocFamilyName()),
        timestamp: Instant = Clock.System.now(),
        identifier: String = "presentation",
    ) = EventPresentmentUriSchemeOpenID4VP(
        identifier = identifier,
        timestamp = timestamp,
        presentmentData = EventPresentmentData(
            requesterName = requesterName,
            requesterCertChain = certChain,
            trustMetadata = null,
            requestedDocuments = listOf(
                EventPresentmentDataDocument(documentId = "doc-1", documentName = "PID", claims = claims)
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

    private fun provisioning(initial: Boolean, issuerName: String = "Test Issuer") = EventProvisioning(
        identifier = "provisioning",
        issuerData = EventProvisioningIssuerDataOpenID4VCI(
            display = Display(text = issuerName, logo = null),
            url = "https://issuer.test",
            credentialId = "cred-1",
        ),
        initialProvisioning = initial,
        documentId = "doc-1",
        documentName = "PID",
        display = null,
        credentialsFetched = emptyMap(),
    )

    @Test
    fun an_mdoc_claim_is_recorded_as_namespace_then_element_under_the_requested_doc_type() = runTest {
        val transaction = assertIs<TransactionLogDomain.Presentation>(
            presentation(requesterName = "Trusted Verifier").toTransactionLogDomain(noStoredFormat)
        )

        val credential = transaction.claimsPresented.single()
        assertEquals(pidDocType, credential.credential.identifier)
        assertEquals(
            listOf(ClaimPathSegment.Key(pidNamespace), ClaimPathSegment.Key("family_name")),
            credential.claims.single().segments,
        )
        assertEquals(TransactionResultDomain.Completed, transaction.result)
        assertEquals("Trusted Verifier", transaction.party.name?.text)
        // multipaz keeps no record of claims requested but not shared.
        assertEquals(transaction.claimsPresented, transaction.claimsRequested)
        assertEquals(true, transaction.canRequestDataDeletion)
    }

    @Test
    fun an_sd_jwt_claim_keeps_its_claims_path_and_the_requested_vct() = runTest {
        val transaction = assertIs<TransactionLogDomain.Presentation>(
            presentation(requesterName = "V", claims = mapOf(sdJwtStreet())).toTransactionLogDomain(noStoredFormat)
        )

        val credential = transaction.claimsPresented.single()
        assertEquals(pidVct, credential.credential.identifier)
        assertEquals(
            listOf(
                ClaimPathSegment.Key("address"),
                ClaimPathSegment.Index(0),
                ClaimPathSegment.Key("street_address"),
            ),
            credential.claims.single().segments,
        )
    }

    @Test
    fun a_blank_requester_name_falls_back_to_the_certificate() = runTest {
        val transaction = assertIs<TransactionLogDomain.Presentation>(
            presentation(requesterName = " ", certChain = certificateNamed("Verifier Signer dev"))
                .toTransactionLogDomain(noStoredFormat)
        )

        assertEquals("Verifier Signer dev", transaction.party.name?.text)
    }

    @Test
    fun without_any_name_the_party_has_none() = runTest {
        val transaction = assertIs<TransactionLogDomain.Presentation>(
            presentation(requesterName = null).toTransactionLogDomain(noStoredFormat)
        )

        assertNull(transaction.party.name)
    }

    @Test
    fun first_provisioning_is_an_issuance_and_a_later_one_a_reissuance() = runTest {
        val formatOf: suspend (String) -> String? = { id -> if (id == "doc-1") pidDocType else null }

        val issuance = assertIs<TransactionLogDomain.CredentialIssuance>(
            provisioning(initial = true).toTransactionLogDomain(formatOf)
        )
        val reissuance = assertIs<TransactionLogDomain.CredentialReissuance>(
            provisioning(initial = false).toTransactionLogDomain(formatOf)
        )

        assertEquals("Test Issuer", issuance.details.issuer.name?.text)
        assertEquals(listOf(pidDocType), issuance.details.credentials.map { it.identifier })
        assertEquals(1, issuance.details.requestedCount)
        assertEquals(1, issuance.details.issuedCount)
        assertNull(issuance.details.isUserTriggered)
        assertEquals(issuance.details, reissuance.details)
    }

    @Test
    fun a_document_deleted_since_leaves_no_credential_and_a_blank_issuer_no_name() = runTest {
        val issuance = assertIs<TransactionLogDomain.CredentialIssuance>(
            provisioning(initial = true, issuerName = "").toTransactionLogDomain(noStoredFormat)
        )

        assertEquals(emptyList(), issuance.details.credentials)
        assertNull(issuance.details.issuer.name)
    }

    @Test
    fun a_free_text_event_is_not_a_transaction() = runTest {
        assertNull(EventSimple(data = ByteString("note".encodeToByteArray())).toTransactionLogDomain(noStoredFormat))
    }

    @Test
    fun the_store_lists_transactions_newest_first() = runTest {
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )
        val now = Clock.System.now()
        store.eventLogger().addEvent(presentation(requesterName = "Older", timestamp = now - 5.minutes))
        store.eventLogger().addEvent(presentation(requesterName = "Newer", timestamp = now))

        assertEquals(
            listOf("Newer", "Older"),
            store.transactionLogs().map { (it as TransactionLogDomain.Presentation).party.name?.text },
        )
    }

    @Test
    fun a_transaction_can_be_found_again_by_the_id_its_row_carries() = runTest {
        // What the details route does with the id the list put in it.
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(storage = storage, secureAreas = listOf(SoftwareSecureArea.create(storage)))
        store.eventLogger().addEvent(presentation(requesterName = "Verifier"))
        val logged = store.transactionLogs().single()

        assertEquals(logged, store.transactionLog(logged.id))
        // An entry that has aged out of the log is absent rather than an error.
        assertNull(store.transactionLog("no-such-transaction"))
    }

    @Test
    fun deleting_a_transaction_removes_only_that_one() = runTest {
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(storage = storage, secureAreas = listOf(SoftwareSecureArea.create(storage)))
        val now = Clock.System.now()
        store.eventLogger().addEvent(presentation(requesterName = "Kept", timestamp = now - 5.minutes))
        store.eventLogger().addEvent(presentation(requesterName = "Deleted", timestamp = now))
        val deleted = store.transactionLogs().first()

        store.deleteTransactionLog(deleted.id)
        // An id the log no longer holds is not an error.
        store.deleteTransactionLog(deleted.id)

        assertEquals(
            listOf("Kept"),
            store.transactionLogs().map { (it as TransactionLogDomain.Presentation).party.name?.text },
        )
    }

    @Test
    fun the_logger_is_the_same_one_every_caller_gets() = runTest {
        // Two loggers over one table would each keep their own initialization state for no benefit,
        // and the presenters resolve it independently of the reader.
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(storage = storage, secureAreas = listOf(SoftwareSecureArea.create(storage)))

        assertSame(store.eventLogger(), store.eventLogger())
    }
}
