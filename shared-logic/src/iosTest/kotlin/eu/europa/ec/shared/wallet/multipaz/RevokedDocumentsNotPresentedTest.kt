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

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.MDOC_PID_DOC_TYPE
import eu.europa.ec.shared.wallet.multipaz.harness.sampleIssuerMetadata
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import eu.europa.ec.shared.wallet.trust.ReaderTrustSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.presentment.CredentialQueryResult
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * A document the revocation check flagged is never offered to anyone: not to a reader in person, not to a
 * remote verifier. The Digital Credentials path, the system picker's registry and the refresh that keeps it
 * current are pinned in their own tests (`IosDcApiPresenterTest`, `IosDocumentRegistrationTest`,
 * `MultipazWalletEngineTest`).
 *
 * Until 2026-10-21 iOS offered them and released one when the user agreed — measured on the simulator with a
 * revoked PID fixture and the EU dev verifier. Android leaves them out of a request's options.
 */
class RevokedDocumentsNotPresentedTest {

    private suspend fun store(): MultipazWalletStore {
        val storage = EphemeralStorage()
        return MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
            documentManagerId = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
        )
    }

    private suspend fun MultipazWalletStore.seedPid(givenName: String): String = seedMdocDocument(
        docType = MDOC_PID_DOC_TYPE,
        displayName = "PID $givenName",
        namespace = MDOC_PID_DOC_TYPE,
        elements = samplePidElements(givenName = givenName),
        policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 3),
        issuerMetadata = sampleIssuerMetadata(MDOC_PID_DOC_TYPE),
        validFrom = Clock.System.now() - 1.days,
        validUntil = Clock.System.now() + 30.days,
    )

    /** What the revocation refresh writes for a document it flags. */
    private suspend fun MultipazWalletStore.revoke(documentId: String) {
        revokedDocumentsTable().insert(key = documentId, data = ByteString())
    }

    private fun CredentialQueryResult.documentIds(): Set<String> =
        credentialSets.flatMap { it.options }.flatMap { it.members }.flatMap { it.matches }
            .map { it.credential.document.identifier }.toSet()

    //region in person

    private suspend fun readerAskingForAPid(): DeviceRequest {
        val encoded = DeviceRequestGenerator(encodedSessionTranscript = Cbor.encode(Simple.NULL))
            .addDocumentRequest(
                docType = MDOC_PID_DOC_TYPE,
                itemsToRequest = mapOf(MDOC_PID_DOC_TYPE to mapOf("given_name" to false)),
                requestInfo = null,
                readerKey = null,
                signatureAlgorithm = Algorithm.UNSET,
                readerKeyCertificateChain = null,
            )
            .generate()
        return DeviceRequest.fromDataItem(Cbor.decode(encoded)).also { it.verifyReaderAuthentication(Simple.NULL) }
    }

    /** The in-person exchange through the source every presenter uses, consent answered by [consent]. */
    private suspend fun presentInPerson(store: MultipazWalletStore, consent: (CredentialQueryResult) -> Unit) =
        iosMdocPresentment(
            deviceRequest = readerAskingForAPid(),
            eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
            sessionTranscript = Simple.NULL,
            source = walletPresentmentSource(
                store = store,
                credentialDomain = store.documentManagerId,
                readerTrust = null,
                offersSdJwt = false,
            ) { _, _, data ->
                consent(data.credentialQueryResult)
                null
            },
            keyAgreementPossible = listOf(EcCurve.P256),
            requesterAppId = null,
            requesterOrigin = null,
        )

    @Test
    fun a_reader_is_offered_only_the_documents_that_are_not_revoked() = runTest {
        val store = store()
        val valid = store.seedPid("Valid")
        val revoked = store.seedPid("Revoked")
        store.revoke(revoked)
        var offered: Set<String>? = null

        // Declining at consent ends the exchange; what was offered is all this test asks.
        runCatching { presentInPerson(store) { offered = it.documentIds() } }

        assertEquals(setOf(valid), offered)
    }

    @Test
    fun a_reader_asking_only_for_a_revoked_document_gets_nothing_to_share() = runTest {
        val store = store()
        store.revoke(store.seedPid("Revoked"))
        var asked = false

        assertFailsWith<PresentmentCannotSatisfyRequestException> { presentInPerson(store) { asked = true } }
        assertFalse(asked, "consent must not be reached")
    }

    //endregion

    //region remote

    private class Verifier(val host: String, private val key: EcPrivateKey) {
        private val clientId = "x509_san_dns:$host"

        val link = "openid4vp://?client_id=${clientId.encodeURLParameter()}" +
            "&request_uri=${"https://$host/request".encodeURLParameter()}"

        suspend fun requestObject(): String {
            val responseEncryptionKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey.toJwk()
            return JsonWebSignature.sign(
                key = key,
                signatureAlgorithm = Algorithm.ESP256,
                claimsSet = buildJsonObject {
                    put("client_id", clientId)
                    put("response_type", "vp_token")
                    // Encrypted or not at all, and multipaz reads the key before consent.
                    put("response_mode", "direct_post.jwt")
                    putJsonObject("client_metadata") {
                        putJsonObject("jwks") { putJsonArray("keys") { add(responseEncryptionKey) } }
                    }
                    put("response_uri", "https://$host/response")
                    put("nonce", "nonce")
                    put("dcql_query", Json.parseToJsonElement(PID_GIVEN_NAME))
                },
                type = "oauth-authz-req+jwt",
                x5c = X509CertChain(listOf(testVerifierCertificate(dnsNames = listOf(host), key = key))),
            )
        }
    }

    /** Runs one remote exchange against [store] and returns the state it ends, or waits for consent, in. */
    private suspend fun presentRemotely(store: MultipazWalletStore): IosRemotePresentationState {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val verifier = Verifier("verifier.test", Crypto.createEcPrivateKey(EcCurve.P256))
        val presenter = IosRemotePresenter(
            walletEngine = IosWalletEngine { store },
            credentialDomain = store.documentManagerId,
            scope = scope,
            readerTrust = ReaderTrustSource { TrustMetadata() },
            isRegistrationCheckEnabled = { false },
            httpEngine = {
                MockEngine {
                    respond(
                        content = verifier.requestObject(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/oauth-authz-req+jwt"),
                    )
                }
            },
        )
        try {
            presenter.start(verifier.link)
            return withContext(Dispatchers.Default) {
                withTimeout(20.seconds) {
                    presenter.state.first {
                        it !is IosRemotePresentationState.Idle && it !is IosRemotePresentationState.Resolving
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_remote_verifier_is_offered_only_the_documents_that_are_not_revoked() = runTest {
        val store = store()
        val valid = store.seedPid("Valid")
        store.revoke(store.seedPid("Revoked"))

        val requesting = assertIs<IosRemotePresentationState.Requesting>(presentRemotely(store))

        assertEquals(
            setOf(valid),
            requesting.request.combinations.flatMap { it.documents }.map { it.documentId }.toSet(),
        )
    }

    @Test
    fun a_remote_verifier_asking_only_for_a_revoked_document_gets_nothing_to_share() = runTest {
        val store = store()
        store.revoke(store.seedPid("Revoked"))

        assertIs<IosRemotePresentationState.NothingToShare>(presentRemotely(store))
    }

    //endregion

    private companion object {
        const val PID_GIVEN_NAME = """
            {"credentials":[{"id":"pid","format":"mso_mdoc",
              "meta":{"doctype_value":"eu.europa.ec.eudi.pid.1"},
              "claims":[{"path":["eu.europa.ec.eudi.pid.1","given_name"]}]}]}
        """
    }
}
