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
import kotlinx.serialization.json.contentOrNull
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
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.webtoken.buildJwt
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.reflect.KClass

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
    /** Where the wallet instance attestation comes from; the authorization server demands one. */
    private val walletProviderBaseUrl: String,
    private val clientId: String,
) {
    private val lock = Mutex()

    private var endpoints: VciEndpoints? = null
    private var scopesByConfigurationId: Map<String, String> = emptyMap()
    private var cachedMetadata: ProvisioningMetadata? = null

    private var dpopKeyAlias: String? = null
    private var dpopKey: AsymmetricKey? = null
    private var dpopNonce: String? = null
    private var attestationKey: AsymmetricKey? = null

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
    suspend fun obtainCredentials(
        configurationId: String,
        keyInfo: KeyBindingInfo,
        /**
         * The client to ask on — the *caller's*, not this session's.
         *
         * ⛔ This is not interchangeable with the session's own. [openID4VciHttpClient] carries a
         * [DeferredIssuanceNotice] per document, and that notice is how a `202 Accepted` becomes a parked
         * document rather than a failure. Sharing one client across configurations would let one
         * document's deferral be read as another's.
         */
        credentialHttpClient: HttpClient,
    ): Credentials {
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

        val response = postJson(credentialHttpClient, endpoints.credentialEndpoint, request.toString(), token)
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
     * Trades this session's refresh token for one bound to a **fresh** DPoP key.
     *
     * ### Why
     *
     * One authorization issues several documents, so without this they all name the same
     * `dpopKeyAlias`. multipaz's `DocumentStore.deleteDocument` deletes the key its authorization data
     * names, so deleting any one of them would take the key its siblings need to refresh or to collect a
     * deferred credential. multipaz is not wrong — it assumes one authorization per document, which is
     * the assumption we traded away to stop asking the user four times.
     *
     * Giving each document its own key restores that assumption, and then deletion is correct again.
     *
     * ✅ **Measured against the dev authorization server before this was written**: the same refresh
     * token presented with a brand-new DPoP key is accepted, and the access token that comes back
     * carries the **new** key's thumbprint in `cnf.jkt` — so the binding really moves. The original key
     * still worked afterwards, so the token is not consumed by the exchange.
     *
     * ⚠️ That is this deployment's behaviour, not a guarantee. RFC 9449 §5 requires a refresh token
     * issued to a *public* client to stay bound to its original key; ours authenticates with a wallet
     * attestation, which is why re-binding is accepted here. A stricter server would refuse — hence the
     * null return rather than an exception, and the caller leaving the document on the shared key.
     *
     * @return the new key's alias and the refresh token to store with it, or null if the server refused.
     */
    suspend fun rebindToFreshDpopKey(): RebindResult? {
        val refresh = refreshToken ?: return null
        val endpoints = lock.withLock { resolveEndpoints() }

        val alias = "$DPOP_KEY_PREFIX${Random.nextBytes(ALIAS_BYTES).toBase64Url()}"
        secureArea.createKey(alias, CreateKeySettings())
        val key = AsymmetricKey.anonymous(secureArea, alias)

        val form = listOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to clientId,
        )
        val attestation = attestationHeaders(endpoints)

        suspend fun attempt(nonce: String?) = httpClient.post(endpoints.tokenEndpoint) {
            attestation.forEach { (name, value) -> header(name, value) }
            header(DPOP_HEADER, key.dpopProof(endpoints.tokenEndpoint, nonce = nonce))
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(form.formUrlEncoded())
        }

        var response = attempt(dpopNonce)
        response.headers[DPOP_NONCE_HEADER]?.let { dpopNonce = it }
        if (response.status != HttpStatusCode.OK && dpopNonce != null) {
            response = attempt(dpopNonce)
        }
        if (response.status != HttpStatusCode.OK) {
            Logger.w(TAG, "the server would not re-bind the refresh token: ${response.status}")
            runCatching { secureArea.deleteKey(alias) }
            return null
        }

        val body = response.bodyAsText().asJsonObject()
        // A server that rotates hands back a new one; this one returns the same token, which stays
        // usable — either way what the document must store is whatever came back.
        val rotated = body["refresh_token"]?.jsonPrimitive?.contentOrNull ?: refresh
        return RebindResult(dpopKeyAlias = alias, refreshToken = rotated)
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
        // The whole point: every requested configuration in ONE request.
        //
        // ⛔ Scopes are usable only when they name the requested configurations **one for one**. The EU
        // dev issuer publishes each `_deferred` twin under its plain twin's scope, so "PID Combined" is
        // four configurations under two scopes — asking by scope would authorize two credentials and
        // leave the issuer to choose which twin each one meant. `authorization_details` names every
        // configuration explicitly, so that is what a request like this uses. (multipaz reaches the same
        // conclusion for the same reason, per configuration rather than per request.)
        val scopes = configurationIds.map { scopesByConfigurationId[it] }
        val useScopes = scopes.none { it == null } && scopes.distinct().size == configurationIds.size

        val form = buildList {
            if (useScopes) {
                add("scope" to scopes.filterNotNull().joinToString(" "))
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

    private suspend fun postForm(url: String, form: List<Pair<String, String>>): HttpResponse {
        // ⛔ DPoP alone is not enough: this ecosystem's authorization server answers
        // `401 {"error":"invalid_request","error_description":"Authentication failed."}` without a
        // wallet instance attestation. Measured against the dev issuer, 2026-09-18.
        val attestation = attestationHeaders(resolveEndpoints())
        suspend fun attempt(nonce: String?) = httpClient.post(url) {
            attestation.forEach { (name, value) -> header(name, value) }
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

    private suspend fun postJson(
        client: HttpClient,
        url: String,
        body: String,
        token: String,
    ): HttpResponse {
        val ath = Crypto.digest(Algorithm.SHA256, token.encodeToByteArray()).toBase64Url()
        suspend fun attempt(nonce: String?) = client.post(url) {
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


    /**
     * The client-attestation pair the authorization server requires, minted the way the deferred
     * collector already does it: an attestation from the wallet provider over a key of ours, and a proof
     * of possession of that key addressed to the authorization server.
     */
    private suspend fun attestationHeaders(endpoints: VciEndpoints): Map<String, String> {
        val key = attestationKey ?: createKey(ATTESTATION_KEY_PREFIX).also { attestationKey = it }

        val attestation = httpClient.post("$walletProviderBaseUrl$WALLET_INSTANCE_ATTESTATION_PATH") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("jwk", key.publicKey.toJwk()) }.toString())
        }.bodyAsText().asJsonObject()["walletInstanceAttestation"]!!.jsonPrimitive.content

        val challenge = endpoints.challengeEndpoint?.let { endpoint ->
            runCatching {
                httpClient.post(endpoint) {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("")
                }.bodyAsText().asJsonObject()["attestation_challenge"]?.jsonPrimitive?.content
            }.getOrNull()
        }

        val pop = buildJwt(
            type = CLIENT_ATTESTATION_POP_TYPE,
            key = key,
            expiresIn = ATTESTATION_POP_VALIDITY,
        ) {
            put("iss", clientId)
            put("aud", endpoints.authorizationServerId)
            put("jti", Random.nextBytes(JTI_BYTES).toBase64Url())
            // `challenge`, NOT `nonce` — multipaz's own spelling, and the one Keycloak accepts.
            challenge?.let { put("challenge", it) }
        }

        return mapOf(
            CLIENT_ATTESTATION_HEADER to attestation,
            CLIENT_ATTESTATION_POP_HEADER to pop,
        )
    }

    private suspend fun createKey(prefix: String): AsymmetricKey {
        val alias = "$prefix${Random.nextBytes(ALIAS_BYTES).toBase64Url()}"
        secureArea.createKey(alias, CreateKeySettings())
        return AsymmetricKey.anonymous(secureArea, alias)
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
        // ⛔ Not `asJsonObject`: one of the EU dev issuers serves its metadata as a SIGNED JWT, and a
        // reader that assumes JSON fails on it. Same helper the deferred collector uses.
        val issuerMetadata = httpClient.get("${issuerUrl.trimEnd('/')}/$ISSUER_METADATA_PATH")
            .bodyAsText().asJsonObjectOrJwtPayload()

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
            authorizationServerId = asMetadata["issuer"]?.jsonPrimitive?.content ?: authorizationServer,
            challengeEndpoint = asMetadata["challenge_endpoint"]?.jsonPrimitive?.content,
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
                runCatching { response.bodyAsText().asJsonObjectOrJwtPayload() }
                    .getOrNull()?.let { return it }
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
        const val ATTESTATION_KEY_PREFIX = "vci-attestation-"
        const val WALLET_INSTANCE_ATTESTATION_PATH = "/wallet-instance-attestation/jwk"
        const val CLIENT_ATTESTATION_HEADER = "OAuth-Client-Attestation"
        const val CLIENT_ATTESTATION_POP_HEADER = "OAuth-Client-Attestation-PoP"
        const val CLIENT_ATTESTATION_POP_TYPE = "oauth-client-attestation-pop+jwt"
        const val HTTP_BAD_REQUEST = 400
        const val PKCE_BYTES = 32
        const val STATE_BYTES = 15
        const val JTI_BYTES = 15
        const val ALIAS_BYTES = 9
        val ATTESTATION_POP_VALIDITY = 2.minutes
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
    /** This document's own shimmed client, so its deferral notice is its own. */
    private val credentialHttpClient: HttpClient,
) : ProvisioningClient {

    /**
     * The issuer's metadata **narrowed to this client's one configuration**.
     *
     * ⛔ Not the whole map. `ProvisioningModel.requestCredentials` builds the document from
     * `issuerMetadata.credentials.values.first()`, so handing it every configuration makes every document
     * — whatever was asked for — out of whichever entry happens to come first. Measured against the dev
     * issuer 2026-09-18: an SD-JWT credential certified onto the mdoc credential that produced, and threw
     * `-39517 bytes leftover after decoding`. multipaz's own client documents the same contract: "when
     * [ProvisioningClient] is configured to issue a particular kind of credential, only that credential
     * will be present in the map".
     */
    override suspend fun getMetadata(): ProvisioningMetadata {
        val full = session.metadata()
        val mine = full.credentials[configurationId]
            ?: throw IllegalStateException("The issuer does not offer $configurationId.")
        return full.copy(credentials = mapOf(configurationId to mine))
    }

    override suspend fun getAuthorizationChallenges(): List<AuthorizationChallenge> =
        listOfNotNull(session.challenge())

    override suspend fun authorize(response: AuthorizationResponse) = session.authorize(response)

    override suspend fun getAuthorizationData(): ByteString? = session.authorizationData(configurationId)

    override suspend fun getKeyBindingChallenge(): String = session.keyBindingChallenge()

    override suspend fun obtainCredentials(keyInfo: KeyBindingInfo): Credentials =
        session.obtainCredentials(configurationId, keyInfo, credentialHttpClient)
}

/** A refresh token and the DPoP key it is now bound to, for one document to keep as its own. */
internal data class RebindResult(val dpopKeyAlias: String, val refreshToken: String)

/** The endpoints one issuance needs, read once per session. */
private data class VciEndpoints(
    val authorizationServer: String,
    /** The `issuer` the AS calls itself, which is what a client-attestation PoP must be addressed to. */
    val authorizationServerId: String,
    val challengeEndpoint: String?,
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

/**
 * What multipaz reads its collaborators out of, when the caller supplies the provisioning client.
 *
 * `ProvisioningModel.launch` takes a `CoroutineContext`, and multipaz's own builder for it is private
 * along with the environment it puts inside (`ProvisioningEnvironment` is `internal`). None of that is
 * needed: [BackendEnvironment] is a public interface whose single method vends four public types, and
 * they are the same four objects [ProvisioningModel] is constructed with. So the context is built here.
 */
internal class IosProvisioningEnvironment(
    private val httpClient: HttpClient,
    secureArea: SecureArea,
    private val clientPreferences: OpenID4VCIClientPreferences,
    private val backend: OpenID4VCIBackend,
) : BackendEnvironment {

    // ⚠️ Dispatchers.Main by default, matching multipaz's own environment: the app pumps a main run
    // loop, so the lazy key creation runs. ⛔ A test binary does not — see MultipazOfferTruncationTest.
    private val secureAreaProvider = SecureAreaProvider { secureArea }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getInterface(clazz: KClass<T>): T? = when (clazz) {
        HttpClient::class -> httpClient
        SecureAreaProvider::class -> secureAreaProvider
        OpenID4VCIClientPreferences::class -> clientPreferences
        OpenID4VCIBackend::class -> backend
        else -> null
    } as T?
}
