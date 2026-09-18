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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.provisioning.AuthorizationChallenge
import org.multipaz.provisioning.AuthorizationResponse
import org.multipaz.provisioning.CredentialCertification
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.Credentials
import org.multipaz.provisioning.KeyBindingInfo
import org.multipaz.provisioning.ProvisioningClient
import org.multipaz.provisioning.ProvisioningMetadata
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.webtoken.buildJwt
import kotlin.random.Random

/**
 * One OpenID4VCI authorization, shared by every credential a single user action asks for.
 *
 * ### Why this exists
 *
 * multipaz authorizes **per credential configuration**: `OpenID4VCIProvisioningClient` is built around a
 * single `configurationId`, so asking for four credentials runs four authorizations and costs the user
 * four browser confirmations. Android's wallet-core sends one pushed authorization request naming
 * several scopes and pays for one. The difference is not a setting — the whole multipaz path is singular,
 * from `CredentialOffer.configurationId` through the PAR to `ProvisioningModel.launch`'s single
 * `Deferred<Document>`. Reported as multipaz#2026.
 *
 * So this session performs **one** pushed authorization request naming **every** requested
 * configuration, hands the resulting token to each per-configuration [IosOpenID4VciProvisioningClient],
 * and those clients report no authorization challenge at all — which is how multipaz's own
 * `ProvisioningModel.launch` loop skips the browser for the second and later credentials.
 *
 * ### What it deliberately does not do
 *
 * Everything that is not the authorization stays multipaz's: documents, credential keys, batching,
 * certification and history are all still driven by `ProvisioningModel` and the provisioning handler.
 * In particular the proof-of-possession JWTs arrive already built and signed in [KeyBindingInfo] — this
 * never touches credential key material.
 *
 * ⛔ **Requests go through [openID4VciHttpClient], never a bare client.** That shim is what absorbs this
 * ecosystem's issuer quirks — the `authorization_details`-to-`scope` rewrite, the DPoP nonce dance, the
 * `202 Accepted` deferred answer — and a client of its own would quietly lose all of it.
 */
