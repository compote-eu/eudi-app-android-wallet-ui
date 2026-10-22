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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.X509CertChain
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * A remote request waits at consent for as long as the user takes, as on Android and in the official iOS
 * wallet. The ARF asks for re-authentication after a period of inactivity (WIAM_16, WIAM_16a), not for the
 * request to be answered on the user's behalf.
 */
class IosRemoteConsentWaitTest {

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

    private suspend fun walletWithPid(): MultipazWalletStore {
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
            documentManagerId = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
        )
        store.seedMdocDocument(
            docType = MDOC_PID_DOC_TYPE,
            displayName = "PID",
            namespace = MDOC_PID_DOC_TYPE,
            elements = samplePidElements(givenName = "Waiting"),
            policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
            issuerMetadata = sampleIssuerMetadata(MDOC_PID_DOC_TYPE),
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
        )
        return store
    }

    @Test
    fun a_remote_request_waits_for_the_user_however_long_they_take() = runTest {
        val store = walletWithPid()
        val verifier = Verifier("verifier.test", Crypto.createEcPrivateKey(EcCurve.P256))
        val presenter = IosRemotePresenter(
            walletEngine = IosWalletEngine { store },
            credentialDomain = store.documentManagerId,
            // The test's own scope, so an hour at consent passes in virtual time.
            scope = backgroundScope,
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

        presenter.start(verifier.link)
        presenter.state.first { it is IosRemotePresentationState.Requesting }
        advanceTimeBy(1.hours)

        assertIs<IosRemotePresentationState.Requesting>(presenter.state.value)
        presenter.decline()
    }

    private companion object {
        const val PID_GIVEN_NAME = """
            {"credentials":[{"id":"pid","format":"mso_mdoc",
              "meta":{"doctype_value":"eu.europa.ec.eudi.pid.1"},
              "claims":[{"path":["eu.europa.ec.eudi.pid.1","given_name"]}]}]}
        """
    }
}
