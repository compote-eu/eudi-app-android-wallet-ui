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
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tstr
import eu.europa.ec.shared.wallet.config.iosWalletConfig
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
        /**
         * How many credentials a *refresh* fetched; zero for a batch that needed none, and always
         * zero for a first issuance, where the count is implied by the document existing at all.
         *
         * Carried because it is the only observable difference between a refresh that had work to do
         * and one that did not: the credential counter shows *certified* credentials, and a spent one
         * stays until its replacement certifies, so it does not move.
         */
        val credentialsFetched: Int = 0,
    ) : IosIssuanceProgress

    data class Failure(val message: String) : IosIssuanceProgress
}

/**
 * Issues documents on iOS: OpenID4VCI through multipaz, into the same document store the wallet reads.
 *
 * This is the productized form of the first issuance run, which proved the path end to end against
 * `dev.issuer-backend.eudiw.dev` — real PAR, real authorization, real credentials in the Secure Enclave.
 * What it adds over that is everything a screen needs: the browser hop, per-configuration
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
    /**
     * Where documents live. Defaults to the engine's own store, which is what production wants — a
     * second `MultipazWalletStore.open()` would be a second cache over the same storage. Injectable
     * because [refreshCredentials] decides three of its four outcomes from the store's *contents*, and
     * those are the outcomes a user actually meets.
     */
) {

    /**
     * Where documents live. Defaults to the engine's own store, which is what production wants — a
     * second `MultipazWalletStore.open()` would be a second cache over the same storage.
     *
     * A `var` set by the internal constructor rather than a public parameter, because
     * [MultipazWalletStore] is internal to this module and this class is what :shared-ui talks to.
     * [refreshCredentials] decides three of its four outcomes from the store's *contents*, and those
     * are the outcomes a user actually meets, so they are worth being able to test.
     */
    private var walletStore: suspend () -> MultipazWalletStore = { walletEngine.store() }

    internal constructor(
        walletEngine: IosWalletEngine,
        walletStore: suspend () -> MultipazWalletStore,
        issuers: List<IosVciIssuer> = IosIssuerCatalog.issuers,
    ) : this(walletEngine = walletEngine, issuers = issuers) {
        this.walletStore = walletStore
    }

    /**
     * Tops a document's credentials back up, without asking the user for anything.
     *
     * This is what "re-issue" means for a wallet that spends credentials as it presents them: the
     * document, its keys and its issuer are all unchanged, and what has run out is the supply of
     * one-time credentials. multipaz keeps the authorization from the original issuance on the document
     * for exactly this, so a refresh needs no browser and no consent — the user already gave it.
     *
     * @return [IosIssuanceProgress.Issued] naming the same document it started with, or a failure that
     *   says which thing went wrong: nothing stored to authorize with, an authorization that has since
     *   expired, or the issuer refusing for a reason only it knows.
     */
    suspend fun refreshCredentials(documentId: String): IosIssuanceProgress {
        val store = walletStore()
        val document = store.documentStore.lookupDocument(documentId)
            ?: return IosIssuanceProgress.Failure(message = NO_SUCH_DOCUMENT)

        // The document remembers who issued it, so the issuer is looked up rather than passed in: a
        // caller that supplied the wrong one would refresh against an issuer that never knew this
        // document. A document that recorded no issuer at all is refused too — never having said
        // where it came from is not evidence that it came from the one issuer configured here.
        //
        // Checked *before* the authorization data, though that is the more common absence, because of
        // what the two messages tell the user to do. "Add it again" is the advice for a document with
        // no stored authorization, and it is only advice at all if this wallet can still reach that
        // issuer. When neither holds, the dead end is the honest thing to say.
        val issuerUrl = document.eudiMetadata?.issuerMetadata?.credentialIssuerIdentifier
        val issuer = issuers.firstOrNull { it.issuerUrl == issuerUrl }
            ?: return IosIssuanceProgress.Failure(message = UNKNOWN_ISSUER)

        // Stored when the document was first issued. Absent for anything this wallet did not provision
        // — a seeded fixture, or a document from a build before authorization was kept — and there is
        // no silent path for those.
        val authorization = document.authorizationData
            ?: return IosIssuanceProgress.Failure(message = NO_STORED_AUTHORIZATION)

        // Ask *before* opening a session.
        //
        // Mostly because a session is a token request and a round trip to the issuer, and a document
        // that needs no credentials should cost neither. There is a second reason from reading
        // multipaz: it writes the (rotated) authorization data back only inside the branch that
        // actually fetched credentials, so a session that fetches nothing discards whatever the token
        // endpoint returned. That one is still a code reading rather than something observed — the
        // failure that actually stopped every refresh on this stack was `401 invalid_client`, and it
        // had two causes, both worked around now: no attestation challenge on the refresh path
        // (`armRefreshRetry`) and a replayed five-minute-old attestation
        // ([withoutStoredWalletAttestation]).
        //
        // `managedCredentialHelper`'s dry run answers the same question multipaz would ask itself,
        // from the same settings, without creating anything or touching the network.
        if (store.credentialsNeededFor(document) == 0) {
            Logger.i(TAG, "no credentials need replacing for $documentId; not opening a session")
            return IosIssuanceProgress.Issued(documentIds = listOf(documentId), credentialsFetched = 0)
        }

        val deferred = DeferredIssuanceNotice()
        val refusal = TokenRefusalNotice()
        val httpClient = openID4VciHttpClient(
            engine = httpEngine ?: Darwin.create(),
            deferredNotice = deferred,
            refusalNotice = refusal,
        )

        val model = ProvisioningModel(
            documentProvisioningHandler = IosDocumentProvisioningHandler(store),
            httpClient = httpClient,
            promptModel = Platform.promptModel,
            authorizationSecureArea = store.keySecureArea,
            eventLogger = store.eventLogger(),
        )

        return try {
            // Synchronous on purpose: there is no authorization step to answer, so none of `issue`'s
            // browser choreography applies. multipaz drives it to completion or throws.
            val fetched = model.openID4VCIRefreshCredentials(
                document = document,
                authorizationData = withoutStoredWalletAttestation(authorization),
                clientPreferences = issuer.clientPreferences(),
                backend = IosOpenID4VciBackend(
                    walletProviderBaseUrl = walletProviderBaseUrl,
                    clientId = issuer.clientId,
                    httpClient = httpClient,
                ),
            )
            // Zero is a success, and the common one. multipaz replaces only the credentials that are
            // used up or close to expiring, so a document whose batch is still fresh needs none — and
            // reporting that as a failure would put an error on the screen for a wallet that is
            // already in exactly the state the user asked for.
            Logger.i(TAG, "refreshed $documentId with $fetched new credential(s)")
            IosIssuanceProgress.Issued(
                documentIds = listOf(documentId),
                credentialsFetched = fetched,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            IosIssuanceProgress.Failure(message = refreshFailureMessage(refusal, deferred, t))
        } finally {
            model.cancel()
            httpClient.close()
        }
    }

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
                clientPreferences = issuer.clientPreferences(),
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
                clientPreferences = wallet.clientPreferences(),
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

    /**
     * What to tell the user when a refresh failed, preferring the issuer's own reason to multipaz's.
     *
     * `invalid_grant` is worth its own message because it is the failure a working wallet meets most:
     * this stack's refresh tokens live **1800 seconds** (measured on a rotated one, `iat` 07:14:53 →
     * `exp` 07:44:53), so a document left alone for longer than that has nothing left to authorize
     * with. multipaz reports it as *"Refresh token (seed credential) rejected by the issuer"*, which is
     * true and useless — the user can act on "add it again", not on that. Every other refusal keeps
     * the generic message rather than guessing.
     */
    internal fun refreshFailureMessage(
        refusal: TokenRefusalNotice,
        deferred: DeferredIssuanceNotice,
        cause: Throwable,
    ): String = when (refusal.error) {
        INVALID_GRANT -> AUTHORIZATION_EXPIRED
        else -> deferred.asFailureOr(cause).message ?: REFRESH_FAILED
    }

    /**
     * How this wallet identifies itself to an issuer. Identical for every call, so it is built once —
     * the redirect in particular must be the URI registered for [IosVciIssuer.clientId], and an issuer
     * that saw two different ones would reject the second with `Invalid parameter: redirect_uri`.
     */
    private fun IosVciIssuer.clientPreferences() = OpenID4VCIClientPreferences(
        clientId = clientId,
        redirectUrl = redirectUri,
        locales = listOf(FALLBACK_LOCALE),
        signingAlgorithms = listOf(Algorithm.ESP256),
    )

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

        // The four ways a credential refresh can end badly. Each says what a user could do about it,
        // since these reach the details screen's error card verbatim.
        internal const val NO_SUCH_DOCUMENT = "That document is no longer in this wallet."

        internal const val NO_STORED_AUTHORIZATION =
            "This document cannot be refreshed automatically. Add it again to get new credentials."

        internal const val UNKNOWN_ISSUER =
            "This wallet no longer has a connection to the issuer that provided this document."

        internal const val REFRESH_FAILED = "Could not get new credentials for this document."

        internal const val AUTHORIZATION_EXPIRED =
            "This document's authorization has expired. Add it again to get new credentials."

        /** OAuth's name for a refresh token that is spent, revoked or past its expiry. */
        private const val INVALID_GRANT = "invalid_grant"

        /**
         * The wallet provider that attests this wallet instance, per build flavour as on Android. Not
         * on [IosVciIssuer] because it is a property of the *wallet*, not of an issuer.
         */
        val DEFAULT_WALLET_PROVIDER_URL: String get() = iosWalletConfig.walletProviderUrl

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
 * delegate routes to [IosAuthorizationRedirects].
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

/**
 * [authorization] with the stored wallet attestation dropped, so multipaz mints a fresh one instead of
 * replaying an expired one.
 *
 * 🩹 **Working around a multipaz bug, and it is the reason a refresh had never once succeeded.**
 * `obtainToken` reuses `authorizationData.walletAttestation` whenever it is non-null and mints a new one
 * only when it is null. But this wallet provider issues attestations that live **300 seconds** —
 * measured off one stored at issuance: `iat` 05:49:15, `exp` 05:54:15 — so the attestation kept with the
 * document is dead five minutes later, and every refresh after that authenticates with an expired client
 * credential. The issuer answers `401 invalid_client`, which multipaz reports as *"Refresh token (seed
 * credential) rejected by the issuer"* — blaming the token for a rejection of the client.
 *
 * multipaz's own comment on `obtainWalletAttestation` says it obtains "a fresh wallet attestation for
 * every session"; the refresh path is the one that does not. Dropping these two keys is what that intent
 * looks like from out here — they are optional in the CBOR map, so an absent key *is* null — and
 * `obtainWalletAttestation` then creates a new key and asks [IosOpenID4VciBackend] for an attestation
 * over it.
 *
 * **The DPoP key is deliberately left alone.** That is what the refresh token is bound to, so replacing
 * it would trade this failure for `invalid_grant`. Anything that is not the CBOR map multipaz writes is
 * passed through untouched rather than guessed at.
 */
internal fun withoutStoredWalletAttestation(authorization: ByteString): ByteString {
    val decoded = runCatching { Cbor.decode(authorization.toByteArray()) }.getOrNull()
    val map = decoded as? CborMap ?: run {
        Logger.w(REFRESH_TAG, "stored authorization is not a CBOR map; leaving it alone")
        return authorization
    }
    val had = map.items.remove(Tstr(WALLET_ATTESTATION_KEY)) != null
    map.items.remove(Tstr(WALLET_ATTESTATION_KEY_ALIAS_KEY))
    if (had) {
        Logger.i(REFRESH_TAG, "dropped the stored wallet attestation so a fresh one is minted")
    }
    return ByteString(*Cbor.encode(map))
}

/** Matches [IosCredentialIssuer]'s, since this is part of that flow. */
private const val REFRESH_TAG = "IosCredentialIssuer"

/**
 * Keys in the CBOR map multipaz serialises `OpenID4VCIAuthorizationData` to. Optional there, so removing
 * one is how a null is expressed.
 */
private const val WALLET_ATTESTATION_KEY = "walletAttestation"
private const val WALLET_ATTESTATION_KEY_ALIAS_KEY = "walletAttestationKeyAlias"
