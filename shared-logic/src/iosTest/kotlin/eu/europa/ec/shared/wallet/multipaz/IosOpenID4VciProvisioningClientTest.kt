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

// One authorization for several credentials. multipaz authorizes per configuration (multipaz#2026), so
// what these cases pin is the thing that replaces it: a single pushed authorization request naming every
// credential asked for, and a second client that raises no challenge at all — which is what stops the
// browser opening again. Only the network is faked.
package eu.europa.ec.shared.wallet.multipaz

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tstr
import org.multipaz.crypto.Algorithm
import org.multipaz.provisioning.AuthorizationChallenge
import org.multipaz.provisioning.AuthorizationResponse
import org.multipaz.provisioning.KeyBindingInfo
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosOpenID4VciProvisioningClientTest {

    private val issuerUrl = "https://issuer.test"
    private val walletProviderUrl = "https://wallet-provider.test"
    private val parEndpoint = "$issuerUrl/par"
    private val tokenEndpoint = "$issuerUrl/token"
    private val credentialEndpoint = "$issuerUrl/credential"

    private val issuerMetadata = """
        {"credential_issuer":"$issuerUrl",
         "credential_endpoint":"$credentialEndpoint",
         "nonce_endpoint":"$issuerUrl/nonce",
         "credential_configurations_supported":{
           "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","scope":"pid_scope",
             "display":[{"name":"PID","locale":"en"}]},
           "loyalty_mdoc":{"format":"mso_mdoc","doctype":"org.example.loyalty","scope":"loyalty_scope",
             "display":[{"name":"Loyalty","locale":"en"}]},
           "unscoped_mdoc":{"format":"mso_mdoc","doctype":"org.example.unscoped",
             "display":[{"name":"Unscoped","locale":"en"}]},
           "pid_mdoc_deferred":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","scope":"pid_scope",
             "display":[{"name":"PID (later)","locale":"en"}]}}}
    """.trimIndent()

    private val asMetadata = """
        {"issuer":"$issuerUrl",
         "authorization_endpoint":"$issuerUrl/authorize",
         "token_endpoint":"$tokenEndpoint",
         "pushed_authorization_request_endpoint":"$parEndpoint",
         "response_types_supported":["code"],
         "code_challenge_methods_supported":["S256"],
         "token_endpoint_auth_methods_supported":["none"]}
    """.trimIndent()

    private val attestationResponse = """{"walletInstanceAttestation":"wia-jwt"}"""

    private var parBody: String = ""
    private var parHeaders: Map<String, String> = emptyMap()
    private var tokenBody: String = ""
    private var credentialBodies = mutableListOf<String>()

    private fun engine() = MockEngine { request ->
        val json = headersOf("Content-Type", "application/json")
        when (request.url.toString().substringBefore('?')) {
            "$issuerUrl/.well-known/openid-credential-issuer" -> respond(issuerMetadata, headers = json)
            "$issuerUrl/.well-known/oauth-authorization-server" -> respond(asMetadata, headers = json)

            parEndpoint -> {
                parBody = request.body.toByteArray().decodeToString()
                parHeaders = request.headers.entries().associate { it.key to it.value.first() }
                respond("""{"request_uri":"urn:req:1"}""", HttpStatusCode.Created, json)
            }

            tokenEndpoint -> {
                tokenBody = request.body.toByteArray().decodeToString()
                respond(
                    """{"access_token":"at-1","refresh_token":"rt-1","c_nonce":"nonce-1"}""",
                    HttpStatusCode.OK,
                    json,
                )
            }

            credentialEndpoint -> {
                credentialBodies += request.body.toByteArray().decodeToString()
                respond("""{"credentials":[{"credential":"${"cred".encodeToByteArray().toBase64Url()}"}]}""", HttpStatusCode.OK, json)
            }

            "$walletProviderUrl/wallet-instance-attestation/jwk" ->
                respond(attestationResponse, HttpStatusCode.OK, json)

            else -> respond("{}", HttpStatusCode.OK, json)
        }
    }

    private val clientPreferences = OpenID4VCIClientPreferences(
        clientId = "eudiw-abca",
        redirectUrl = "eu.europa.ec.euidi://authorization",
        locales = listOf("en"),
        signingAlgorithms = listOf(Algorithm.ESP256),
    )

    private val httpClient by lazy { HttpClient(engine()) }

    private suspend fun session(
        configurationIds: List<String>,
    ) = IosVciAuthorizationSession(
        issuerUrl = issuerUrl,
        configurationIds = configurationIds,
        clientPreferences = clientPreferences,
        httpClient = httpClient,
        secureArea = SoftwareSecureArea.create(EphemeralStorage()),
        backend = StubOpenID4VciBackend(clientPreferences.clientId),
        walletProviderBaseUrl = walletProviderUrl,
        clientId = clientPreferences.clientId,
    )

    /** What the provisioning model hands back after the browser round trip. */
    private fun redirectResponse(challenge: AuthorizationChallenge.OAuth) = AuthorizationResponse.OAuth(
        id = challenge.id,
        parameterizedRedirectUrl = "eu.europa.ec.euidi://authorization?code=auth-code-1&state=s",
    )

    /** A proof JWT is only read for its `kid` here, which is what names the credential to certify. */
    private fun proofJwt(kid: String): String {
        val header = """{"typ":"openid4vci-proof+jwt","kid":"$kid"}""".encodeToByteArray().toBase64Url()
        val payload = """{"nonce":"nonce-1"}""".encodeToByteArray().toBase64Url()
        return "$header.$payload.signature"
    }

    @Test
    fun one_authorization_request_names_every_credential_asked_for() = runTest {
        val session = session(listOf("pid_mdoc", "loyalty_mdoc"))

        val challenge = session.challenge()

        assertIs<AuthorizationChallenge.OAuth>(challenge)
        val sent = parBody.parseUrlEncodedParameters()
        // The whole point: both scopes, one request, one confirmation.
        assertEquals("pid_scope loyalty_scope", sent["scope"])
        assertEquals("S256", sent["code_challenge_method"])
        assertNotNull(sent["code_challenge"])
        assertEquals(clientPreferences.redirectUrl, sent["redirect_uri"])
    }

    @Test
    fun the_authorization_request_carries_a_wallet_attestation() = runTest {
        // ⛔ DPoP alone is not enough. Measured against the dev issuer 2026-09-18: without these two
        // headers the authorization server answers
        // `401 {"error":"invalid_request","error_description":"Authentication failed."}`, and the whole
        // issuance fails before the browser ever opens. This is that defect, pinned.
        val session = session(listOf("pid_mdoc", "loyalty_mdoc"))

        session.challenge()

        assertEquals("wia-jwt", parHeaders["OAuth-Client-Attestation"])
        assertNotNull(parHeaders["OAuth-Client-Attestation-PoP"])
        assertNotNull(parHeaders["DPoP"])
    }

    @Test
    fun a_configuration_without_a_scope_falls_back_to_authorization_details() = runTest {
        // An issuer need not publish a scope. Dropping such a configuration from the request would
        // authorize less than was asked for, so the request switches form instead.
        val session = session(listOf("pid_mdoc", "unscoped_mdoc"))

        session.challenge()

        val sent = parBody.parseUrlEncodedParameters()
        assertNull(sent["scope"])
        val details = Json.parseToJsonElement(sent["authorization_details"]!!)
        assertEquals(
            listOf("pid_mdoc", "unscoped_mdoc"),
            details.jsonArrayOfConfigurationIds(),
        )
    }

    @Test
    fun configurations_sharing_one_scope_are_named_explicitly_instead() = runTest {
        // This is the real "PID Combined" shape: the EU dev issuer publishes each `_deferred` twin under
        // its plain twin's scope, so scopes do not name the four configurations one for one. Asking by
        // scope would authorize two credentials and let the issuer pick which twin each one meant.
        val session = session(listOf("pid_mdoc", "pid_mdoc_deferred"))

        session.challenge()

        val sent = parBody.parseUrlEncodedParameters()
        assertNull(sent["scope"])
        assertEquals(
            listOf("pid_mdoc", "pid_mdoc_deferred"),
            Json.parseToJsonElement(sent["authorization_details"]!!).jsonArrayOfConfigurationIds(),
        )
    }

    @Test
    fun the_second_credential_raises_no_challenge_so_the_browser_never_reopens() = runTest {
        val session = session(listOf("pid_mdoc", "loyalty_mdoc"))
        val first = IosOpenID4VciProvisioningClient(session, "pid_mdoc", httpClient)
        val second = IosOpenID4VciProvisioningClient(session, "loyalty_mdoc", httpClient)

        val challenges = first.getAuthorizationChallenges()
        assertEquals(1, challenges.size)
        first.authorize(redirectResponse(challenges.single() as AuthorizationChallenge.OAuth))

        // This is the saving: multipaz's launch loop only opens a browser while challenges remain, and
        // the second credential presents none.
        assertTrue(second.getAuthorizationChallenges().isEmpty())
    }

    @Test
    fun the_authorization_code_is_traded_for_a_token_with_the_pkce_verifier() = runTest {
        val session = session(listOf("pid_mdoc"))
        val challenge = session.challenge() as AuthorizationChallenge.OAuth

        session.authorize(redirectResponse(challenge))

        val sent = tokenBody.parseUrlEncodedParameters()
        assertEquals("authorization_code", sent["grant_type"])
        assertEquals("auth-code-1", sent["code"])
        assertNotNull(sent["code_verifier"])
        assertTrue(session.isAuthorized)
    }

    @Test
    fun each_credential_is_requested_for_its_own_configuration() = runTest {
        val session = session(listOf("pid_mdoc", "loyalty_mdoc"))
        session.authorize(redirectResponse(session.challenge() as AuthorizationChallenge.OAuth))

        val first = IosOpenID4VciProvisioningClient(session, "pid_mdoc", httpClient)
        val second = IosOpenID4VciProvisioningClient(session, "loyalty_mdoc", httpClient)
        first.obtainCredentials(KeyBindingInfo.OpenidProofOfPossession(listOf(proofJwt("cred-a"))))
        second.obtainCredentials(KeyBindingInfo.OpenidProofOfPossession(listOf(proofJwt("cred-b"))))

        assertEquals(2, credentialBodies.size)
        val requested = credentialBodies.map {
            Json.parseToJsonElement(it).jsonObject["credential_configuration_id"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("pid_mdoc", "loyalty_mdoc"), requested)
        // The format fields travel with it; an issuer that gets neither answers 400.
        val firstRequest = Json.parseToJsonElement(credentialBodies.first()).jsonObject
        assertEquals("mso_mdoc", firstRequest["format"]!!.jsonPrimitive.content)
        assertEquals("eu.europa.ec.eudi.pid.1", firstRequest["doctype"]!!.jsonPrimitive.content)
    }

    @Test
    fun an_issued_credential_is_paired_with_the_pending_credential_its_proof_names() = runTest {
        val session = session(listOf("pid_mdoc"))
        session.authorize(redirectResponse(session.challenge() as AuthorizationChallenge.OAuth))

        val credentials = IosOpenID4VciProvisioningClient(session, "pid_mdoc", httpClient)
            .obtainCredentials(KeyBindingInfo.OpenidProofOfPossession(listOf(proofJwt("cred-a"))))

        // Certifying the right bytes onto the wrong key is silent and permanent, so the pairing is
        // pinned rather than assumed: the id comes from the proof's own `kid`.
        assertEquals(listOf("cred-a"), credentials.certifications.map { it.credentialId })
    }

    @Test
    fun a_client_reports_only_its_own_configuration_in_the_metadata() = runTest {
        // ⛔ ProvisioningModel builds the document from `credentials.values.first()`. Reporting the whole
        // catalogue makes every document out of whichever entry comes first — measured against the dev
        // issuer, an SD-JWT credential was certified onto an mdoc credential and threw
        // "-39517 bytes leftover after decoding".
        val session = session(listOf("pid_mdoc", "loyalty_mdoc"))

        val metadata = IosOpenID4VciProvisioningClient(session, "loyalty_mdoc", httpClient).getMetadata()

        assertEquals(setOf("loyalty_mdoc"), metadata.credentials.keys)
    }

    @Test
    fun the_authorization_data_carries_what_a_later_refresh_needs() = runTest {
        // ⚠️ This is multipaz's own `OpenID4VCIAuthorizationData` shape, which is internal to it. If a
        // multipaz upgrade changes the schema, this is the test that should fail rather than the
        // deferred collection quietly stopping.
        val session = session(listOf("pid_mdoc"))
        session.authorize(redirectResponse(session.challenge() as AuthorizationChallenge.OAuth))

        val data = assertNotNull(session.authorizationData("pid_mdoc"))
        val map = Cbor.decode(data.toByteArray()) as CborMap

        assertEquals("openid4vci", (map.items[Tstr("type")] as Tstr).value)
        assertEquals(issuerUrl, (map.items[Tstr("issuerUri")] as Tstr).value)
        assertEquals("pid_mdoc", (map.items[Tstr("configurationId")] as Tstr).value)
        assertEquals("rt-1", (map.items[Tstr("refreshToken")] as Tstr).value)
        // Without the alias the DPoP key cannot be reopened, and a refresh is unauthenticated.
        assertNotNull(map.items[Tstr("dpopKeyAlias")])
    }

    @Test
    fun there_is_no_authorization_data_before_a_token_exists() = runTest {
        assertNull(session(listOf("pid_mdoc")).authorizationData("pid_mdoc"))
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonArrayOfConfigurationIds(): List<String> =
    kotlinx.serialization.json.Json.parseToJsonElement(toString())
        .let { element ->
            (element as kotlinx.serialization.json.JsonArray).map {
                it.jsonObject["credential_configuration_id"]!!.jsonPrimitive.content
            }
        }

/** The wallet back-end is not what these cases are about; only the key attestation path would use it. */
private class StubOpenID4VciBackend(
    private val clientId: String,
) : org.multipaz.provisioning.openid4vci.OpenID4VCIBackend {
    override suspend fun getClientId(): String = clientId

    override suspend fun createJwtClientAssertion(authorizationServerIdentifier: String): String =
        throw UnsupportedOperationException("the test server asks for no client authentication")

    override suspend fun createJwtWalletAttestation(
        keyAttestation: org.multipaz.securearea.KeyAttestation,
    ): String = throw UnsupportedOperationException("the test server asks for no client authentication")

    override suspend fun createJwtKeyAttestation(
        credentialKeyAttestations: List<org.multipaz.provisioning.CredentialKeyAttestation>,
        challenge: String,
        userAuthentication: List<String>?,
        keyStorage: List<String>?,
    ): String = "key-attestation-jwt"
}
