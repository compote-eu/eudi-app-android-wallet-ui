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

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.multipaz.crypto.Algorithm
import org.multipaz.provisioning.AuthorizationChallenge
import org.multipaz.provisioning.AuthorizationResponse
import org.multipaz.provisioning.ProvisioningModel
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.util.Logger
import org.multipaz.util.Platform
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How an issuance attempt ended, in the terms the add-document screen reasons about. */
sealed interface IosIssuanceProgress {

    /**
     * At least one document was issued. [failures] is empty on a clean run and names the configurations
     * that did not make it otherwise — the caller decides whether that is a partial success or a failure.
     */
    data class Issued(
        val documentIds: List<String>,
        val failures: Map<String, String> = emptyMap(),
    ) : IosIssuanceProgress

    data class Failure(val message: String) : IosIssuanceProgress
}

/**
 * Issues documents on iOS: OpenID4VCI through multipaz, into the same document store the wallet reads.
 *
 * This is the productized form of the issuance spike, which proved the path end to end against
 * `dev.issuer-backend.eudiw.dev` — real PAR, real authorization, real credentials in the Secure Enclave.
 * What it adds over the spike is everything a screen needs: the browser hop, per-configuration
 * sequencing, and outcomes instead of printed lines.
 *
 * **One authorization per configuration, and that is multipaz's shape rather than a choice.**
 * `OpenID4VCIProvisioningClient` is built for a single credential configuration, so "PID Combined" — two
 * configurations at one issuer — runs two flows and therefore two authorizations. In practice the second
 * is silent: the authorization server still has its session cookie from the first, so the browser returns
 * immediately. Android asks for both in one request instead (multiple scopes), which is nicer but is
 * wallet-core's doing, not something this can imitate without forking multipaz.
 *
 * **Deferred issuance is not supported.** multipaz has no `transaction_id` handling, so an issuer's
 * `*_deferred` configuration fails here with whatever the issuer says. The catalogue still lists those
 * configurations, because filtering them would be a second, hidden policy — better a visible failure than
 * a quietly shorter list.
 */
