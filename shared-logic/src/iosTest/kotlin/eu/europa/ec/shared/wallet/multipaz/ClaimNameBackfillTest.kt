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


// Backfilling the issuer's claim names onto documents provisioned before those names were captured.
//
// Every case here is about *when not to fetch* and *what to persist*, because getting either wrong is
// the difference between one request per document and one per screen draw — or between "this issuer
// publishes no names" and "the network was down once".
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.document.IssuerMetadata
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.MDOC_PID_DOC_TYPE
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaimNameBackfillTest {

    private val metadataWithNames = """
        {"credential_issuer":"https://dev.issuer.test",
         "credential_configurations_supported":{
           "pid":{"doctype":"eu.europa.ec.eudi.pid.1","credential_metadata":{"claims":[
             {"path":["eu.europa.ec.eudi.pid.1","family_name"],
              "display":[{"name":"Family Name(s)","locale":"en"}]}]}}}}
    """.trimIndent()

    private val metadataWithoutNames = """
        {"credential_issuer":"https://dev.issuer.test",
         "credential_configurations_supported":{
           "pid":{"doctype":"eu.europa.ec.eudi.pid.1"}}}
    """.trimIndent()

    /** A document as it looks before claim names were captured: a real issuer URL, `claims` unset. */
    private suspend fun documentWithUnnamedClaims(
        issuerIdentifier: String = "https://dev.issuer.test",
    ): Pair<MultipazWalletStore, String> {
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )
        val documentId = store.seedMdocDocument(
            docType = MDOC_PID_DOC_TYPE,
            displayName = "PID",
            namespace = MDOC_PID_DOC_TYPE,
            elements = samplePidElements(),
            policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
            issuerMetadata = IssuerMetadata(
                documentConfigurationIdentifier = MDOC_PID_DOC_TYPE,
                credentialIssuerIdentifier = issuerIdentifier,
                claims = null,
            ),
        )
        return store to documentId
    }

    @Test
    fun a_document_with_no_claim_names_gets_them_from_the_issuer_and_keeps_them() = runTest {
        var fetches = 0
        val (store, documentId) = documentWithUnnamedClaims()
        val engine = MultipazWalletEngine(
            store = store,
            claimNameHttpEngine = MockEngine {
                fetches++
                respond(metadataWithNames, headers = headersOf("Content-Type", "application/json"))
            },
        )

        assertEquals(
            mapOf("family_name" to "Family Name(s)"),
            engine.getClaimDisplayNames(documentId, "en"),
        )
        // Asked again: the names were persisted, so nothing is fetched twice. Without this the details
        // screen would reach the network on every draw.
        assertEquals(
            mapOf("family_name" to "Family Name(s)"),
            engine.getClaimDisplayNames(documentId, "en"),
        )
        assertEquals(1, fetches)
    }

    @Test
    fun an_issuer_publishing_no_names_settles_the_document_instead_of_being_asked_again() = runTest {
        var fetches = 0
        val (store, documentId) = documentWithUnnamedClaims()
        val engine = MultipazWalletEngine(
            store = store,
            claimNameHttpEngine = MockEngine {
                fetches++
                respond(metadataWithoutNames, headers = headersOf("Content-Type", "application/json"))
            },
        )

        assertTrue(engine.getClaimDisplayNames(documentId, "en").isEmpty())
        assertTrue(engine.getClaimDisplayNames(documentId, "en").isEmpty())
        // An empty answer is a real answer and is stored: "looked, there are none" is not "never looked".
        assertEquals(1, fetches)
    }

    @Test
    fun a_failed_fetch_is_not_retried_within_the_same_run() = runTest {
        var fetches = 0
        val (store, documentId) = documentWithUnnamedClaims()
        val mockEngine = MockEngine { fetches++; respondError(HttpStatusCode.ServiceUnavailable) }
        val engine = MultipazWalletEngine(store = store, claimNameHttpEngine = mockEngine)

        assertTrue(engine.getClaimDisplayNames(documentId, "en").isEmpty())
        assertTrue(engine.getClaimDisplayNames(documentId, "en").isEmpty())
        // One attempt per document per run. A fixture pointing at an unresolvable host produced three
        // DNS lookups in a single probe run before this guard existed.
        assertEquals(1, fetches)
    }

    @Test
    fun a_failed_fetch_is_retried_on_the_next_run() = runTest {
        var fetches = 0
        val (store, documentId) = documentWithUnnamedClaims()
        fun engine() = MultipazWalletEngine(
            store = store,
            claimNameHttpEngine = MockEngine { fetches++; respondError(HttpStatusCode.ServiceUnavailable) },
        )

        assertTrue(engine().getClaimDisplayNames(documentId, "en").isEmpty())
        assertTrue(engine().getClaimDisplayNames(documentId, "en").isEmpty())
        // The guard is in memory on purpose: being offline once must not brand a document as unnamed
        // forever, because that is a property of the network and not of the document. Nothing is
        // persisted on failure, so a later launch asks again.
        assertEquals(2, fetches)
    }

    @Test
    fun a_document_whose_issuer_is_a_name_rather_than_a_url_is_never_fetched_for() = runTest {
        var fetches = 0
        // Exactly the documents issued before the issuer-identifier fix, which stored the issuer's
        // display name here. There is nothing to fetch from "Digital Credentials Issuer", and trying
        // would be a wasted request on every draw.
        val (store, documentId) = documentWithUnnamedClaims(
            issuerIdentifier = "Digital Credentials Issuer",
        )
        val engine = MultipazWalletEngine(
            store = store,
            claimNameHttpEngine = MockEngine { fetches++; respondError(HttpStatusCode.NotFound) },
        )

        assertTrue(engine.getClaimDisplayNames(documentId, "en").isEmpty())
        assertEquals(0, fetches)
    }
}
