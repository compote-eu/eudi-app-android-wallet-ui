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

// THROWAWAY SPIKE — answers one question: can iOS talk OpenID4VCI to the EU dev issuers through
// multipaz's `org.multipaz.provisioning`, and what would those issuers demand of us?
//
// It reads issuer metadata only. No authorization, no keys, no credentials — which is the point: the
// metadata is what says which key-binding mode each credential configuration requires, and that is the
// question that decides whether iOS needs App Attest at all.
package eu.europa.ec.shared.wallet.multipaz.spike

import io.ktor.client.HttpClient
import eu.europa.ec.shared.wallet.multipaz.IosDocumentProvisioningHandler
import eu.europa.ec.shared.wallet.multipaz.IosOpenID4VciBackend
import eu.europa.ec.shared.wallet.multipaz.MultipazWalletStore
import eu.europa.ec.shared.wallet.multipaz.openID4VciHttpClient
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.securearea.KeyAttestation
import kotlinx.coroutines.withContext
import org.multipaz.crypto.Algorithm
import org.multipaz.prompt.PromptModel
import org.multipaz.provisioning.AuthorizationChallenge
import org.multipaz.provisioning.KeyBindingType
import org.multipaz.provisioning.ProvisioningModel
import org.multipaz.util.Platform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.backend.BackendEnvironment
import kotlin.reflect.KClass

/**
 * Reads an issuer's OpenID4VCI metadata through multipaz and reports what it found.
 *
 * @param issuerUrl the credential issuer identifier, e.g. `https://ec.dev.issuer.eudiw.dev`.
 */
suspend fun probeIssuerMetadata(issuerUrl: String, onResult: (String) -> Unit) {
    // multipaz's provisioning code reaches its collaborators through a `BackendEnvironment` in the
    // coroutine context rather than constructor arguments, so the caller installs one. For a metadata
    // read the only interface it asks for is the HTTP client.
    // The compatibility client, not a bare Darwin one: see `openID4VciHttpClient` for the two
    // multipaz-vs-EU-issuer mismatches it papers over.
    val httpClient = openID4VciHttpClient()
    val environment = object : BackendEnvironment {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> getInterface(clazz: KClass<T>): T? =
            if (clazz == HttpClient::class) httpClient as T else null
    }

    val preferences = OpenID4VCIClientPreferences(
        clientId = "eudi-wallet-ios-spike",
        redirectUrl = "eudi-openid4vci://authorize",
        locales = listOf("en"),
        signingAlgorithms = listOf(Algorithm.ESP256),
    )

    try {
        withContext(environment) {
            val metadata = OpenID4VCI.getMetadata(issuerUrl, httpClient, preferences)
            onResult("$issuerUrl -> display='${metadata.display.text}' ${metadata.credentials.size} configuration(s)")
            metadata.credentials.entries.take(6).forEach { (id, credential) ->
                val binding = when (val type = credential.keyBindingType) {
                    is KeyBindingType.Keyless -> "Keyless"
                    is KeyBindingType.OpenidProofOfPossession ->
                        "ProofOfPossession(${type.algorithm}, aud=${type.aud})"

                    is KeyBindingType.Attestation -> "Attestation(${type.algorithm})"
                }
                onResult("   $id: format=${credential.format::class.simpleName} keyBinding=$binding batch=${credential.maxBatchSize}")
            }
        }
    } catch (t: Throwable) {
        onResult("$issuerUrl FAILED: ${t::class.simpleName}: ${t.message}")
    } finally {
        httpClient.close()
    }
}

/**
 * Asks the real wallet provider for a wallet instance attestation over a freshly generated key.
 *
 * The unit tests prove the request *shape* matches Android's; only the live service can say whether it
 * accepts it. Nothing secret leaves the device — a public EC JWK for a throwaway key.
 */
