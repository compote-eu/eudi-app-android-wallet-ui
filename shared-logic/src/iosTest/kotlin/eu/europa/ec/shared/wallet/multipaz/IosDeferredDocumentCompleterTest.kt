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

import eu.europa.ec.shared.wallet.WalletDocumentIssuanceState
import eu.europa.ec.shared.wallet.document.IssuerMetadata
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.issuerNamespacesOf
import eu.europa.ec.shared.wallet.multipaz.harness.issuerSignedDataFor
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborMap
import org.multipaz.document.Document
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wallet half of deferred issuance: does a **parked document actually become an issued one** when
 * the issuer finally mints its credential?
 *
 * [IosDeferredCredentialCollectorTest] pins the protocol; this pins what the wallet does with the
 * answer, which is the part a user sees. The document here is built in the state a `202 Accepted`
 * leaves behind — a transaction id, stored authorization, and **pending (uncertified) credentials whose
 * keys multipaz created before the request** — because that state is the whole reason the flow is
 * possible: the credential the issuer mints is bound to those keys.
 */
class IosDeferredDocumentCompleterTest {

    private val docType = "eu.europa.ec.eudi.pid.1"
    private val issuerUrl = "https://issuer.test"
    private val deferredEndpoint = "https://issuer.test/wallet/deferredEndpoint"
    private val tokenEndpoint = "https://as.test/token"
    private val dpopAlias = "dpop-key-alias"
    private val policy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 1)

    private val issuer = IosVciIssuer(
        issuerUrl = issuerUrl,
        clientId = "eudiw-abca",
        redirectUri = "eu.europa.ec.euidi://authorization",
        order = 0,
    )

    private suspend fun storeOver(storage: Storage): MultipazWalletStore =
        MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )

    /**
     * A document in exactly the state a deferred issuance leaves: the issuer's handle, the
     * authorization multipaz stores, and one pending credential.
     */
    private suspend fun MultipazWalletStore.parkWithPendingCredential(
        transactionId: String = "txn-abc-123",
        refreshToken: String = "the-refresh-token",
    ): Document {
        keySecureArea.createKey(dpopAlias, SoftwareCreateKeySettings.Builder().build())
        val document = documentStore.createDocument(
            displayName = "PID (deferred)",
            metadata = EudiDocumentMetadata.create(
                documentManagerId = documentManagerId,
                format = StoredDocumentFormat.MsoMdoc(docType),
                credentialPolicy = policy,
                deferredTransactionId = transactionId,
                issuerMetadata = IssuerMetadata(
                    documentConfigurationIdentifier = "mso_mdoc",
                    credentialIssuerIdentifier = issuerUrl,
                ),
            ),
        )
        // The CBOR multipaz writes. Only the two members this flow reads are set; the rest is absent,
        // which is legal there since every member is optional.
        val authorization = Cbor.encode(
            buildCborMap {
                put("dpopKeyAlias", Tstr(dpopAlias))
                put("refreshToken", Tstr(refreshToken))
            }
        )
        document.edit { authorizationData = ByteString(*authorization) }
        MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = documentManagerId,
            secureArea = keySecureArea,
            docType = docType,
            createKeySettings = SoftwareCreateKeySettings.Builder().build(),
        )
        return document
    }

    /** The bytes an issuer would return for [document]'s waiting credential, base64url as on the wire. */
    private suspend fun issuedCredentialFor(document: Document): String {
        val pending = document.getPendingCredentials().single() as MdocCredential
        val data = issuerSignedDataFor(
            docType = docType,
            issuerNamespaces = issuerNamespacesOf(docType, samplePidElements(), Random.Default),
            deviceKey = pending.secureArea.getKeyInfo(pending.alias).publicKey,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
        )
        return data.toByteArray().toBase64Url()
    }

    private fun completerOver(
        store: MultipazWalletStore,
        deferredStatus: HttpStatusCode,
        deferredBody: String,
    ): IosDeferredDocumentCompleter {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.endsWith(".well-known/openid-credential-issuer") -> respond(
                    """{"credential_issuer":"$issuerUrl",""" +
                            """"deferred_credential_endpoint":"$deferredEndpoint",""" +
                            """"authorization_servers":["https://as.test"]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )

                url.endsWith(".well-known/openid-configuration") ->
                    respond("""{"token_endpoint":"$tokenEndpoint"}""", HttpStatusCode.OK, jsonHeaders)

                url.endsWith("/wallet-instance-attestation/jwk") -> respond(
                    """{"walletInstanceAttestation":"wia.jwt"}""", HttpStatusCode.OK, jsonHeaders
                )

                url == tokenEndpoint ->
                    respond("""{"access_token":"the-token"}""", HttpStatusCode.OK, jsonHeaders)

                url == deferredEndpoint -> respond(deferredBody, deferredStatus, jsonHeaders)

                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return IosDeferredDocumentCompleter(
            store = store,
            httpClient = HttpClient(engine),
            walletProviderBaseUrl = "https://wallet-provider.test",
            issuers = listOf(issuer),
        )
    }

    @Test
    fun a_parked_document_is_finished_when_the_issuer_finally_mints_the_credential() = runTest {
        val storage = EphemeralStorage()
        val store = storeOver(storage)
        val document = store.parkWithPendingCredential()
        val credential = issuedCredentialFor(document)

        val result = completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[{"credential":"$credential"}]}""",
        ).complete(document)

        assertIs<DeferredCollection.Issued>(result)

        // The document is now an ordinary issued one, read back the way the UI reads it.
        val read = MultipazWalletEngine(store).getAllDocumentsWithDetails(locale = "en").single()
        assertEquals(WalletDocumentIssuanceState.Issued, read.issuanceState)
        assertNotNull(read.issuedAt)
        assertEquals(1, read.credentialsCount)

        // And the handle is cleared, so nothing polls for it again.
        assertNull(assertNotNull(document.eudiMetadata).deferredTransactionId)
        assertTrue(document.getPendingCredentials().isEmpty())
    }

    @Test
    fun an_issuer_still_working_on_it_leaves_the_document_exactly_as_it_was() = runTest {
        val storage = EphemeralStorage()
        val store = storeOver(storage)
        val document = store.parkWithPendingCredential()

        val result = completerOver(
            store = store,
            deferredStatus = HttpStatusCode.BadRequest,
            deferredBody = """{"error":"issuance_pending","interval":5}""",
        ).complete(document)

        assertIs<DeferredCollection.StillPending>(result)

        // Nothing may be consumed by a poll that came back empty-handed: the handle must survive for
        // the next attempt, and the credential must stay pending so the issuer's eventual answer still
        // has the key it was minted for.
        val read = MultipazWalletEngine(store).getAllDocumentsWithDetails(locale = "en").single()
        assertEquals(WalletDocumentIssuanceState.Pending, read.issuanceState)
        assertEquals("txn-abc-123", assertNotNull(document.eudiMetadata).deferredTransactionId)
        assertEquals(1, document.getPendingCredentials().size)
    }

    @Test
    fun a_document_whose_dpop_key_is_gone_reports_expired_rather_than_failing_obscurely() = runTest {
        val storage = EphemeralStorage()
        val store = storeOver(storage)
        val document = store.parkWithPendingCredential()
        store.keySecureArea.deleteKey(dpopAlias)

        val result = completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[]}""",
        ).complete(document)

        // Nothing can be signed without that key, so the honest answer is "authorize it again".
        assertIs<DeferredCollection.AuthorizationExpired>(result)
        assertEquals("txn-abc-123", assertNotNull(document.eudiMetadata).deferredTransactionId)
    }

    @Test
    fun a_document_that_is_not_waiting_on_anything_is_left_alone() = runTest {
        val storage = EphemeralStorage()
        val store = storeOver(storage)
        val document = store.documentStore.createDocument(
            displayName = "PID",
            metadata = EudiDocumentMetadata.create(
                documentManagerId = store.documentManagerId,
                format = StoredDocumentFormat.MsoMdoc(docType),
                credentialPolicy = policy,
            ),
        )

        val result = completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[]}""",
        ).complete(document)

        assertIs<DeferredCollection.Failed>(result)
    }

    private companion object {
        val jsonHeaders = headersOf("Content-Type", listOf("application/json"))
    }
}
