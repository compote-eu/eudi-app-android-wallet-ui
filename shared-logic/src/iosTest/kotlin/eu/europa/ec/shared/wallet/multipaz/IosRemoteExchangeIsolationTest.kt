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
import eu.europa.ec.shared.wallet.trust.certChain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Each remote exchange is judged, named and shown on its own — the iOS side of wallet-core #440, where one
 * shared trust object handed a request the verdict of the request before it.
 *
 * Driven through the real presenter and multipaz, with a `MockEngine` playing the verifiers: each host serves
 * a request object really signed by its own certificate, asking for a document nobody holds, so an exchange
 * ends at "nothing to share" — the outcome that names the verifier and says whether anyone vouches for it.
 */
class IosRemoteExchangeIsolationTest {

    private class Verifier(val host: String, val certificate: X509Cert, private val key: EcPrivateKey) {
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
                    put("nonce", "nonce-$host")
                    put("state", "state-$host")
                    put("dcql_query", Json.parseToJsonElement(NOTHING_ANYONE_HOLDS))
                },
                type = "oauth-authz-req+jwt",
                x5c = X509CertChain(listOf(certificate)),
            )
        }
    }

    private suspend fun verifier(host: String, name: String): Verifier {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        return Verifier(host, testVerifierCertificate(dnsNames = listOf(host), commonName = name, key = key), key)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * A presenter whose verifiers are [served] — a host in [silent] never answers — and whose trusted list
     * vouches for exactly [trusted].
     */
    private fun presenter(
        served: List<Verifier>,
        trusted: List<Verifier>,
        silent: Set<String> = emptySet(),
        isRegistrationCheckEnabled: suspend () -> Boolean = { false },
    ) = IosRemotePresenter(
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
        readerTrust = ReaderTrustSource { requester ->
            val signer = requester.certChain?.certificates?.firstOrNull()?.encoded
            TrustMetadata().takeIf { trusted.any { it.certificate.encoded == signer } }
        },
        isRegistrationCheckEnabled = isRegistrationCheckEnabled,
        httpEngine = {
            MockEngine { request ->
                if (request.url.host in silent) awaitCancellation()
                respond(
                    content = served.single { it.host == request.url.host }.requestObject(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/oauth-authz-req+jwt"),
                )
            }
        },
    )

    /** The presenter's state once the exchange has ended, in real time: it runs on [Dispatchers.Default]. */
    private suspend fun IosRemotePresenter.outcome() = withContext(Dispatchers.Default) {
        withTimeout(OUTCOME_TIMEOUT) {
            state.first { it !is IosRemotePresentationState.Idle && it !is IosRemotePresentationState.Resolving }
        }
    }

    @Test
    fun each_exchange_is_named_and_judged_by_its_own_verifier() = runTest {
        val first = verifier("first.test", "First Verifier")
        val second = verifier("second.test", "Second Verifier")
        val untrusted = verifier("untrusted.test", "Untrusted Verifier")
        val presenter = presenter(served = listOf(first, second, untrusted), trusted = listOf(first, second))
        try {
            presenter.start(first.link)
            val firstOutcome = assertIs<IosRemotePresentationState.NothingToShare>(presenter.outcome())
            assertEquals("First Verifier", firstOutcome.requesterName)
            assertTrue(firstOutcome.requesterIsTrusted)

            presenter.start(second.link)
            val secondOutcome = assertIs<IosRemotePresentationState.NothingToShare>(presenter.outcome())
            assertEquals("Second Verifier", secondOutcome.requesterName)
            assertTrue(secondOutcome.requesterIsTrusted)

            // Two trusted verifiers before it, and still refused on its own certificate.
            presenter.start(untrusted.link)
            assertEquals(IosRemotePresentationState.VerifierNotTrusted, presenter.outcome())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_superseded_exchange_does_not_overwrite_the_next_one() = runTest {
        val first = verifier("first.test", "First Verifier")
        val next = verifier("next.test", "Next Verifier")
        val superseded = CompletableDeferred<Unit>()
        lateinit var presenter: IosRemotePresenter
        presenter = presenter(
            served = listOf(first),
            trusted = listOf(first, next),
            // The next verifier's request never arrives, so its exchange stays `Resolving` — unless something
            // else writes over it.
            silent = setOf(next.host),
            // The first exchange asks this as it ends with "nothing to share", just before it shows that. A
            // second link arriving at that very moment is what this makes deterministic: the first exchange
            // has been cancelled, but runs on to its next suspension point.
            isRegistrationCheckEnabled = {
                if (!superseded.isCompleted) {
                    presenter.start(next.link)
                    superseded.complete(Unit)
                }
                false
            },
        )
        try {
            presenter.start(first.link)
            withContext(Dispatchers.Default) { withTimeout(OUTCOME_TIMEOUT) { superseded.await() } }

            // The first exchange does not suspend between there and its outcome, so a second is ample.
            val overwritten = withContext(Dispatchers.Default) {
                withTimeoutOrNull(1.seconds) {
                    presenter.state.first { it !is IosRemotePresentationState.Resolving }
                }
            }

            assertNull(overwritten, "the superseded exchange's outcome replaced the next one's state")
            assertEquals(IosRemotePresentationState.Resolving, presenter.state.value)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        val OUTCOME_TIMEOUT = 20.seconds

        /** A DCQL query for a document type no wallet holds. */
        const val NOTHING_ANYONE_HOLDS = """
            {"credentials":[{"id":"nothing","format":"mso_mdoc",
              "meta":{"doctype_value":"org.example.nothing.1"},
              "claims":[{"path":["org.example.nothing.1","nothing"]}]}]}
        """
    }
}
