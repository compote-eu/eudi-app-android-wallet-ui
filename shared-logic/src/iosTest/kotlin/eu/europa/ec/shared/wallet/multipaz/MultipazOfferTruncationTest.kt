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

// What a credential offer naming SEVERAL configurations actually gets issued.
//
// The offer screen lists what [IosCredentialOfferReader] finds, and that reader honours the whole
// `credential_configuration_ids` array. multipaz does not: `CredentialOffer.parseJson` keeps
// `credentialConfigurationIds[0]` and discards the rest, under its own comment "Right now only use the
// first configuration id" — 0.99.0 and `main` alike. So the screen can promise two documents and the
// wallet can request one, with nothing logged in between.
//
// These cases pin that difference on multipaz's REAL parser and its REAL pushed authorization request,
// not on a stand-in: the only thing faked here is the network.
package eu.europa.ec.shared.wallet.multipaz

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.encodeURLParameter
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.multipaz.crypto.Algorithm
import org.multipaz.provisioning.CredentialKeyAttestation
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class MultipazOfferTruncationTest {

    private val issuerUrl = "https://issuer.test"
    private val issuerMetadataUrl = "$issuerUrl/.well-known/openid-credential-issuer"
    private val asMetadataUrl = "$issuerUrl/.well-known/oauth-authorization-server"
    private val parEndpoint = "$issuerUrl/par"

    /**
     * Two configurations with **different** scopes, so a request for one cannot be mistaken for a
     * request for the other — that is what makes the truncation visible in the authorization request.
     */
    private val issuerMetadata = """
        {"credential_issuer":"$issuerUrl",
         "credential_endpoint":"$issuerUrl/credential",
         "credential_configurations_supported":{
           "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","scope":"pid_scope",
             "display":[{"name":"PID (MSO MDoc)","locale":"en"}]},
           "loyalty_mdoc":{"format":"mso_mdoc","doctype":"org.example.loyalty","scope":"loyalty_scope",
             "display":[{"name":"Loyalty card","locale":"en"}]}}}
    """.trimIndent()

    /** `none` keeps client authentication out of the way; this test is about the request's subject. */
    private val asMetadata = """
        {"issuer":"$issuerUrl",
         "authorization_endpoint":"$issuerUrl/authorize",
         "token_endpoint":"$issuerUrl/token",
         "pushed_authorization_request_endpoint":"$parEndpoint",
         "response_types_supported":["code"],
         "code_challenge_methods_supported":["S256"],
         "token_endpoint_auth_methods_supported":["none"]}
    """.trimIndent()

    /** An offer naming both configurations, in the order a wallet would show them. */
    private val twoConfigurationOffer = "openid-credential-offer://?credential_offer=" +
        """{"credential_issuer":"$issuerUrl","credential_configuration_ids":["pid_mdoc","loyalty_mdoc"]}"""
            .encodeURLParameter()

    private val seen = mutableListOf<String>()

    private fun engine(onPar: (String) -> Unit = {}) = MockEngine { request ->
        seen += request.url.toString()
        when (request.url.toString().substringBefore('?')) {
            issuerMetadataUrl ->
                respond(issuerMetadata, headers = headersOf("Content-Type", "application/json"))

            asMetadataUrl ->
                respond(asMetadata, headers = headersOf("Content-Type", "application/json"))

            parEndpoint -> {
                onPar(request.body.toByteArray().decodeToString())
                respond(
                    """{"request_uri":"urn:ietf:params:oauth:request_uri:x","expires_in":60}""",
                    HttpStatusCode.Created,
                    headersOf("Content-Type", "application/json"),
                )
            }

            else -> respond("", HttpStatusCode.OK)
        }
    }

    private val clientPreferences = OpenID4VCIClientPreferences(
        clientId = "eudiw-abca",
        redirectUrl = "eu.europa.ec.euidi://authorization",
        locales = listOf("en"),
        signingAlgorithms = listOf(Algorithm.ESP256),
    )

    /**
     * multipaz reads its collaborators out of the coroutine context. Its own implementation is
     * `internal`, but [BackendEnvironment] is public and everything it vends here is public too — which
     * is the whole reason this test can drive the real client from outside the library.
     */
    private suspend fun environment(httpClient: HttpClient): BackendEnvironment {
        val secureArea = SoftwareSecureArea.create(EphemeralStorage())
        // ⛔ NOT the default: SecureAreaProvider's context defaults to Dispatchers.Main, and a
        // Kotlin/Native test binary pumps no main run loop, so its LAZY async never starts and get()
        // awaits for ever — a silent hang with no HTTP and no error.
        val secureAreaProvider = SecureAreaProvider(Dispatchers.Default) { secureArea }
        val backend = object : OpenID4VCIBackend {
            override suspend fun getClientId(): String = clientPreferences.clientId
            override suspend fun createJwtClientAssertion(authorizationServerIdentifier: String): String =
                throw UnsupportedOperationException("the test server asks for no client authentication")

            override suspend fun createJwtWalletAttestation(keyAttestation: KeyAttestation): String =
                throw UnsupportedOperationException("the test server asks for no client authentication")

            override suspend fun createJwtKeyAttestation(
                credentialKeyAttestations: List<CredentialKeyAttestation>,
                challenge: String,
                userAuthentication: List<String>?,
                keyStorage: List<String>?,
            ): String = throw UnsupportedOperationException("no credentials are requested here")
        }
        return object : BackendEnvironment {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> getInterface(clazz: KClass<T>): T? = when (clazz) {
                HttpClient::class -> httpClient
                SecureAreaProvider::class -> secureAreaProvider
                OpenID4VCIClientPreferences::class -> clientPreferences
                OpenID4VCIBackend::class -> backend
                else -> null
            } as T?
        }
    }

    @Test
    fun our_reader_names_every_configuration_the_offer_carries() = runTest {
        val resolution = IosCredentialOfferReader(engine = engine())
            .resolve(offerUri = twoConfigurationOffer, locale = "en")

        val resolved = assertIs<IosOfferResolution.Resolved>(resolution)

        // What the offer screen puts in front of the user: two documents.
        assertEquals(listOf("PID (MSO MDoc)", "Loyalty card"), resolved.documentNames)
        assertEquals(listOf("pid_mdoc", "loyalty_mdoc"), resolved.offer.configurationIds)
    }

    @Test
    fun multipaz_asks_the_issuer_for_only_the_first_configuration() = runBlocking {
        var parBody = ""
        val httpClient = HttpClient(engine { parBody = it })

        withContext(Dispatchers.Default + environment(httpClient) + RpcAuthClientSession()) {
            val client = withTimeoutOrNull(30_000) {
                OpenID4VCI.createClientFromOffer(
                    offerUri = twoConfigurationOffer,
                    clientPreferences = clientPreferences,
                )
            } ?: fail("multipaz's client never came back; requests it made were: $seen")

            // Driving the real client: asking for the challenges is what performs the PAR.
            withTimeoutOrNull(30_000) { client.getAuthorizationChallenges() }
                ?: fail("the authorization request never came back; requests seen: $seen")
        }

        val sent = parBody.parseUrlEncodedParameters()
        // The offer named two credentials under two different scopes. One reaches the issuer.
        assertEquals("pid_scope", sent["scope"])
        assertTrue(
            "loyalty" !in parBody,
            "the second configuration should be absent from the request, but the body was: $parBody",
        )
    }
}
