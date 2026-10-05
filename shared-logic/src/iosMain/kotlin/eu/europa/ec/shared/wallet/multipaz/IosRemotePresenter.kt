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

import eu.europa.ec.shared.wallet.trust.IosEtsiTrust
import eu.europa.ec.shared.wallet.trust.ReaderTrustSource
import eu.europa.ec.shared.wallet.trust.certChain
import eu.europa.ec.shared.wallet.trust.requesterSignedBy
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.multipaz.asn1.OID
import org.multipaz.crypto.X509CertChain
import org.multipaz.presentment.ConsentData
import org.multipaz.presentment.CredentialQueryResult
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.uriSchemePresentment
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

/** Where a remote presentation has got to, as the screens need to see it. */
sealed interface IosRemotePresentationState {

    data object Idle : IosRemotePresentationState

    /** The request object is being fetched and verified; nothing has been shown to the user yet. */
    data object Resolving : IosRemotePresentationState

    /** A verifier has asked for something and the user has not answered yet. */
    data class Requesting(val request: IosPresentmentRequest) : IosRemotePresentationState

    data object Sending : IosRemotePresentationState

    /**
     * The verifier accepted the response.
     *
     * [sharedDocuments] names what was released, and [redirectUri] is where the verifier would like the
     * user sent afterwards — null when it asks for no redirect, which is the common case.
     */
    data class Sent(
        val sharedDocuments: List<String>,
        val redirectUri: String?,
    ) : IosRemotePresentationState

    data class Failed(val message: String) : IosRemotePresentationState

    /**
     * The verifier rejected the response — a refusal in the shape Android's openid4vp-kt calls a
     * rejection ([verifierRejectionOf]), which wallet-core reports as `TransferEvent.Rejected`.
     *
     * Not a [Failed]: the exchange reached its end and the relying party said no, so the screen says that
     * and offers Close, not a Retry that would send the same response again. [redirectUri] is where the
     * verifier asked the user to be sent instead — null when it named nowhere.
     */
    data class Rejected(val redirectUri: String?) : IosRemotePresentationState

    /**
     * The verifier asked for something this wallet does not hold.
     *
     * ⚠️ **Not a [Failed].** Nothing went wrong: the request was understood, answered as far as it
     * could be, and the honest reply is that there is nothing to show. The shared screens already model
     * this — `PresentationRequestInteractorPartialState.NoData` renders a proper screen with the
     * requester's header and no error — and Android reaches it from two branches of its own. Routing it
     * through `Failed` instead put a *"something went wrong"* heading and a Retry button over an
     * ordinary outcome, which is what a colleague saw on a simulator on 2026-09-17.
     *
     * Carries who asked, as the consent screen would have named them, because the screen still shows
     * the requester's header — and multipaz ends this way before consent, so no request reaches the app
     * to name them from. Android's no-data screen names the verifier too.
     */
    data class NothingToShare(
        val requesterName: String? = null,
        val requesterIsTrusted: Boolean = false,
        val relyingPartyRegistration: RelyingPartyRegistrationOutcome = RelyingPartyRegistrationOutcome.NotOffered,
    ) : IosRemotePresentationState

    /**
     * Blocked: nothing this wallet trusts vouches for the verifier's access certificate, or the trust lists
     * could not say. Nothing was matched, nothing was asked, and nothing is sent — the request was refused
     * before the wallet learned where it could answer. Android's and the official iOS wallet's "Presentation
     * blocked", reached at the same point and failing closed the same way.
     */
    data object VerifierNotTrusted : IosRemotePresentationState

    /**
     * Refused: with the registration check on, an `x509_hash` verifier sent no registration certificate, or
     * more than one, or one in the wrong shape. The verifier was told `invalid_request`; nothing was matched
     * or asked. Android reaches the same point through openid4vp-kt and shows its generic error, so the
     * screen does too — see [registrationCertificateRequirementFailure].
     */
    data object RegistrationCertificateMissing : IosRemotePresentationState
}