class IosCredentialIssuer(
    /**
     * The wallet itself, because issuance must write into the *same* store the reader uses: a second
     * `MultipazWalletStore.open()` over the same storage is a second `DocumentStore` with its own cache,
     * and a document created there would not appear in the list until something reloaded.
     */
    private val walletEngine: IosWalletEngine,
    private val issuers: List<IosVciIssuer> = IosIssuerCatalog.issuers,
    /**
     * Opens the authorization URL. Defaults to Safari; injected because a test harness cannot log into
     * Keycloak through a browser, and because this is the one step of the flow iOS cannot drive itself.
     */
    private val openAuthorizationUrl: (String) -> Unit = ::openInSafari,
    /** The transport, injectable for tests. Defaults to Darwin behind the compatibility engine. */
    private val httpEngine: HttpClientEngine? = null,
    private val walletProviderBaseUrl: String = DEFAULT_WALLET_PROVIDER_URL,
    /** How long to wait for the user to finish authorizing in the browser. */
    private val authorizationTimeout: Duration = DEFAULT_AUTHORIZATION_TIMEOUT,
    /**
     * The seam that makes the sequencing above testable: how *one* configuration is issued. Null means
     * the real thing — drive multipaz. A full fake of this would otherwise have to mint issuer-signed
     * credentials, which says nothing about the code here.
     */
    private val issueConfiguration: (suspend (IosVciIssuer, String) -> Result<String>)? = null,
) {

    /**
     * Issues [configurationIds] at [issuerId], as one progress value per attempt sequence.
     *
     * Stops at the first configuration that fails rather than pressing on: a failure usually means the
     * user declined or the issuer refused, and the next configuration would only open another browser
     * window to be declined again. Whatever was issued before that is still reported.
     */
    fun issue(issuerId: String, configurationIds: List<String>): Flow<IosIssuanceProgress> = flow {
        val issuer = issuers.firstOrNull { it.issuerUrl == issuerId }
        if (issuer == null) {
            emit(IosIssuanceProgress.Failure("Unknown issuer: $issuerId"))
            return@flow
        }
        if (configurationIds.isEmpty()) {
            emit(IosIssuanceProgress.Failure("No document was requested."))
            return@flow
        }

        val documentIds = mutableListOf<String>()
        val failures = mutableMapOf<String, String>()

        for (configurationId in configurationIds) {
            val outcome = issueConfiguration?.invoke(issuer, configurationId)
                ?: runCatching { provision(issuer, configurationId) }

            outcome
                .onSuccess { documentIds += it }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Logger.w(TAG, "issuing '$configurationId' failed: ${error.message}")
                    failures[configurationId] =
                        error.message ?: error::class.simpleName ?: "Issuance failed."
                }

            if (failures.isNotEmpty()) break
        }

        emit(
            when {
                documentIds.isNotEmpty() -> IosIssuanceProgress.Issued(documentIds, failures)
                else -> IosIssuanceProgress.Failure(
                    failures.values.firstOrNull() ?: "Nothing was issued."
                )
            }
        )
    }

    /**
     * Issues the documents a credential offer names.
     *
     * One flow for the whole offer, unlike [issue]: an offer *is* one credential offer as far as
     * OpenID4VCI is concerned, so multipaz drives it in one go — and the wallet gets one document out of
     * it, since the offer's configurations belong to a single provisioning session.
     *
     * @param txCode the transaction code the issuer asked for, already collected by the offer-code screen.
     *   Null when the offer wanted none; a pre-authorized offer that wants one and does not get it fails.
     */
    fun issueOffer(offerUri: String, txCode: String?): Flow<IosIssuanceProgress> = flow {
        val outcome = runCatching { provision(offerUri = offerUri, txCode = txCode) }
        emit(
            outcome.fold(
                onSuccess = { IosIssuanceProgress.Issued(documentIds = listOf(it)) },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    Logger.w(TAG, "issuing the offer failed: ${error.message}")
                    IosIssuanceProgress.Failure(
                        error.message ?: error::class.simpleName ?: "Issuance failed."
                    )
                },
            )
        )
    }

    /** Drives one OpenID4VCI flow to a document, or throws with what went wrong. */
    private suspend fun provision(issuer: IosVciIssuer, configurationId: String): String {
        val deferred = DeferredIssuanceNotice()
        val httpClient = openID4VciHttpClient(
            engine = httpEngine ?: Darwin.create(),
            deferredNotice = deferred,
        )
        val walletStore = walletEngine.store()
        // A redirect left over from an earlier attempt carries a spent authorization code.
        IosAuthorizationRedirects.clear()

        val model = ProvisioningModel(
            documentProvisioningHandler = IosDocumentProvisioningHandler(walletStore),
            httpClient = httpClient,
            promptModel = Platform.promptModel,
            authorizationSecureArea = walletStore.keySecureArea,
            // Puts a successful issuance in the History tab; multipaz writes the event itself once
            // the credentials are certified. Note it logs *per document*, so a configuration that
            // yields several produces several entries — which is what the user did, several times.
            eventLogger = walletStore.eventLogger(),
        )

        try {
            val document = model.launchOpenID4VCIProvisioning(
                issuerUrl = issuer.issuerUrl,
                credentialId = configurationId,
                clientPreferences = OpenID4VCIClientPreferences(
                    clientId = issuer.clientId,
                    // Must be the URI registered for this client id; anything else is rejected with
                    // `Invalid parameter: redirect_uri`.
                    redirectUrl = issuer.redirectUri,
                    locales = listOf(FALLBACK_LOCALE),
                    signingAlgorithms = listOf(Algorithm.ESP256),
                ),
                backend = IosOpenID4VciBackend(
                    walletProviderBaseUrl = walletProviderBaseUrl,
                    clientId = issuer.clientId,
                    httpClient = httpClient,
                ),
            )

            return coroutineScope {
                // multipaz asks for authorization by moving to `Authorizing`; answering it is what lets
                // the flow continue, so the two run together and the answering side stops when the
                // document (or an error) arrives.
                val authorizing = launch { answerAuthorizationChallenges(model) }
                try {
                    document.await().identifier
                } catch (t: Throwable) {
                    throw deferred.asFailureOr(t)
                } finally {
                    authorizing.cancel()
                }
            }
        } finally {
            model.cancel()
            httpClient.close()
        }
    }

    /**
     * The offer counterpart of [provision]: multipaz is handed the offer link and works out from it which
     * issuer, which configuration and which grant applies.
     *
     * The client identity still comes from the catalogue when the offering issuer is one this build knows,
     * and from the wallet's own otherwise — an offer may legitimately come from an unknown issuer, and
     * `clientId`/`redirectUrl` are the wallet's identity rather than the issuer's.
     */
    private suspend fun provision(offerUri: String, txCode: String?): String {
        val deferred = DeferredIssuanceNotice()
        val httpClient = openID4VciHttpClient(
            engine = httpEngine ?: Darwin.create(),
            deferredNotice = deferred,
        )
        val walletStore = walletEngine.store()
        IosAuthorizationRedirects.clear()

        val model = ProvisioningModel(
            documentProvisioningHandler = IosDocumentProvisioningHandler(walletStore),
            httpClient = httpClient,
            promptModel = Platform.promptModel,
            authorizationSecureArea = walletStore.keySecureArea,
            // Puts a successful issuance in the History tab; multipaz writes the event itself once
            // the credentials are certified. Note it logs *per document*, so a configuration that
            // yields several produces several entries — which is what the user did, several times.
            eventLogger = walletStore.eventLogger(),
        )

        try {
            val wallet = issuers.first()
            val document = model.launchOpenID4VCIProvisioning(
                offerUri = offerUri,
                clientPreferences = OpenID4VCIClientPreferences(
                    clientId = wallet.clientId,
                    redirectUrl = wallet.redirectUri,
                    locales = listOf(FALLBACK_LOCALE),
                    signingAlgorithms = listOf(Algorithm.ESP256),
                ),
                backend = IosOpenID4VciBackend(
                    walletProviderBaseUrl = walletProviderBaseUrl,
                    clientId = wallet.clientId,
                    httpClient = httpClient,
                ),
            )

            return coroutineScope {
                val authorizing = launch { answerAuthorizationChallenges(model, txCode) }
                try {
                    document.await().identifier
                } catch (t: Throwable) {
                    throw deferred.asFailureOr(t)
                } finally {
                    authorizing.cancel()
                }
            }
        } finally {
            model.cancel()
            httpClient.close()
        }
    }

    /**
     * Watches for authorization challenges and answers each one exactly once.
     *
     * Two kinds arrive. An OAuth challenge is answered by opening the issuer's URL and waiting for the
     * redirect the app delegate delivers. A secret-text challenge is the offer's transaction code, which
     * the offer-code screen has already collected — there is nothing to prompt for here, and no way to
     * ask, since this runs below the UI.
     */
    private suspend fun answerAuthorizationChallenges(
        model: ProvisioningModel,
        txCode: String? = null,
    ) {
        val answered = mutableSetOf<String>()

        model.state.collect { state ->
            if (state !is ProvisioningModel.Authorizing) return@collect

            val challenge = state.authorizationChallenges.firstOrNull { it.id !in answered }
                ?: return@collect
            answered += challenge.id

            when (challenge) {
                is AuthorizationChallenge.OAuth -> {
                    Logger.i(TAG, "opening the authorization URL for challenge ${challenge.id}")
                    openAuthorizationUrl(challenge.url)

                    val redirect = IosAuthorizationRedirects.await(timeout = authorizationTimeout)
                    if (redirect == null) {
                        // Leaving the flow hanging would leave the screen spinning; failing surfaces it
                        // as an issuance failure, which is what a user who never logged in did.
                        throw IllegalStateException("Authorization was not completed.")
                    }

                    model.provideAuthorizationResponse(
                        AuthorizationResponse.OAuth(
                            id = challenge.id,
                            parameterizedRedirectUrl = redirect,
                        )
                    )
                }

                is AuthorizationChallenge.SecretText -> {
                    if (txCode == null) {
                        throw IllegalStateException(
                            "This offer needs a transaction code" +
                                    (challenge.request.description?.let { ": $it" } ?: ".")
                        )
                    }
                    model.provideAuthorizationResponse(
                        AuthorizationResponse.SecretText(id = challenge.id, secret = txCode)
                    )
                }
            }
        }
    }

    /**
     * Turns "multipaz could not read the credential response" into "the issuer is issuing this later",
     * when that is what happened.
     *
     * **Deliberately still a failure, not a deferred success.** Android reports `DeferredSuccess` here and
     * keeps a pending document that its wallet-core later collects from the issuer's
     * `deferred_credential_endpoint`. iOS has nothing that can collect it — multipaz neither parses that
     * endpoint nor exposes the token and DPoP key needed to call it — so a document parked as "pending"
     * would stay pending forever. Saying so is the honest answer until multipaz supports the flow; see the
     * upstream note in the KDoc of this class.
     */
    private fun DeferredIssuanceNotice.asFailureOr(cause: Throwable): Throwable =
        if (wasDeferred) IllegalStateException(DEFERRED_NOT_SUPPORTED) else cause

    companion object {
        private const val TAG = "IosCredentialIssuer"

        /**
         * User-facing, so it says what to do rather than what broke. Not a resource string: :shared-logic
         * holds no strings, and the layers that do (the interactor's `StringCatalog`) pass this through as
         * the issuer's own error text — the same shape Android's wallet-core messages arrive in.
         */
        internal const val DEFERRED_NOT_SUPPORTED: String =
            "This issuer provides this document later, which this app cannot collect yet."
        private const val FALLBACK_LOCALE = "en"

        /**
         * The wallet provider that attests this wallet instance, matching Android's `dev` flavour. Not on
         * [IosVciIssuer] because it is a property of the *wallet*, not of an issuer.
         */
        const val DEFAULT_WALLET_PROVIDER_URL: String = "https://dev.wallet-provider.eudiw.dev"

        /**
         * Long enough for a human to log in, and shorter than nothing: the PAR `request_uri` these
         * redirects belong to expires about a minute after it is issued, so a slower login fails at the
         * token endpoint anyway. This bound only stops the flow waiting forever.
         */
        val DEFAULT_AUTHORIZATION_TIMEOUT: Duration = 5.minutes
    }
}

/**
 * Hands the authorization URL to Safari. The answer comes back as a URL open on the app, which the app
 * delegate routes to [IosAuthorizationRedirects] — the same path the authorization spike used.
 *
 * On the main queue because UIKit requires it, and fire-and-forget because the interesting event is the
 * redirect, not the opening.
 */
private fun openInSafari(url: String) {
    CoroutineScope(Dispatchers.Main).launch {
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            Logger.w("IosCredentialIssuer", "could not parse the authorization URL")
            return@launch
        }
        UIApplication.sharedApplication.openURL(nsUrl, emptyMap<Any?, Any>(), null)
    }
}
