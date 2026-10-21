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

import eu.europa.ec.shared.wallet.trust.ReaderTrustSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.X509CertChain
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A verifier that asks for its response unencrypted gets nothing: no presentation, no consent screen, and no
 * error either — ARF OIA_09, the rule Android's wallet-core enforces as `EncryptionPolicy.HAIP`.
 *
 * Until 2026-10-21 iOS answered a plain `direct_post` request with the presentation in an unsigned,
 * unencrypted JWT (`alg: none`), because that is what multipaz builds for it (`uriSchemePresentment`).
 *
 * Driven through the real presenter and multipaz, with a `MockEngine` playing a verifier the trusted list
 * vouches for, so the refusal is the response mode's and nothing else's.
 */
class UnencryptedResponseRefusedTest {

    private val host = "verifier.test"
    private val clientId = "x509_san_dns:$host"
    private val link = "openid4vp://?client_id=${clientId.encodeURLParameter()}" +
        "&request_uri=${"https://$host/request".encodeURLParameter()}"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Every POST the verifier received: an answer, or an error response. */
    private val posts = MutableStateFlow(0)

    @AfterTest
    fun tearDown() = scope.cancel()

    private suspend fun presenter(responseMode: String?): IosRemotePresenter {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val requestObject = JsonWebSignature.sign(
            key = key,
            signatureAlgorithm = Algorithm.ESP256,
            claimsSet = buildJsonObject {
                put("client_id", clientId)
                put("response_type", "vp_token")
                responseMode?.let { put("response_mode", it) }
                put("response_uri", "https://$host/response")
                put("nonce", "nonce")
                put("state", "state")
                put("dcql_query", Json.parseToJsonElement(PID_GIVEN_NAME))
            },
            type = "oauth-authz-req+jwt",
            x5c = X509CertChain(listOf(testVerifierCertificate(dnsNames = listOf(host), key = key))),
        )
        return IosRemotePresenter(
            // An empty wallet on ephemeral storage: a test binary has no Keychain for the real store.
            walletEngine = IosWalletEngine {
                val storage = EphemeralStorage()
                MultipazWalletStore.build(
                    storage = storage,
                    secureAreas = listOf(SoftwareSecureArea.create(storage)),
                    documentManagerId = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
                )
            },
            credentialDomain = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
            scope = scope,
            readerTrust = ReaderTrustSource { TrustMetadata() },
            isRegistrationCheckEnabled = { false },
            httpEngine = {
                MockEngine { request ->
                    if (request.method == HttpMethod.Post) {
                        posts.update { it + 1 }
                        respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        respond(
                            content = requestObject,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/oauth-authz-req+jwt"),
                        )
                    }
                }
            },
        )
    }

    /** The exchange's outcome, in real time: the presenter runs on [Dispatchers.Default]. */
    private suspend fun IosRemotePresenter.outcome(): IosRemotePresentationState = withContext(Dispatchers.Default) {
        withTimeout(20.seconds) {
            state.first { it !is IosRemotePresentationState.Idle && it !is IosRemotePresentationState.Resolving }
        }
    }

    /** Closes the error screen as a user would, then gives any error response time to arrive. */
    private suspend fun IosRemotePresenter.closeAndSettle() = withContext(Dispatchers.Default) {
        reject()
        delay(500.milliseconds)
    }

    @Test
    fun a_verifier_asking_for_plain_direct_post_is_refused_and_sent_nothing() = runTest {
        val presenter = presenter(responseMode = "direct_post")

        presenter.start(link)
        val outcome = assertIs<IosRemotePresentationState.Failed>(presenter.outcome())
        presenter.closeAndSettle()

        assertEquals(UnencryptedResponseModeException().message, outcome.message)
        // Not the presentation, and not an error either — wallet-core tells such a verifier nothing.
        assertEquals(0, posts.value)
    }

    @Test
    fun a_request_naming_no_response_mode_is_refused_too() = runTest {
        // OpenID4VP's default is `fragment`, which is not encrypted either.
        val presenter = presenter(responseMode = null)

        presenter.start(link)
        assertIs<IosRemotePresentationState.Failed>(presenter.outcome())
        presenter.closeAndSettle()

        assertEquals(0, posts.value)
    }

    private companion object {
        const val PID_GIVEN_NAME = """
            {"credentials":[{"id":"pid","format":"mso_mdoc",
              "meta":{"doctype_value":"eu.europa.ec.eudi.pid.1"},
              "claims":[{"path":["eu.europa.ec.eudi.pid.1","given_name"]}]}]}
        """
    }
}
