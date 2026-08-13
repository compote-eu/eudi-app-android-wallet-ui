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

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.provisioning.CredentialKeyAttestation
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.securearea.KeyAttestation

/**
 * iOS's [OpenID4VCIBackend]: the wallet-provider calls that let an issuer trust this wallet.
 *
 * OpenID4VCI's trust model does not have the issuer trusting the *app* — platform attestations are not
 * standardised — it has the issuer trusting a **wallet back-end**, which vouches for the app in its own
 * way. This is the client for that back-end, and it is deliberately a direct port of Android's
 * `WalletCoreAttestationProviderImpl`/`WalletAttestationRepositoryImpl`: same host, same two endpoints,
 * same request and response shapes.
 *
 * **Why that matters more than it looks:** the wallet provider is handed nothing but the *public* JWKs.
 * It receives no Play Integrity token on Android and would receive no App Attest assertion here, so the
 * same service can vouch for Secure Enclave keys exactly as it does for Android Keystore ones. That is
 * what makes iOS issuance possible without App Attest — a production wallet provider would likely
 * demand platform evidence, but that is its policy to change, on both platforms at once.
 *
 * Which methods the issuer actually exercises is decided by the authorization server's
 * `token_endpoint_auth_methods_supported`: the EU dev AS advertises `attest_jwt_client_auth`, so multipaz
 * selects client *attestation* and calls [createJwtWalletAttestation]; [createJwtClientAssertion] stays
 * unreachable there (see its note).
 *
 * @param walletProviderBaseUrl e.g. `https://dev.wallet-provider.eudiw.dev` — Android's
 * `WalletCoreConfig.walletProviderHost`.
 * @param clientId the OAuth `client_id` the wallet provider issues attestations for; Android's dev
 * flavour uses `eudiw-abca`.
 */
internal class IosOpenID4VciBackend(
    private val walletProviderBaseUrl: String,
    private val clientId: String,
    private val httpClient: HttpClient,
) : OpenID4VCIBackend {

    override suspend fun getClientId(): String = clientId

    /**
     * The wallet instance attestation, binding [keyAttestation]'s key to this wallet instance.
     *
     * multipaz asks for this once per provisioning session, for the key it will use to authenticate to
     * the authorization server.
     */
    override suspend fun createJwtWalletAttestation(keyAttestation: KeyAttestation): String =
        request(
            path = WALLET_INSTANCE_ATTESTATION_PATH,
            body = buildJsonObject { put("jwk", keyAttestation.publicKey.toJwk(null)) },
            responseField = "walletInstanceAttestation",
        )

    /**
     * The key attestation covering the credential keys, answering [challenge].
     *
     * Every credential configuration on both EU dev issuers requires attestation-backed proofs, so this
     * is on the main path rather than an edge case. [userAuthentication] and [keyStorage] are the
     * assurances the issuer asked for; the wallet provider's API takes neither today — Android does not
     * send them either — so they are ignored rather than silently promised.
     */
    override suspend fun createJwtKeyAttestation(
        credentialKeyAttestations: List<CredentialKeyAttestation>,
        challenge: String,
        userAuthentication: List<String>?,
        keyStorage: List<String>?,
    ): String {
        // Resolved before building the JSON: `toJwk` suspends, and the array builder is not a
        // coroutine body.
        val jwks = credentialKeyAttestations.map { it.keyAttestation.publicKey.toJwk(null) }
        return request(
            path = KEY_ATTESTATION_PATH,
            body = buildJsonObject {
                // Empty rather than absent when there is no challenge, matching the Android repository.
                put("nonce", challenge)
                putJsonObject("jwkSet") {
                    putJsonArray("keys") { jwks.forEach { add(it) } }
                }
            },
            responseField = "keyAttestation",
        )
    }

    /**
     * Not supported, and not reachable against the EU issuers.
     *
     * multipaz calls this only when the authorization server offers `private_key_jwt` *without*
     * `attest_jwt_client_auth`. The EUDI wallet provider has no endpoint that mints RFC 7523 client
     * assertions, and inventing one locally would mean signing with a key no server trusts — a failure
     * that would surface as a confusing 401 at the token endpoint instead of here.
     */
    override suspend fun createJwtClientAssertion(authorizationServerIdentifier: String): String =
        throw UnsupportedOperationException(
            "This wallet authenticates with a wallet attestation, not a client assertion; " +
                    "'$authorizationServerIdentifier' offers no attestation-based client auth"
        )

    private suspend fun request(
        path: String,
        body: JsonObject,
        responseField: String,
    ): String {
        val response = httpClient.post(walletProviderBaseUrl + path) {
            contentType(ContentType.Application.Json)
            // Serialised here rather than through ContentNegotiation: the client multipaz is given
            // installs no converters, and this is the only place in the iOS wallet that posts JSON.
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "wallet provider $path failed: ${response.status} ${text.take(200)}"
            )
        }
        return Json.parseToJsonElement(text).jsonObject[responseField]?.jsonPrimitive?.content
            ?: throw IllegalStateException("wallet provider $path returned no '$responseField'")
    }

    private companion object {
        const val WALLET_INSTANCE_ATTESTATION_PATH = "/wallet-instance-attestation/jwk"
        const val KEY_ATTESTATION_PATH = "/key-attestation/jwk-set"
    }
}