/**
 * Remote presentation on iOS: OpenID4VP 1.0 over a URI scheme, against a verifier reached over HTTPS.
 *
 * The proximity twin of this class is [IosProximityPresenter], and the two are deliberately the same
 * shape — multipaz reduces both protocols to one `CredentialPresentmentSource`, so what the app adds is
 * the same in both cases: which credentials may be offered, and a consent step that waits for a *person*
 * rather than answering itself. [state] is what a screen renders; [accept] and [decline] are what a
 * screen calls back.
 *
 * Two differences from proximity, both from the protocol rather than from taste. There is no engagement
 * step — the verifier's URI arrives as a deep link already carrying everything — so the flow starts at
 * [start] and the first thing a user sees is the consent screen. And SD-JWT VC credentials are offerable
 * here: OpenID4VP carries them, whereas ISO 18013-5 is mdoc-only.
 *
 * **Proven against the EUDI dev verifier**, which is worth recording because it was the open question:
 * multipaz implements OpenID4VP 1.0 (its `DRAFT_29`) and a verifier wanting an older draft would have
 * failed at the response rather than at the request. `dev.verifier-backend.eudiw.dev` accepted a real
 * response — request object over `request_uri`, DCQL match, encrypted `direct_post.jwt` response, and a
 * device signature over the `OpenID4VPHandover` session transcript.
 */
