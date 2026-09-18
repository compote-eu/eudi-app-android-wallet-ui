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
import org.multipaz.rpc.handler.RpcAuthClientSession
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
 * `OpenID4VCIProvisioningClient` is built for a single credential configuration, so a request naming
 * several runs several flows and therefore several authorizations. Android asks for all of them in one
 * request instead (multiple scopes), which is nicer but is wallet-core's doing, not something this can
 * imitate without forking multipaz.
 *
 * 📌 **Read in multipaz's own source, 0.99.0 and `main` alike (2026-09-18):** `CredentialOffer`
 * declares `abstract val configurationId: String`, the pushed authorization request appends a single
 * `scope` — or a single `authorization_details` entry naming one `credential_configuration_id` — and
 * `ProvisioningModel.launch` resolves to one `Deferred<Document>`. There is no list anywhere on the
 * path, so this is structural rather than a setting somebody forgot to expose.
 *
 * ⚠️ **Each authorization costs the user a tap even when no login is needed.** The authorization server
 * still holds its session cookie, so the browser comes back in a few seconds without asking for
 * credentials — but iOS asks *"open in EUDI Wallet?"* on every hand-back. An earlier version of this
 * comment called the second round "silent"; it is silent as to *login* only. Verified on a device
 * 2026-09-04.
 *
 * 🚩 **What that costs today: "PID Combined" asks for FOUR browser confirmations here against ONE on
 * Android.** The EUDI dev issuer publishes every credential twice — plain and `_deferred` — **under one
 * scope**, so "Combined" expands to four configurations and each one authorizes separately. Watched on a
 * device 2026-09-18: four confirmations, four documents. ⚖️ Kept deliberately — see [issue], below.
 *
 * 🔄 **Deferred issuance IS supported**, since `1261ce22`. A `*_deferred` configuration parks with the
 * issuer's handle and is collected later by `IosDeferredDocumentCompleter`, on the issuer's own
 * `interval`. ⛔ An earlier version of this comment said the opposite and described a de-duplication
 * guard that skipped the `_deferred` twins; both the guard and the limitation are gone (`a44126d1`).
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
     * The same seam for the offer shapes that must stay on multipaz's own flow — pre-authorized, or
     * carrying `issuer_state`. Separate from [issueConfiguration] because the point of the cases that
     * use it is precisely that they do *not* go per configuration.
     */
    private val issueWholeOffer: (suspend (IosCredentialOffer, String?) -> Result<String>)? = null,
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
            // With the notice, so a deferred refresh parks the handle instead of only failing.
            documentProvisioningHandler = IosDocumentProvisioningHandler(store, deferred = deferred),
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
            // A deferred refresh is not a failure either: the document keeps the credentials it
            // already had, and the handle now on it lets the sweep claim the new ones later.
            if (deferred.parkedDocumentId != null) {
                Logger.i(TAG, "the issuer deferred the refresh of $documentId; it will be collected later")
                IosIssuanceProgress.Issued(documentIds = listOf(documentId), credentialsFetched = 0)
            } else {
                IosIssuanceProgress.Failure(message = refreshFailureMessage(refusal, deferred, t))
            }
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

        // ⛔ Every offered configuration is attempted, including a `_deferred` twin sharing the plain
        // one's authorization scope. There used to be a guard skipping those, and it was right when it
        // was written: the twin cost a second browser confirmation and then *failed*, because deferred
        // issuance was unsupported — and the failure abandoned the rest of the request, so "PID
        // Combined" delivered one document instead of two.
        //
        // Deferred issuance now works, so the twin parks and completes like any other document.
        //
        // ✅ And the confirmations they used to cost are gone: more than one configuration is authorized
        // ONCE, by [IosVciAuthorizationSession], instead of once per configuration as multipaz does
        // (multipaz#2026). "PID Combined" is four configurations and one browser confirmation.
        if (issueConfiguration == null && configurationIds.size > 1) {
            val together = runCatching { provisionTogether(issuer, configurationIds) }
            together
                .onSuccess { (issued, failed) ->
                    documentIds += issued
                    failures += failed
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Logger.w(TAG, "issuing ${configurationIds.size} configurations failed: ${error.message}")
                    failures[configurationIds.first()] =
                        error.message ?: error::class.simpleName ?: "Issuance failed."
                }

            emit(
                when {
                    documentIds.isNotEmpty() -> IosIssuanceProgress.Issued(documentIds, failures)
                    else -> IosIssuanceProgress.Failure(
                        failures.values.firstOrNull() ?: "Nothing was issued."
                    )
                }
            )
            return@flow
        }

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
     * Issues every credential the offer names, not just the first.
     *
     * 🚩 **multipaz reads only the first configuration an offer names.**
     * `CredentialOffer.parseJson` does `credentialConfigurationIds[0]`, under its own comment
     * *"Right now only use the first configuration id"* — unchanged in 0.99.0 and on `main`
     * (read 2026-09-18), and reported as multipaz#2026. Nothing is logged, so an offer naming several
     * credentials would quietly yield one where the screen promised several.
     * ✅ Measured by `MultipazOfferTruncationTest`, which drives multipaz's real client: the pushed
     * authorization request for a two-credential offer names only the first.
     *
     * So the offer is read by [IosCredentialOfferReader] instead, and each configuration is issued
     * through the same per-configuration flow [issue] uses. That costs one browser confirmation per
     * credential — multipaz authorizes per configuration — which is the price of getting all of them.
     *
     * ⛔ **Two offer shapes keep multipaz's own flow**, because re-expressing them per configuration
     * would drop something the issuer relies on:
     * - a **pre-authorized** offer, whose code only multipaz's offer client holds;
     * - an offer carrying **`issuer_state`**, which ties the authorization request back to this offer.
     *
     * Both are single-document today, exactly as before. [IosOpenID4VciProvisioningClient] is what lifts
     * that restriction; until then this is deliberately the conservative half.
     *
     * @param offer the offer as the screen resolved it — already parsed, so it is not read twice.
     * @param txCode the transaction code the issuer asked for, already collected by the offer-code screen.
     *   Null when the offer wanted none; a pre-authorized offer that wants one and does not get it fails.
     */
    fun issueOffer(offer: IosCredentialOffer, txCode: String?): Flow<IosIssuanceProgress> = flow {
        val configurationIds = offer.configurationIds
        val mustUseOfferFlow = offer.isPreAuthorized || offer.issuerState != null
        if (mustUseOfferFlow || configurationIds.size <= 1) {
            val outcome = issueWholeOffer?.invoke(offer, txCode)
                ?: runCatching { provision(offerUri = offer.offerUri, txCode = txCode) }
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
            return@flow
        }

        // The offering issuer may be one this build does not know; the client identity is the wallet's
        // own either way, which is the rule the offer flow already follows.
        val issuer = issuers.firstOrNull { it.issuerUrl == offer.issuerUrl } ?: issuers.first()
        val documentIds = mutableListOf<String>()
        val failures = mutableMapOf<String, String>()

        for (configurationId in configurationIds) {
            val outcome = issueConfiguration?.invoke(issuer, configurationId)
                ?: runCatching {
                    provision(issuer, configurationId, issuerUrl = offer.issuerUrl)
                }

            outcome
                .onSuccess { documentIds += it }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Logger.w(TAG, "issuing '$configurationId' from the offer failed: ${error.message}")
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
     * Issues several configurations behind **one** authorization.
     *
     * multipaz authorizes per configuration, so its own client would open the browser once per
     * credential (multipaz#2026). [IosVciAuthorizationSession] performs a single pushed authorization
     * request naming all of them and every per-configuration client then reports no challenge, so
     * `ProvisioningModel.launch`'s loop opens the browser exactly once.
     *
     * ⛔ **Each configuration still gets its own HTTP client and its own notices.** The deferral notice
     * is per document — it is what turns a `202 Accepted` into a parked document — so sharing one client
     * across configurations would let one document's deferral be read as another's.
     *
     * 📌 The coroutine context is built here rather than by multipaz, whose own builder is private.
     * Every part of it is public API: `BackendEnvironment` is an interface, and the four things it vends
     * are the same four objects passed to [ProvisioningModel].
     *
     * @return the documents issued, and the configurations that failed with why.
     */
    private suspend fun provisionTogether(
        issuer: IosVciIssuer,
        configurationIds: List<String>,
        issuerUrl: String = issuer.issuerUrl,
    ): Pair<List<String>, Map<String, String>> {
        val walletStore = walletEngine.store()
        IosAuthorizationRedirects.clear()

        val clientPreferences = issuer.clientPreferences()

        // ⛔ The claim-display and reuse-policy notices are filled in by the shim **while it reads the
        // issuer's metadata**, and in this path the metadata is read once, by the session. So they are
        // created once for the whole batch and given to every client, including the authorization one —
        // otherwise nothing ever sees the metadata response and the issuer's `credential_reuse_policy` is
        // lost, which showed up as a "7/20" credential counter where Android reads 7/7.
        // ⚖️ Sharing them is right rather than merely convenient: both are keyed by doctype or vct, which
        // is an issuer-level fact, not a per-document one. The deferral notice is the opposite and stays
        // per document.
        val claimDisplay = IssuerClaimDisplayNotice()
        val reusePolicy = IssuerReusePolicyNotice()
        val authorizationHttpClient = openID4VciHttpClient(
            engine = httpEngine ?: Darwin.create(),
            claimDisplayNotice = claimDisplay,
            reusePolicyNotice = reusePolicy,
        )
        val session = IosVciAuthorizationSession(
            issuerUrl = issuerUrl,
            configurationIds = configurationIds,
            clientPreferences = clientPreferences,
            httpClient = authorizationHttpClient,
            secureArea = walletStore.keySecureArea,
            backend = IosOpenID4VciBackend(
                walletProviderBaseUrl = walletProviderBaseUrl,
                clientId = issuer.clientId,
                httpClient = authorizationHttpClient,
            ),
            walletProviderBaseUrl = walletProviderBaseUrl,
            clientId = issuer.clientId,
        )

        val documentIds = mutableListOf<String>()
        val failures = mutableMapOf<String, String>()

        try {
            for (configurationId in configurationIds) {
                val deferred = DeferredIssuanceNotice()
                val httpClient = openID4VciHttpClient(
                    engine = httpEngine ?: Darwin.create(),
                    deferredNotice = deferred,
                    claimDisplayNotice = claimDisplay,
                    reusePolicyNotice = reusePolicy,
                )
                val model = ProvisioningModel(
                    documentProvisioningHandler = IosDocumentProvisioningHandler(
                        walletStore,
                        claimDisplay = claimDisplay,
                        reusePolicy = reusePolicy,
                        deferred = deferred,
                    ),
                    httpClient = httpClient,
                    promptModel = Platform.promptModel,
                    authorizationSecureArea = walletStore.keySecureArea,
                    eventLogger = walletStore.eventLogger(),
                )

                val environment = IosProvisioningEnvironment(
                    httpClient = httpClient,
                    secureArea = walletStore.keySecureArea,
                    clientPreferences = clientPreferences,
                    backend = IosOpenID4VciBackend(
                        walletProviderBaseUrl = walletProviderBaseUrl,
                        clientId = issuer.clientId,
                        httpClient = httpClient,
                    ),
                )

                try {
                    val document = model.launch(
                        coroutineContext = Dispatchers.Default + Platform.promptModel +
                            RpcAuthClientSession() + environment,
                        document = null,
                    ) {
                        IosOpenID4VciProvisioningClient(session, configurationId, httpClient)
                    }

                    coroutineScope {
                        val authorizing = launch { answerAuthorizationChallenges(model) }
                        try {
                            documentIds += document.await().identifier
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (t: Throwable) {
                            // Same rule as the single-configuration path: a parked document IS the result.
                            val parked = deferred.parkedDocumentId
                            if (parked != null) {
                                documentIds += parked
                            } else {
                                Logger.w(TAG, "issuing '$configurationId' failed: ${t.message}")
                                failures[configurationId] = deferred.asFailureOr(t).message
                                    ?: t::class.simpleName ?: "Issuance failed."
                            }
                        } finally {
                            authorizing.cancel()
                        }
                    }
                } finally {
                    model.cancel()
                    httpClient.close()
                }

                if (failures.isNotEmpty()) break
            }
        } finally {
            authorizationHttpClient.close()
        }

        return documentIds to failures
    }

    /** Drives one OpenID4VCI flow to a document, or throws with what went wrong. */
    private suspend fun provision(
        issuer: IosVciIssuer,
        configurationId: String,
        /** The issuer to talk to, which for an offer is the offering issuer rather than the catalogue's. */
        issuerUrl: String = issuer.issuerUrl,
    ): String {
        val deferred = DeferredIssuanceNotice()
        val claimDisplay = IssuerClaimDisplayNotice()
        val reusePolicy = IssuerReusePolicyNotice()
        val httpClient = openID4VciHttpClient(
            engine = httpEngine ?: Darwin.create(),
            deferredNotice = deferred,
            claimDisplayNotice = claimDisplay,
            reusePolicyNotice = reusePolicy,
        )
        val walletStore = walletEngine.store()
        // A redirect left over from an earlier attempt carries a spent authorization code.
        IosAuthorizationRedirects.clear()

        val model = ProvisioningModel(
            documentProvisioningHandler = IosDocumentProvisioningHandler(
                walletStore,
                claimDisplay = claimDisplay,
                reusePolicy = reusePolicy,
                // So a `202 Accepted` parks the document instead of deleting it.
                deferred = deferred,
            ),
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
                issuerUrl = issuerUrl,
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
                    // A deferred issuance is not a failure: the handler kept the document and stamped
                    // the issuer's handle on it, so the id of that parked document IS the result. It
                    // reads as `Pending` until `IosDeferredDocumentCompleter` finishes it.
                    deferred.parkedDocumentId ?: throw deferred.asFailureOr(t)
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
        val claimDisplay = IssuerClaimDisplayNotice()
        val reusePolicy = IssuerReusePolicyNotice()
        val httpClient = openID4VciHttpClient(
            engine = httpEngine ?: Darwin.create(),
            deferredNotice = deferred,
            claimDisplayNotice = claimDisplay,
            reusePolicyNotice = reusePolicy,
        )
        val walletStore = walletEngine.store()
        IosAuthorizationRedirects.clear()

        val model = ProvisioningModel(
            documentProvisioningHandler = IosDocumentProvisioningHandler(
                walletStore,
                claimDisplay = claimDisplay,
                reusePolicy = reusePolicy,
                // So a `202 Accepted` parks the document instead of deleting it.
                deferred = deferred,
            ),
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
                    // A deferred issuance is not a failure: the handler kept the document and stamped
                    // the issuer's handle on it, so the id of that parked document IS the result. It
                    // reads as `Pending` until `IosDeferredDocumentCompleter` finishes it.
                    deferred.parkedDocumentId ?: throw deferred.asFailureOr(t)
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
     * ⚠️ **This is now the fallback, not the main path.** A deferred issuance normally parks a document:
     * `IosDocumentProvisioningHandler.cleanupDocumentOnError` keeps it and stamps the issuer's handle,
     * and the caller returns that document's id as the result. This message is what remains for the
     * cases where parking could not happen — a document with no wallet metadata, or a **refresh** that
     * was deferred, where multipaz keeps the document but nothing has yet stamped a handle on it.
     *
     * 📌 The old claim here — that iOS "has nothing that can collect it" — was true until
     * [IosDeferredCredentialCollector] existed. multipaz still never parses
     * `deferred_credential_endpoint`; the wallet now does it instead.
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