suspend fun probeWalletProvider(
    baseUrl: String = "https://dev.wallet-provider.eudiw.dev",
    clientId: String = "eudiw-abca",
    onResult: (String) -> Unit,
) {
    val httpClient = openID4VciHttpClient()
    try {
        val backend = IosOpenID4VciBackend(
            walletProviderBaseUrl = baseUrl,
            clientId = clientId,
            httpClient = httpClient,
        )
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val attestation = backend.createJwtWalletAttestation(
            KeyAttestation(publicKey = key.publicKey, certChain = null)
        )
        onResult("wallet attestation from $baseUrl: ${attestation.take(48)}… (${attestation.length} chars)")
    } catch (t: Throwable) {
        onResult("wallet provider FAILED: ${t::class.simpleName}: ${t.message?.take(220)}")
    } finally {
        httpClient.close()
    }
}

/**
 * Drives a real provisioning session as far as it can go without a browser.
 *
 * Everything up to authorization is server-side protocol: issuer metadata, authorization-server
 * metadata, the wallet attestation from the wallet provider, client attestation, DPoP, and the pushed
 * authorization request. Reaching [ProvisioningModel.Authorizing] with a live URL means all of
 * that was accepted by the real issuer. The browser round-trip that follows needs deep-link wiring the
 * iOS host does not have yet, so this stops there and cancels.
 */
suspend fun probeProvisioning(
    issuerUrl: String = "https://dev.issuer-backend.eudiw.dev",
    credentialId: String = "eu.europa.ec.eudi.pid_mso_mdoc",
    onResult: (String) -> Unit,
) {
    val httpClient = openID4VciHttpClient()
    val store = MultipazWalletStore.open()
    val model = ProvisioningModel(
        documentProvisioningHandler = IosDocumentProvisioningHandler(store),
        httpClient = httpClient,
        promptModel = Platform.promptModel,
        authorizationSecureArea = store.keySecureArea,
    )

    try {
        model.launchOpenID4VCIProvisioning(
            issuerUrl = issuerUrl,
            credentialId = credentialId,
            clientPreferences = OpenID4VCIClientPreferences(
                clientId = "eudiw-abca",
                // MUST be the URI registered for this client_id — the authorization server rejects
                // anything else with `Invalid parameter: redirect_uri`. Same value as Android's
                // `BuildConfig.ISSUE_AUTHORIZATION_DEEPLINK`.
                redirectUrl = "eu.europa.ec.euidi://authorization",
                locales = listOf("en"),
                signingAlgorithms = listOf(Algorithm.ESP256),
            ),
            backend = IosOpenID4VciBackend(
                walletProviderBaseUrl = "https://dev.wallet-provider.eudiw.dev",
                clientId = "eudiw-abca",
                httpClient = httpClient,
            ),
        )

        // Report every state change until authorization is offered or something fails.
        val outcome = withTimeoutOrNull(60.seconds) {
            model.state
                .onEach { state -> onResult("  provisioning state: ${state.describe()}") }
                .first { it is ProvisioningModel.Authorizing || it is ProvisioningModel.Error }
        }
        when (outcome) {
            null -> onResult("provisioning timed out before offering authorization")
            is ProvisioningModel.Authorizing -> onResult(
                "reached authorization with ${outcome.authorizationChallenges.size} challenge(s): " +
                        outcome.authorizationChallenges.joinToString { it.describe() }
            )
            is ProvisioningModel.Error -> onResult("provisioning FAILED: ${outcome.err}")
            else -> Unit
        }
    } catch (t: Throwable) {
        onResult("provisioning threw: ${t::class.simpleName}: ${t.message?.take(200)}")
    } finally {
        model.cancel()
        httpClient.close()
    }
}

private fun ProvisioningModel.State.describe(): String = when (this) {
    is ProvisioningModel.Authorizing -> "Authorizing(${authorizationChallenges.size} challenges)"
    is ProvisioningModel.Error -> "Error(${err::class.simpleName}: ${err.message?.take(120)})"
    is ProvisioningModel.CredentialsIssued -> "CredentialsIssued"
    else -> this::class.simpleName ?: "?"
}

private fun AuthorizationChallenge.describe(): String = when (this) {
    is AuthorizationChallenge.OAuth -> "OAuth(url=${url.take(120)}…)"
    is AuthorizationChallenge.SecretText -> "SecretText(retry=$retry)"
}