class IosRemotePresenter internal constructor(
    private val walletEngine: IosWalletEngine,
    /** Where the wallet's own credentials live; anything else in the store is not offered. */
    private val credentialDomain: String,
    private val scope: CoroutineScope,
    /**
     * Who the verifier is. Null answers "unknown" without asking anyone — which is what a presentment
     * test wants, since a real check would make it pass or fail with the network.
     *
     * No default *here* on purpose: two constructors both accepting three arguments would be an
     * ambiguous overload, so the public one below is the single place the production value is chosen.
     */
    private val readerTrust: ReaderTrustSource?,
    /** Whether the user asked for registration certificates to be checked; read on every request. */
    private val isRegistrationCheckEnabled: suspend () -> Boolean,
) {

    /**
     * The constructor `:shared-ui` and Swift use.
     *
     * Reader trust is deliberately not a parameter of it: [IosEtsiTrust] hands back a multipaz
     * `TrustMetadata`, and this module's boundary is that nothing above it names a multipaz type. So
     * the primary constructor is `internal` and this one supplies the production value.
     */
    constructor(
        walletEngine: IosWalletEngine,
        /**
         * The user's registration-check setting. No default: the stored value lives in `:shared-ui`,
         * and a silent `false` here would switch the check off for any caller that forgot it.
         */
        isRegistrationCheckEnabled: suspend () -> Boolean,
        credentialDomain: String = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    ) : this(walletEngine, credentialDomain, scope, IosEtsiTrust(), isRegistrationCheckEnabled)

    /** Filled in by the observing engine while multipaz fetches the request object. */
    private var requestNotice = PresentationRequestNotice()

    /**
     * What the verifier's registration certificate says, or [RelyingPartyRegistrationOutcome.NotOffered]
     * when it publishes none — which is most of them today.
     *
     * Asked only when the user switched the registration check on, as Android does: wallet-core
     * evaluates a verifier's certificate only under `WrpRegistrationPolicy.Enabled`, which follows the
     * same setting, and it is off by default. Off means not looked at, so no trust list is consulted and
     * no status list is fetched — the check's traffic stops with it.
     *
     * Never throws and never blocks the consent screen: a verifier whose registration cannot be judged
     * is still one the user may want to answer, and Android refuses nothing on this either.
     */
    private suspend fun evaluateRelyingPartyRegistration(): RelyingPartyRegistrationOutcome {
        if (!isRegistrationCheckEnabled()) return RelyingPartyRegistrationOutcome.NotChecked
        val requestObject = requestNotice.requestObject
            ?: return RelyingPartyRegistrationOutcome.NotOffered
        // The concrete ETSI source, because the trust *lists* are what a registration certificate is
        // judged against and `ReaderTrustSource` deliberately exposes only the verifier-metadata
        // question. A test supplying a different source gets NotOffered, which is the honest answer.
        val etsi = readerTrust as? IosEtsiTrust ?: return RelyingPartyRegistrationOutcome.NotOffered

        return runCatching {
            withEtsiRegistrationValidator(etsi) { evaluate(requestObject, requestNotice.requestSigner) }
        }.getOrElse {
            Logger.w(TAG, "relying party registration could not be evaluated: ${it.message}")
            RelyingPartyRegistrationOutcome.NotOffered
        }
    }

    private val mutableState =
        MutableStateFlow<IosRemotePresentationState>(IosRemotePresentationState.Idle)
    val state: StateFlow<IosRemotePresentationState> = mutableState.asStateFlow()

    private var presentmentJob: Job? = null
    private var pendingConsent: CompletableDeferred<CredentialSelection?>? = null

    /** The request being consented to, kept so [accept] can turn the app's answer back into matches. */
    private var pendingData: CredentialQueryResult? = null

    /** What the user agreed to share, remembered so the success state can name it. */
    private var sharedDocuments: List<String> = emptyList()

    /** How many times multipaz has asked for consent in the current exchange; for the log only. */
    private var consentRequests: Int = 0

    /** The History row of the current exchange; see [IosPresentationLog]. */
    private var history: IosPresentationLog? = null

    /**
     * Starts the exchange the verifier's link describes.
     *
     * Returns immediately; the exchange continues in the background and shows up in [state]. The URI is
     * whatever the deep link carried — an `openid4vp:`/`eudi-openid4vp:`/`mdoc-openid4vp:`/`haip-vp:`
     * link, all four of which differ only in scheme, or the `mdoc://` reader-engagement form multipaz
     * also accepts here.
     */
    fun start(uri: String) {
        // Logged because a second `start` silently cancels the first exchange, and that is invisible
        // otherwise: the whole send leg used to log nothing at all between multipaz building the
        // response and the flow ending, which made a stalled exchange indistinguishable from a
        // completed one. Both were "no further output".
        Logger.i(TAG, "starting an exchange for ${uri.substringBefore(':')}; cancelling any previous")
        cancel()
        // A fresh one per exchange: answering with the previous verifier's `response_uri` would tell
        // the wrong party, and telling nobody is better than telling the wrong one. It carries the link's
        // `client_id`, which the signed request object must repeat.
        requestNotice = PresentationRequestNotice(linkClientId = linkClientIdOf(uri))
        val history = IosPresentationLog { walletEngine.store() }.also { this.history = it }
        mutableState.value = IosRemotePresentationState.Resolving

        presentmentJob = scope.launch {
            try {
                val redirect = uriSchemePresentment(
                    source = presentmentSource(),
                    uri = uri,
                    // Neither is known: the link arrives through the system, which tells an iOS app
                    // nothing trustworthy about who sent it. Saying so is what makes multipaz treat the
                    // request as one to be judged on its signature alone.
                    appId = null,
                    origin = null,
                    // Wrapped so the request object's `response_uri` and `state` are seen in
                    // passing; they are what a rejection has to be addressed to, and multipaz keeps
                    // its parsed request to itself. It is also where an untrusted verifier is refused.
                    httpClientEngineFactory = PresentationObservingEngineFactory(
                        notice = requestNotice,
                        isVerifierTrusted = ::isVerifierTrusted,
                        isRegistrationCheckEnabled = isRegistrationCheckEnabled,
                    ),
                )
                Logger.i(
                    TAG,
                    "response accepted by the verifier; " +
                            "shared=${sharedDocuments.joinToString()}, " +
                            "redirect=${redirect?.let { "yes" } ?: "none"}",
                )
                mutableState.value = IosRemotePresentationState.Sent(
                    sharedDocuments = sharedDocuments,
                    redirectUri = redirect,
                )
                history.completed()
            } catch (e: CancellationException) {
                // 🪤 The state is deliberately left alone — whoever cancelled owns it — but say so,
                // because a cancellation mid-send leaves `Sending` on screen for ever otherwise.
                Logger.i(TAG, "the exchange was cancelled")
                throw e
            } catch (t: Throwable) {
                // Two of multipaz's outcomes are answers rather than errors, and the screens show them
                // differently — the same distinction the proximity presenter makes.
                when (t) {
                    is PresentmentCanceledException -> {
                        // The user declined. Nothing was shared and nothing went wrong.
                        mutableState.value = IosRemotePresentationState.Idle
                        history.stopped()
                    }

                    is PresentmentCannotSatisfyRequestException -> {
                        // An answer, not an error — see [IosRemotePresentationState.NothingToShare].
                        val registration = evaluateRelyingPartyRegistration()
                        val nothing = nothingToShare(
                            notice = requestNotice,
                            readerTrust = readerTrust,
                            registration = registration,
                        )
                        mutableState.value = nothing
                        history.nothingToShare(nothing.requesterName, registration.partyRecord())
                    }

                    else -> if (t.isUntrustedVerifierRefusal()) {
                        Logger.w(TAG, "blocked: the verifier's access certificate is not trusted")
                        mutableState.value = IosRemotePresentationState.VerifierNotTrusted
                    } else if (t.isRegistrationCertificateRefusal()) {
                        // Told first, as wallet-core dispatches the error before reporting it; the notice
                        // already holds the verified request's `response_uri` and `state`.
                        tellVerifier(INVALID_REQUEST)
                        mutableState.value = IosRemotePresentationState.RegistrationCertificateMissing
                    } else {
                        // multipaz's bare `check(...)` on the answer's status is what threw; the engine
                        // read the body it discarded.
                        val rejection = requestNotice.verifierRejection
                        if (rejection != null) {
                            rejected(rejection)
                            history.rejected()
                        } else {
                            fail(t)
                            history.failed(t)
                        }
                    }
                }
            }
        }
    }

    /**
     * Answers the consent step with what the user chose to share.
     *
     * Releasing nothing — an empty list, or a list whose every claim was unchecked — is a refusal
     * rather than an empty response, which is also how multipaz reads a null selection.
     */
    fun accept(disclosures: List<IosPresentmentDisclosure>) {
        // 🪤 Answered twice, and the second answer used to STRAND THE SCREEN. `pendingConsent` is
        // nulled by the first answer but `pendingData` was not, so a second call still built a
        // selection, set `Sending` — *Please wait…* — and then completed nothing: there was no longer a
        // deferred to hand it to, so nothing was ever sent, and with no timeout on the send the screen
        // waited for ever. Measured on an iPhone 2026-09-08, twice, in the wallet-centric signing flow.
        // So take the deferred first and do nothing at all without one.
        val consent = pendingConsent ?: run {
            Logger.w(TAG, "consent answered with nothing waiting for it; ignoring")
            return
        }
        pendingConsent = null

        val selection = pendingData?.toSelection(disclosures)
        pendingData = null
        if (selection == null || selection.matches.isEmpty()) {
            // Releasing nothing is a refusal, and the deferred still has to hear it.
            consent.complete(null)
            return
        }

        sharedDocuments = selection.matches.map { match ->
            match.credential.document.displayName ?: match.credential.document.identifier
        }.distinct()
        mutableState.value = IosRemotePresentationState.Sending
        consent.complete(selection)
    }

    /** Answers the consent step with a refusal; the verifier is told nothing was shared. */
    fun decline() {
        pendingConsent?.complete(null)
        pendingConsent = null
        pendingData = null
    }

    /**
     * The user declined: tell the verifier before tearing down.
     *
     * Distinct from [cancel] on purpose, and for the same reason Android's
     * `rejectPresentation`/`stopPresentation` are distinct — [cancel] also runs on ordinary teardown,
     * including after a successful send, where an `access_denied` would be a lie.
     *
     * The verifier is told on a best-effort basis and the teardown happens regardless: a user who has
     * declined is finished either way, and multipaz never sent anything at all before this.
     */
    fun reject() {
        tellVerifier(ACCESS_DENIED)
        cancel()
    }

    /** Sends an OpenID4VP error response to the current request's verifier, if it named where to. */
    private fun tellVerifier(error: String) {
        val notice = requestNotice
        if (!notice.canReject) return
        // Its own scope: `cancel()` kills `presentmentJob`, and the POST must outlive that.
        scope.launch {
            val client = HttpClient(Darwin)
            try {
                sendPresentationRejection(notice, client, error)
            } finally {
                client.close()
            }
        }
    }

    /**
     * Whether a trusted list vouches for the certificate chain that signed the request object — the same
     * verdict the consent screen's badge reads ([IosEtsiTrust.trustMetadataFor]). No trust source answers no,
     * so a presenter built without one refuses rather than waves everything through.
     */
    private suspend fun isVerifierTrusted(chain: X509CertChain): Boolean =
        readerTrust?.trustMetadataFor(requesterSignedBy(chain)) != null

    /** Abandons the exchange — the back button, and every teardown. */
    fun cancel() {
        // Its own coroutine, as the exchange's is cancelled below; a row already ended is left as it is,
        // which is what makes the teardown after a successful send harmless here.
        history?.let { ending -> scope.launch { ending.stopped() } }
        history = null
        pendingConsent?.complete(null)
        pendingConsent = null
        pendingData = null
        presentmentJob?.cancel()
        presentmentJob = null
        sharedDocuments = emptyList()
        consentRequests = 0
        mutableState.value = IosRemotePresentationState.Idle
    }

    /**
     * The wallet's answer to "what may be presented, and does the user agree".
     *
     * Both credential kinds are offered from the wallet's own domain: OpenID4VP requests are DCQL, and a
     * DCQL query may name an SD-JWT VC as readily as an mdoc. Leaving `domainsKeyBoundSdJwt` out — as the
     * proximity source does, correctly, since ISO 18013-5 has no SD-JWT — would make every SD-JWT request
     * report "you have nothing this verifier asked for" while the credential sat in the wallet.
     */
    private suspend fun presentmentSource() = walletPresentmentSource(
        store = walletEngine.store(),
        credentialDomain = credentialDomain,
        readerTrust = readerTrust,
        offersSdJwt = true,
        showConsent = { requester, trustedRequesterIdentity, data ->
            awaitConsent(
                requester = requester,
                trustedRequesterIdentity = trustedRequesterIdentity,
                data = data,
            )
        },
    )

    /**
     * Publishes the request and suspends until a screen answers.
     *
     * This is the whole reason the app supplies a presentment source at all: multipaz's default answers
     * immediately, which would release documents without asking anyone.
     */
    private suspend fun awaitConsent(
        requester: Requester,
        trustedRequesterIdentity: TrustedRequesterIdentity?,
        data: ConsentData,
    ): CredentialSelection? {
        val consent = CompletableDeferred<CredentialSelection?>()
        // Counted, because multipaz may ask more than once for one exchange and a repeat looks
        // identical to the user — it is the same screen a second time.
        consentRequests += 1
        Logger.i(TAG, "asking for consent (request $consentRequests of this exchange)")
        pendingConsent = consent
        pendingData = data.credentialQueryResult
        val registration = evaluateRelyingPartyRegistration()
        val name = requesterName(trustedRequesterIdentity?.trustMetadata, requester.certChain)
        history?.requestReceived(name, registration.partyRecord(), data.credentialQueryResult)
        mutableState.value = IosRemotePresentationState.Requesting(
            request = data.credentialQueryResult.toPresentmentRequest(
                // A name without trust behind it is still worth showing. Unlike proximity there is
                // usually *something* here: an OpenID4VP request over a URI scheme must be signed, so
                // the verifier's certificate is present even when nothing vouches for it.
                requesterName = name,
                requesterIsTrusted = trustedRequesterIdentity != null,
                // Read from the request object the observing engine already kept — `verifier_info` is
                // another claim multipaz does not parse, and re-fetching a single-use `request_uri`
                // to get it would risk the exchange.
                relyingPartyRegistration = registration,
            ),
        )

        // Bounded, so a request left unanswered ends in a message rather than a screen that waits for
        // ever. The verifier's own transaction expires on a similar scale.
        val selection = withTimeoutOrNull(CONSENT_TIMEOUT) { consent.await() }
        if (selection != null) {
            history?.presented(selection)
            mutableState.value = IosRemotePresentationState.Sending
        }
        return selection
    }

    /**
     * Turns a multipaz failure into something a screen can show.
     *
     * The message is used only when it is likely to mean anything to a person. That rules out the most
     * common one: `uriSchemePresentment` checks the verifier's HTTP status with a bare `check(...)` and
     * discards the body, so a rejected response arrives as `IllegalStateException("Check failed.")`.
     * Showing either that string or the exception's class name would be worse than a plain sentence.
     * The verifier's own explanation is logged instead — see [PresentationRequestNotice.verifierRefusal].
     */
    private fun rejected(rejection: VerifierRejection) {
        Logger.w(TAG, "the verifier rejected the response: ${requestNotice.verifierRefusal}")
        mutableState.value = IosRemotePresentationState.Rejected(redirectUri = rejection.redirectUri)
    }

    private fun fail(cause: Throwable) {
        Logger.w(TAG, "remote presentation failed: ${cause::class.simpleName}: ${cause.message}")
        requestNotice.verifierRefusal?.let { Logger.w(TAG, "the verifier refused the response: $it") }
        mutableState.value = IosRemotePresentationState.Failed(
            message = cause.message?.takeIf { it.isNotBlank() && it != CHECK_FAILED } ?: SHARING_FAILED
        )
    }

    private companion object {
        const val TAG = "IosRemotePresenter"

        val CONSENT_TIMEOUT = 2.minutes

        const val SHARING_FAILED =
            "Sharing failed. The verifier did not accept the response from this wallet."

        /** What Kotlin's `check(...)` produces with no message. See [fail]. */
        const val CHECK_FAILED = "Check failed."

    }
}