internal class IosVciAuthorizationSession(
    val issuerUrl: String,
    /** Every configuration this user action asked for; all of them go into the one authorization. */
    private val configurationIds: List<String>,
    private val clientPreferences: OpenID4VCIClientPreferences,
    private val httpClient: HttpClient,
    private val secureArea: SecureArea,
    private val backend: OpenID4VCIBackend,
    /** Opens the issuer's authorization URL; the app delegate delivers the redirect. */
    private val openAuthorizationUrl: suspend (String) -> Unit,
    /** Waits for that redirect, or null when the user never came back. */
    private val awaitRedirect: suspend () -> String?,
) {
    private val lock = Mutex()

    private var endpoints: VciEndpoints? = null
    private var scopesByConfigurationId: Map<String, String> = emptyMap()
    private var cachedMetadata: ProvisioningMetadata? = null

    private var dpopKeyAlias: String? = null
    private var dpopKey: AsymmetricKey? = null
    private var dpopNonce: String? = null

    private var pkceVerifier: String? = null
    private var redirectState: String? = null

    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var keyChallenge: String? = null

    /** True once the token is in hand, which is what makes later configurations free. */
    val isAuthorized: Boolean get() = accessToken != null

    suspend fun metadata(): ProvisioningMetadata = lock.withLock {
        cachedMetadata ?: OpenID4VCI.getMetadata(issuerUrl, httpClient, clientPreferences)
            .also { cachedMetadata = it }
    }

    /**
     * The one authorization challenge, or none once it has been answered.
     *
     * The first configuration to ask performs the pushed authorization request; every later one gets an
     * empty list back from its client, and `ProvisioningModel.launch`'s `while (isNotEmpty())` loop then
     * never opens the browser.
     */
    suspend fun challenge(): AuthorizationChallenge? = lock.withLock {
        if (accessToken != null) return null

        val endpoints = resolveEndpoints()
        val verifier = Random.nextBytes(PKCE_BYTES).toBase64Url().also { pkceVerifier = it }
        val state = Random.nextBytes(STATE_BYTES).toBase64Url().also { redirectState = it }
        val challengeValue = Crypto.digest(Algorithm.SHA256, verifier.encodeToByteArray()).toBase64Url()

        val requestUri = pushAuthorizationRequest(endpoints, challengeValue, state)

        AuthorizationChallenge.OAuth(
            id = OAUTH_CHALLENGE_ID,
            url = buildString {
                append(endpoints.authorizationEndpoint)
                append(if ('?' in endpoints.authorizationEndpoint) "&" else "?")
                append("client_id=").append(clientPreferences.clientId.formUrlEncode())
                append("&request_uri=").append(requestUri.formUrlEncode())
            },
            state = state,
        )
    }

    /** Trades the authorization code the redirect carries for an access token. */
    suspend fun authorize(response: AuthorizationResponse): Unit = lock.withLock {
        if (accessToken != null) return@withLock
        val oauth = response as? AuthorizationResponse.OAuth
            ?: throw IllegalStateException("Unexpected authorization response: ${response::class.simpleName}")
        val code = oauth.parameterizedRedirectUrl.queryParameter("code")
            ?: throw IllegalStateException("The issuer's redirect carried no authorization code.")

        val endpoints = resolveEndpoints()
        val form = listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to clientPreferences.redirectUrl,
            "client_id" to clientPreferences.clientId,
            "code_verifier" to (pkceVerifier ?: throw IllegalStateException("No PKCE verifier.")),
        )
        val body = postForm(endpoints.tokenEndpoint, form).also {
            if (it.status != HttpStatusCode.OK) {
                throw IllegalStateException("Token request failed: ${it.status} ${it.bodyAsText()}")
            }
        }.bodyAsText().asJsonObject()

        accessToken = body["access_token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("The issuer returned no access token.")
        refreshToken = body["refresh_token"]?.jsonPrimitive?.content
        keyChallenge = body["c_nonce"]?.jsonPrimitive?.content
    }

    /** The `c_nonce` the credential proofs must carry, from the token response or the nonce endpoint. */
    suspend fun keyBindingChallenge(): String = lock.withLock {
        keyChallenge?.let { return it }
        val endpoint = resolveEndpoints().nonceEndpoint
            ?: throw IllegalStateException("The issuer published no nonce endpoint and sent no c_nonce.")
        val response = httpClient.post(endpoint) { contentType(ContentType.Application.Json) }
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Nonce request failed: ${response.status}")
        }
        response.bodyAsText().asJsonObject()["c_nonce"]?.jsonPrimitive?.content
            ?.also { keyChallenge = it }
            ?: throw IllegalStateException("The issuer's nonce endpoint returned no c_nonce.")
    }

    /** Asks the credential endpoint for one configuration, with proofs multipaz has already signed. */
    suspend fun obtainCredentials(configurationId: String, keyInfo: KeyBindingInfo): Credentials {
        val metadata = metadata()
        val credentialMetadata = metadata.credentials[configurationId]
            ?: throw IllegalStateException("The issuer does not offer $configurationId.")
        val endpoints = lock.withLock { resolveEndpoints() }
        val token = accessToken ?: throw IllegalStateException("Not authorized.")

        val proofs = keyProofs(keyInfo)
        val request = buildJsonObject {
            put("credential_configuration_id", configurationId)
            proofs?.let { put("proofs", it) }
            when (val format = credentialMetadata.format) {
                is CredentialFormat.Mdoc -> {
                    put("format", "mso_mdoc")
                    put("doctype", format.docType)
                }

                is CredentialFormat.SdJwt -> {
                    put("format", "dc+sd-jwt")
                    put("vct", format.vct)
                }
            }
        }

        val response = postJson(endpoints.credentialEndpoint, request.toString(), token)
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException(
                "Credential request failed: ${response.status} ${response.bodyAsText()}"
            )
        }

        val body = response.bodyAsText().asJsonObject()
        val issued = body["credentials"]?.jsonArray.orEmpty().map { entry ->
            val text = entry.jsonObject["credential"]!!.jsonPrimitive.content
            when (credentialMetadata.format) {
                is CredentialFormat.Mdoc -> ByteString(text.fromBase64Url())
                is CredentialFormat.SdJwt -> ByteString(text.encodeToByteArray())
            }
        }

        // The pending credential each issued credential belongs to is named by its own proof's `kid`,
        // which is the pairing multipaz's handler certifies against.
        val credentialIds = keyInfo.credentialIds()
        return Credentials(
            certifications = issued.zip(credentialIds) { data, id -> CredentialCertification(id, data) },
            display = null,
        )
    }

    /**
     * The session as multipaz persists it, so a refresh or a deferred collection can resume later.
     *
     * ⚠️ **This reproduces `OpenID4VCIAuthorizationData`, which is `internal` to multipaz.** The shape is
     * a CBOR map keyed by field name — the same one [IosDeferredDocumentCompleter] already reads to find
     * `dpopKeyAlias` and `refreshToken`. It is written to disk by multipaz and carries a schema hash, so
     * a multipaz upgrade can change it under us; the deferred tests are what would notice.
     */
    fun authorizationData(configurationId: String): ByteString? {
        val refresh = refreshToken ?: return null
        val map = CborMap.builder()
            .put("type", "openid4vci")
            .put("issuerUri", issuerUrl)
            .put("configurationId", configurationId)
            .put("secureAreaId", secureArea.identifier)
            .put("refreshToken", refresh)
        dpopKeyAlias?.let { map.put("dpopKeyAlias", it) }
        endpoints?.authorizationServer?.let { map.put("authorizationServer", it) }
        return ByteString(Cbor.encode(map.end().build()))
    }

    // ---- the wire ------------------------------------------------------------------------------

    private suspend fun pushAuthorizationRequest(
        endpoints: VciEndpoints,
        codeChallenge: String,
        state: String,
    ): String {
        // The whole point: every requested configuration in ONE request. Scopes when the issuer
        // published them and they are unambiguous, `authorization_details` otherwise.
        val scopes = configurationIds.mapNotNull { scopesByConfigurationId[it] }.distinct()
        val useScopes = scopes.size == configurationIds.map { scopesByConfigurationId[it] }.distinct().size &&
            scopes.isNotEmpty() &&
            configurationIds.all { scopesByConfigurationId[it] != null }

        val form = buildList {
            if (useScopes) {
                add("scope" to scopes.joinToString(" "))
            } else {
                add(
                    "authorization_details" to buildJsonArray {
                        for (configurationId in configurationIds) {
                            add(
                                buildJsonObject {
                                    put("type", "openid_credential")
                                    put("credential_configuration_id", configurationId)
                                }
                            )
                        }
                    }.toString()
                )
            }
            add("response_type" to "code")
            add("code_challenge_method" to "S256")
            add("code_challenge" to codeChallenge)
            add("redirect_uri" to clientPreferences.redirectUrl)
            add("client_id" to clientPreferences.clientId)
            add("state" to state)
        }

        Logger.i(
            TAG,
            "authorizing ${configurationIds.size} configurations in one request " +
                if (useScopes) "by scope" else "by authorization_details"
        )

        val response = postForm(endpoints.parEndpoint, form)
        if (response.status != HttpStatusCode.Created && response.status != HttpStatusCode.OK) {
            throw IllegalStateException(
                "Authorization request failed: ${response.status} ${response.bodyAsText()}"
            )
        }
        return response.bodyAsText().asJsonObject()["request_uri"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("The authorization server returned no request_uri.")
    }

    /** Awaits the browser hand-back for the challenge this session raised. */
    suspend fun completeInBrowser(challenge: AuthorizationChallenge.OAuth): AuthorizationResponse {
        openAuthorizationUrl(challenge.url)
        val redirect = awaitRedirect()
            ?: throw IllegalStateException("Authorization was not completed.")
        return AuthorizationResponse.OAuth(id = challenge.id, parameterizedRedirectUrl = redirect)
    }

    private suspend fun postForm(url: String, form: List<Pair<String, String>>): HttpResponse {
        suspend fun attempt(nonce: String?) = httpClient.post(url) {
            header(DPOP_HEADER, dpopKey().dpopProof(url, nonce = nonce))
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(form.formUrlEncoded())
        }

        val first = attempt(dpopNonce)
        first.headers[DPOP_NONCE_HEADER]?.let { dpopNonce = it }
        // This ecosystem's authorization servers refuse the first DPoP proof and hand back a nonce.
        return if (first.status.value >= HTTP_BAD_REQUEST && dpopNonce != null) {
            attempt(dpopNonce).also { retry ->
                retry.headers[DPOP_NONCE_HEADER]?.let { dpopNonce = it }
            }
        } else {
            first
        }
    }

    private suspend fun postJson(url: String, body: String, token: String): HttpResponse {
        val ath = Crypto.digest(Algorithm.SHA256, token.encodeToByteArray()).toBase64Url()
        suspend fun attempt(nonce: String?) = httpClient.post(url) {
            header("Authorization", "$DPOP_SCHEME $token")
            header(DPOP_HEADER, dpopKey().dpopProof(url, ath = ath, nonce = nonce))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val first = attempt(dpopNonce)
        first.headers[DPOP_NONCE_HEADER]?.let { dpopNonce = it }
        return if (first.status.value >= HTTP_BAD_REQUEST && dpopNonce != null) {
            attempt(dpopNonce).also { retry ->
                retry.headers[DPOP_NONCE_HEADER]?.let { dpopNonce = it }
            }
        } else {
            first
        }
    }

    private suspend fun dpopKey(): AsymmetricKey = dpopKey ?: run {
        val alias = "$DPOP_KEY_PREFIX${Random.nextBytes(ALIAS_BYTES).toBase64Url()}"
        secureArea.createKey(alias, CreateKeySettings())
        AsymmetricKey.anonymous(secureArea, alias).also {
            dpopKey = it
            dpopKeyAlias = alias
        }
    }

    private suspend fun AsymmetricKey.dpopProof(
        url: String,
        ath: String? = null,
        nonce: String? = null,
    ): String = buildJwt(
        type = DPOP_JWT_TYPE,
        key = this,
        header = { put("jwk", publicKey.toJwk()) },
    ) {
        put("htm", "POST")
        put("htu", url)
        put("jti", Random.nextBytes(JTI_BYTES).toBase64Url())
        nonce?.let { put("nonce", it) }
        ath?.let { put("ath", it) }
    }

    private suspend fun keyProofs(keyInfo: KeyBindingInfo): JsonElement? = when (keyInfo) {
        KeyBindingInfo.Keyless -> null

        is KeyBindingInfo.OpenidProofOfPossession -> buildJsonObject {
            putJsonArray("jwt") { keyInfo.jwtList.forEach { add(it) } }
        }

        is KeyBindingInfo.Attestation -> {
            // Minted before the builder: the JSON builder is not a coroutine body.
            val attestation = backend.createJwtKeyAttestation(
                credentialKeyAttestations = keyInfo.attestations,
                challenge = keyBindingChallenge(),
            )
            buildJsonObject { putJsonArray("attestation") { add(attestation) } }
        }
    }

    /** Reads the endpoints and the scope of every configuration straight from the issuer's metadata. */
    private suspend fun resolveEndpoints(): VciEndpoints = endpoints ?: run {
        val issuerMetadata = httpClient.get("${issuerUrl.trimEnd('/')}/$ISSUER_METADATA_PATH")
            .bodyAsText().asJsonObject()

        scopesByConfigurationId = issuerMetadata["credential_configurations_supported"]
            ?.jsonObject.orEmpty()
            .mapNotNull { (id, config) ->
                config.jsonObject["scope"]?.jsonPrimitive?.content?.let { id to it }
            }
            .toMap()

        val authorizationServer = issuerMetadata["authorization_servers"]?.jsonArray
            ?.firstOrNull()?.jsonPrimitive?.content ?: issuerUrl
        val asMetadata = authorizationServerMetadata(authorizationServer)

        VciEndpoints(
            authorizationServer = authorizationServer,
            parEndpoint = asMetadata.required("pushed_authorization_request_endpoint"),
            authorizationEndpoint = asMetadata.required("authorization_endpoint"),
            tokenEndpoint = asMetadata.required("token_endpoint"),
            credentialEndpoint = issuerMetadata.required("credential_endpoint"),
            nonceEndpoint = issuerMetadata["nonce_endpoint"]?.jsonPrimitive?.content,
        ).also { endpoints = it }
    }

    /**
     * 🪤 Issuers in this ecosystem publish authorization-server metadata under **either** well-known
     * name, and which one is not predictable from the issuer URL — so both are tried before giving up.
     */
    private suspend fun authorizationServerMetadata(authorizationServer: String): JsonObject {
        val base = authorizationServer.trimEnd('/')
        for (path in listOf(AS_METADATA_PATH, OPENID_CONFIGURATION_PATH)) {
            val response = runCatching { httpClient.get("$base/$path") }.getOrNull() ?: continue
            if (response.status == HttpStatusCode.OK) {
                runCatching { response.bodyAsText().asJsonObject() }.getOrNull()?.let { return it }
            }
        }
        throw IllegalStateException("No authorization server metadata at $base.")
    }

    private companion object {
        const val TAG = "IosVciAuthorization"
        const val ISSUER_METADATA_PATH = ".well-known/openid-credential-issuer"
        const val AS_METADATA_PATH = ".well-known/oauth-authorization-server"
        const val OPENID_CONFIGURATION_PATH = ".well-known/openid-configuration"
        const val OAUTH_CHALLENGE_ID = "oauth"
        const val DPOP_HEADER = "DPoP"
        const val DPOP_NONCE_HEADER = "DPoP-Nonce"
        const val DPOP_SCHEME = "DPoP"
        const val DPOP_JWT_TYPE = "dpop+jwt"
        const val DPOP_KEY_PREFIX = "vci-dpop-"
        const val HTTP_BAD_REQUEST = 400
        const val PKCE_BYTES = 32
        const val STATE_BYTES = 15
        const val JTI_BYTES = 15
        const val ALIAS_BYTES = 9
    }
}

/**
 * One credential's worth of [ProvisioningClient], sharing its authorization with the others.
 *
 * multipaz drives this exactly as it drives its own client — `ProvisioningModel.launch` calls the six
 * methods in order — but only the first instance ever reports a challenge, so the user confirms once for
 * the whole batch. See [IosVciAuthorizationSession] for why that cannot be done with multipaz's client.
 */
internal class IosOpenID4VciProvisioningClient(
    private val session: IosVciAuthorizationSession,
    private val configurationId: String,
) : ProvisioningClient {

    override suspend fun getMetadata(): ProvisioningMetadata = session.metadata()

    override suspend fun getAuthorizationChallenges(): List<AuthorizationChallenge> =
        listOfNotNull(session.challenge())

    override suspend fun authorize(response: AuthorizationResponse) = session.authorize(response)

    override suspend fun getAuthorizationData(): ByteString? = session.authorizationData(configurationId)

    override suspend fun getKeyBindingChallenge(): String = session.keyBindingChallenge()

    override suspend fun obtainCredentials(keyInfo: KeyBindingInfo): Credentials =
        session.obtainCredentials(configurationId, keyInfo)
}

/** The endpoints one issuance needs, read once per session. */
private data class VciEndpoints(
    val authorizationServer: String,
    val parEndpoint: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val credentialEndpoint: String,
    val nonceEndpoint: String?,
)

/**
 * The pending credential each proof belongs to, named by the proof's own `kid`.
 *
 * Mirrors what multipaz's client does with the same [KeyBindingInfo]: the handler certifies by
 * credential id, so a wrong pairing would certify the right bytes onto the wrong key.
 */
private fun KeyBindingInfo.credentialIds(): List<String> = when (this) {
    KeyBindingInfo.Keyless -> listOf("")
    is KeyBindingInfo.OpenidProofOfPossession -> jwtList.map { jwt ->
        val header = Json.parseToJsonElement(
            jwt.substringBefore('.').fromBase64Url().decodeToString()
        ).jsonObject
        header["kid"]?.jsonPrimitive?.content
            ?: header["jwk"]?.jsonObject?.get("kid")?.jsonPrimitive?.content
            ?: ""
    }

    is KeyBindingInfo.Attestation -> attestations.map { it.credentialId }
}

private fun JsonObject.required(name: String): String =
    this[name]?.jsonPrimitive?.content
        ?: throw IllegalStateException("The issuer's metadata has no $name.")

private fun String.asJsonObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

/** The value of one query parameter of a redirect URL, without parsing the whole URL. */
private fun String.queryParameter(name: String): String? = substringAfter('?', "")
    .split('&')
    .firstOrNull { it.startsWith("$name=") }
    ?.substringAfter('=')
    ?.replace('+', ' ')
    ?.percentDecoded()

private fun String.percentDecoded(): String {
    if ('%' !in this) return this
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 2 < length) {
            bytes += substring(index + 1, index + 3).toInt(16).toByte()
            index += 3
        } else {
            bytes += char.code.toByte()
            index++
        }
    }
    return bytes.toByteArray().decodeToString()
}

private fun List<Pair<String, String>>.formUrlEncoded(): String =
    joinToString("&") { (name, value) -> "${name.formUrlEncode()}=${value.formUrlEncode()}" }

private fun String.formUrlEncode(): String = buildString {
    this@formUrlEncode.encodeToByteArray().forEach { byte ->
        val char = byte.toInt().toChar()
        if (char.isLetterOrDigit() || char in "-_.~") {
            append(char)
        } else {
            append('%').append(byte.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }
}
