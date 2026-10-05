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

package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.CredentialRefDomain
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.QualifiedIdentifierDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.MDOC_PID_DOC_TYPE
import eu.europa.ec.shared.wallet.multipaz.harness.sampleIssuerMetadata
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import kotlinx.coroutines.test.runTest
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One issuance session's History rows, by wallet-core 0.31.0's `CredentialIssuanceLogger` rules: a row
 * only once the user has authorized, written ahead and brought up to date, and a row of its own for each
 * deferred credential.
 */
class IosIssuanceLogTest {

    private suspend fun store(): MultipazWalletStore {
        val storage = EphemeralStorage()
        return MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
            documentManagerId = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
        )
    }

    private fun log(
        store: MultipazWalletStore,
        requested: Int = 1,
        reissuance: Boolean = false,
        userTriggered: Boolean = true,
        registration: IssuerRegistration? = null,
    ) = IosIssuanceLog(
        store = { store },
        reissuance = reissuance,
        userTriggered = userTriggered,
        requested = requested,
        registration = registration,
    )

    private val registration = IssuerRegistration(
        subject = "NTRSK-12345678",
        name = "Fixture",
        legalName = "Fixture Issuer s.r.o.",
        country = "SK",
        entitlements = listOf(IssuerEntitlements.PID),
        privacyPolicyUri = null,
        purpose = emptyList(),
        serviceDescription = emptyList(),
        providedAttestations = emptyList(),
        registeredCredentials = emptyList(),
        status = null,
        expiresAt = null,
        intermediaryIdentifier = null,
        infoUri = "https://fixture.example/info",
        supportUri = "https://fixture.example/support",
    )

    private suspend fun MultipazWalletStore.issuances() =
        transactionLogs().filterIsInstance<TransactionLogDomain.CredentialIssuance>()

    @Test
    fun a_session_that_never_got_past_authorization_leaves_no_row() = runTest {
        // Android's `IssueEvent.Started` comes after `authorize`; a login the user abandons logs nothing.
        val store = store()
        val log = log(store)

        log.failed(IllegalStateException("The user closed the browser."))
        log.finish()

        assertTrue(store.transactionLogs().isEmpty())
    }

    @Test
    fun a_started_session_is_written_ahead_as_not_completed() = runTest {
        val store = store()

        log(store, requested = 2).started(issuerName = "Digital Credentials Issuer", issuerNameLanguage = "en")

        // So an issuance the process does not survive still leaves its row.
        val row = store.issuances().single()
        assertEquals(TransactionResultDomain.NotCompleted(null), row.result)
        assertEquals(2, row.details.requestedCount)
        assertEquals(0, row.details.issuedCount)
        assertEquals(LocalizedTextDomain("en", "Digital Credentials Issuer"), row.details.issuer.name)
    }

    @Test
    fun a_completed_session_updates_the_same_row() = runTest {
        val store = store()
        val log = log(store, requested = 2)

        log.started(issuerName = "Digital Credentials Issuer")
        log.issued(MDOC_PID_DOC_TYPE)
        log.issued("urn:eudi:pid:1")
        log.finish()

        val row = store.issuances().single()
        assertEquals(TransactionResultDomain.Completed, row.result)
        assertEquals(2, row.details.issuedCount)
        assertEquals(
            listOf(CredentialRefDomain(MDOC_PID_DOC_TYPE), CredentialRefDomain("urn:eudi:pid:1")),
            row.details.credentials,
        )
        assertEquals(true, row.details.isUserTriggered)
    }

    @Test
    fun a_session_that_issued_only_some_is_not_completed_with_the_failure() = runTest {
        val store = store()
        val log = log(store, requested = 2)

        log.started(issuerName = null)
        log.issued(MDOC_PID_DOC_TYPE)
        log.failed(IllegalStateException("invalid_proof\nat line 3"))
        log.finish()

        val row = store.issuances().single()
        assertEquals(TransactionResultDomain.NotCompleted("invalid_proof"), row.result)
        assertEquals(1, row.details.issuedCount)
    }

    @Test
    fun a_failure_with_no_usable_message_takes_wallet_cores_reason() = runTest {
        val store = store()
        val log = log(store)

        log.started(issuerName = null)
        log.failed(IllegalStateException())
        log.finish()

        assertEquals(TransactionResultDomain.NotCompleted(REASON_ISSUANCE_FAILED), store.issuances().single().result)
    }

    @Test
    fun a_whole_session_failure_is_not_completed_with_its_reason() = runTest {
        val store = store()
        val log = log(store)

        log.started(issuerName = null)
        log.finish(overallError = IllegalStateException("The authorization server went away."))

        assertEquals(
            TransactionResultDomain.NotCompleted("The authorization server went away."),
            store.issuances().single().result,
        )
    }

    @Test
    fun a_deferred_credential_gets_an_awaiting_row_of_its_own() = runTest {
        val store = store()
        val log = log(store, requested = 2)

        log.started(issuerName = "Digital Credentials Issuer")
        log.issued(MDOC_PID_DOC_TYPE)
        log.deferred("parked-document")
        log.finish()

        val rows = store.issuances().associateBy { it.id }
        val awaiting = assertIs<TransactionLogDomain.CredentialIssuance>(rows[deferredRowId("parked-document")])
        assertEquals(TransactionResultDomain.NotCompleted(REASON_ISSUANCE_DEFERRED), awaiting.result)
        assertEquals(1, awaiting.details.requestedCount)
        // The session's own row counts only what it resolved right away, and that is complete.
        val session = rows.values.single { it.id != awaiting.id }
        assertEquals(TransactionResultDomain.Completed, session.result)
        assertEquals(1, session.details.requestedCount)
    }

    @Test
    fun a_session_that_deferred_everything_says_so_on_its_own_row() = runTest {
        val store = store()
        val log = log(store, requested = 1)

        log.started(issuerName = null)
        log.deferred("parked-document")
        log.finish()

        val row = store.issuances().single()
        assertEquals(TransactionResultDomain.NotCompleted(REASON_ISSUANCE_DEFERRED), row.result)
    }

    @Test
    fun a_reissuance_is_recorded_as_one() = runTest {
        val store = store()
        val log = log(store, reissuance = true, userTriggered = false)

        log.started(issuerName = null)
        log.issued(MDOC_PID_DOC_TYPE)
        log.finish()

        val row = assertIs<TransactionLogDomain.CredentialReissuance>(store.transactionLogs().single())
        assertEquals(TransactionResultDomain.Completed, row.result)
        assertEquals(false, row.details.isUserTriggered)
    }

    @Test
    fun a_verified_registration_names_the_issuer_as_wallet_core_does() = runTest {
        val store = store()
        val log = log(store, registration = registration)

        log.started(issuerName = "Display name")
        log.finish()

        val issuer = store.issuances().single().details
        assertEquals(LocalizedTextDomain("und", "Fixture Issuer s.r.o."), issuer.issuer.name)
        // wallet-core's decoding: the register from the prefix, the value after the first hyphen.
        assertEquals(
            QualifiedIdentifierDomain("http://data.europa.eu/eudi/id/EUID", "12345678"),
            issuer.issuer.identifier,
        )
        assertEquals("PIDProvider", issuer.issuerType)
        assertEquals(
            listOf("SK", "https://fixture.example/support", "https://fixture.example/info"),
            issuer.issuer.contacts,
        )
    }

    @Test
    fun without_a_registration_the_issuer_is_known_by_its_display_name_alone() = runTest {
        val store = store()
        val log = log(store)

        log.started(issuerName = "Display name", issuerNameLanguage = "sk")
        log.finish()

        val details = store.issuances().single().details
        assertEquals(LocalizedTextDomain("sk", "Display name"), details.issuer.name)
        assertNull(details.issuer.identifier)
        assertNull(details.issuerType)
    }

    @Test
    fun a_finished_document_counts_as_issued_or_deferred_by_its_own_state() = runTest {
        val store = store()
        val issued = store.seedPid(issued = true)
        val pending = store.seedPid(issued = false)
        store.documentStore.lookupDocument(pending)!!.let { document ->
            val metadata = document.eudiMetadata!!
            metadata.park("txn-1")
            document.edit { this.metadata = metadata }
        }
        val log = log(store, requested = 2)

        log.started(issuerName = null)
        log.documentFinished(issued)
        log.documentFinished(pending)
        log.finish()

        val ids = store.issuances().map { it.id }
        assertTrue(deferredRowId(pending) in ids)
        val session = store.issuances().single { it.id != deferredRowId(pending) }
        assertEquals(listOf(CredentialRefDomain(MDOC_PID_DOC_TYPE)), session.details.credentials)
    }

    private suspend fun MultipazWalletStore.seedPid(issued: Boolean): String = seedMdocDocument(
        docType = MDOC_PID_DOC_TYPE,
        displayName = "PID",
        namespace = MDOC_PID_DOC_TYPE,
        elements = samplePidElements(),
        policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
        issuerMetadata = sampleIssuerMetadata(MDOC_PID_DOC_TYPE),
        markIssued = issued,
    )
}
