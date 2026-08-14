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
import eu.europa.ec.shared.wallet.multipaz.IosAuthorizationRedirects
import eu.europa.ec.shared.wallet.multipaz.IosDocumentProvisioningHandler
import eu.europa.ec.shared.wallet.multipaz.MultipazWalletEngine
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
import org.multipaz.provisioning.AuthorizationResponse
import org.multipaz.provisioning.KeyBindingType
import org.multipaz.provisioning.ProvisioningModel
import org.multipaz.util.Platform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
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
    // A redirect left over from an earlier attempt carries a spent code.
    IosAuthorizationRedirects.clear()
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
        val offered = withTimeoutOrNull(60.seconds) {
            model.state
                .onEach { state -> onResult("  provisioning state: ${state.describe()}") }
                .first { it is ProvisioningModel.Authorizing || it is ProvisioningModel.Error }
        }
        when (offered) {
            null -> {
                onResult("provisioning timed out before offering authorization")
                return
            }

            is ProvisioningModel.Error -> {
                onResult("provisioning FAILED: ${offered.err}")
                return
            }

            is ProvisioningModel.Authorizing -> {
                val challenge = offered.authorizationChallenges.first()
                onResult("authorization challenge: ${challenge.describe()}")
                if (challenge is AuthorizationChallenge.OAuth) {
                    // Printed in full and on its own line so it can be driven from outside: there is no
                    // way to log into Keycloak from a test harness on the simulator.
                    onResult("AUTHORIZE-HERE ${challenge.url}")
                }

                // The app shell delivers the redirect (see IosAuthorizationRedirects); until iOS opens
                // the browser itself, that is `xcrun simctl openurl` with the URL the login ends on.
                onResult("waiting for the authorization redirect… (or $REDIRECT_FILE_NAME in Documents)")
                val redirect = awaitRedirectFromAppOrFile(onResult)
                if (redirect == null) {
                    onResult("no authorization redirect arrived; stopping here")
                    return
                }
                onResult("got redirect: ${redirect.take(80)}…")
                model.provideAuthorizationResponse(
                    AuthorizationResponse.OAuth(
                        id = challenge.id,
                        parameterizedRedirectUrl = redirect,
                    )
                )
            }

            else -> Unit
        }

        // From here multipaz exchanges the code, creates Secure Enclave keys, proves possession of them
        // and asks for credentials.
        val finished = withTimeoutOrNull(120.seconds) {
            model.state
                .onEach { state -> onResult("  provisioning state: ${state.describe()}") }
                .first { it is ProvisioningModel.CredentialsIssued || it is ProvisioningModel.Error }
        }
        when (finished) {
            null -> onResult("provisioning stalled after authorization")
            is ProvisioningModel.Error -> onResult("provisioning FAILED after authorization: ${finished.err}")
            is ProvisioningModel.CredentialsIssued -> {
                onResult(
                    "CREDENTIALS ISSUED: document=${finished.document.identifier} " +
                            "new=${finished.isNewlyIssued} credentials=${finished.numCredentialsFetched}"
                )
                // The point of step 3: the document must be visible to the reader, not just stored.
                MultipazWalletEngine(store).getAllDocumentsWithDetails(locale = "en").forEach {
                    onResult("  reader sees: ${it.name} / ${it.formatType} state=${it.issuanceState} credentials=${it.credentialsCount}/${it.initialCredentialsCount}")
                }
            }

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

/**
 * The redirect file this harness watches, inside the app's Documents directory.
 *
 * Why it exists: the production path is the app delegate calling [IosAuthorizationRedirects.deliver]
 * (see `iOSApp.swift`), and on a device that is exactly what happens. On the *simulator* it cannot be
 * exercised from a script — `xcrun simctl openurl` reports success, LaunchServices logs
 * "Found application … to handle url scheme", and then the app is never activated and the delegate never
 * runs (`Error fetching bundle record for scheme approval`, `-10814`, survives a clean reinstall). Since
 * completing the login needs a browser anyway, the harness takes the redirect through the filesystem
 * instead, which a test script can write from the host.
 */
private const val REDIRECT_FILE_NAME = "authorization-redirect.txt"

/** Races the real app-delegate hand-off against the harness file, whichever arrives first. */
private suspend fun awaitRedirectFromAppOrFile(onResult: (String) -> Unit): String? = coroutineScope {
    val fromApp = async { IosAuthorizationRedirects.await() }
    val fromFile = async { pollRedirectFile() }
    val winner = select {
        fromApp.onAwait { it?.also { onResult("redirect arrived from the app delegate") } }
        fromFile.onAwait { it?.also { onResult("redirect arrived from $REDIRECT_FILE_NAME") } }
    }
    fromApp.cancel()
    fromFile.cancel()
    winner
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private suspend fun pollRedirectFile(): String? {
    val path = NSHomeDirectory() + "/Documents/" + REDIRECT_FILE_NAME
    val manager = NSFileManager.defaultManager
    // Start clean: a file left from a previous run holds a spent authorization code.
    if (manager.fileExistsAtPath(path)) manager.removeItemAtPath(path, null)

    repeat(180) {
        delay(1.seconds)
        if (manager.fileExistsAtPath(path)) {
            val contents = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
                ?.trim()
            manager.removeItemAtPath(path, null)
            if (!contents.isNullOrEmpty()) return contents
        }
    }
    return null
}