/**
 * The name on the verifier's own certificate, or null when the request carried none.
 *
 * Shown *without* a trust mark, which is the point: an OpenID4VP request over a URI scheme is signed, so
 * a name is nearly always available and nearly always unverified. Naming who asked is more useful to a
 * user than "unknown verifier"; treating that name as an identity is what the trust flag is for.
 *
 * Keyed by **OID**, because that is how `X500Name.components` is keyed — the short forms (`CN`, `O`, …)
 * appear only in its rendered `name`. Asking for `"CN"` compiles, type-checks, and silently returns null
 * for every certificate ever issued, which is exactly what it did here until a live run showed the
 * consent screen naming the verifier "null".
 */
internal fun Requester.certificateCommonName(): String? = certChain.commonName()

/**
 * What the screens call the verifier: the name a trusted list gives it, else its certificate's own. The
 * one rule for consent and for "nothing to share", so the two cannot name the same verifier differently.
 */
internal fun requesterName(trustMetadata: TrustMetadata?, chain: X509CertChain?): String? =
    trustMetadata?.displayName ?: chain.commonName()

/**
 * Who asked, when nothing in the wallet matched: the request's signer as the observing engine kept it,
 * judged by [readerTrust] the way the consent screen's badge is. Naming only — a request whose signer
 * nothing vouches for was refused before this, so an undeterminable verdict here just leaves the badge off.
 */
internal suspend fun nothingToShare(
    notice: PresentationRequestNotice,
    readerTrust: ReaderTrustSource?,
    registration: RelyingPartyRegistrationOutcome,
): IosRemotePresentationState.NothingToShare {
    val chain = notice.requestSignerChain
    val trustMetadata = chain?.let { runCatching { readerTrust?.trustMetadataFor(requesterSignedBy(it)) }.getOrNull() }
    return IosRemotePresentationState.NothingToShare(
        requesterName = requesterName(trustMetadata, chain),
        requesterIsTrusted = trustMetadata != null,
        relyingPartyRegistration = registration,
    )
}

/** The same, for the certificate chain a stored event kept when the `Requester` itself is long gone. */
internal fun X509CertChain?.commonName(): String? =
    this?.certificates?.firstOrNull()?.subject?.components
        ?.get(OID.COMMON_NAME.oid)?.value?.takeIf { it.isNotBlank() }
