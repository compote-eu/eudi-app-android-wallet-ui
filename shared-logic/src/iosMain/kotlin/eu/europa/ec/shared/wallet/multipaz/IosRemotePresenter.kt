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
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.uriSchemePresentment
import org.multipaz.request.Requester
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
        credentialDomain: String = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    ) : this(walletEngine, credentialDomain, scope, IosEtsiTrust())

    private val mutableState =
        MutableStateFlow<IosRemotePresentationState>(IosRemotePresentationState.Idle)
    val state: StateFlow<IosRemotePresentationState> = mutableState.asStateFlow()

    private var presentmentJob: Job? = null
    private var pendingConsent: CompletableDeferred<CredentialPresentmentSelection?>? = null

    /** The request being consented to, kept so [accept] can turn the app's answer back into matches. */
    private var pendingData: CredentialPresentmentData? = null

    /** What the user agreed to share, remembered so the success state can name it. */
    private var sharedDocuments: List<String> = emptyList()

    /**
     * Starts the exchange the verifier's link describes.
     *
     * Returns immediately; the exchange continues in the background and shows up in [state]. The URI is
     * whatever the deep link carried — an `openid4vp:`/`eudi-openid4vp:`/`mdoc-openid4vp:`/`haip-vp:`
     * link, all four of which differ only in scheme, or the `mdoc://` reader-engagement form multipaz
     * also accepts here.
     */
    fun start(uri: String) {
        cancel()
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
                    httpClientEngineFactory = Darwin,
                )
                mutableState.value = IosRemotePresentationState.Sent(
                    sharedDocuments = sharedDocuments,
                    redirectUri = redirect,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Two of multipaz's outcomes are answers rather than errors, and the screens show them
                // differently — the same distinction the proximity presenter makes.
                when (t) {
                    is PresentmentCanceledException -> {
                        // The user declined. Nothing was shared and nothing went wrong.
                        mutableState.value = IosRemotePresentationState.Idle
                    }

                    is PresentmentCannotSatisfyRequestException -> {
                        mutableState.value = IosRemotePresentationState.Failed(
                            message = NOTHING_TO_SHARE
                        )
                    }

                    else -> fail(t)
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
        val selection = pendingData?.toSelection(disclosures)
        if (selection == null || selection.matches.isEmpty()) {
            decline()
            return
        }

        sharedDocuments = selection.matches.map { match ->
            match.credential.document.displayName ?: match.credential.document.identifier
        }.distinct()
        mutableState.value = IosRemotePresentationState.Sending
        pendingConsent?.complete(selection)
        pendingConsent = null
    }

    /** Answers the consent step with a refusal; the verifier is told nothing was shared. */
    fun decline() {
        pendingConsent?.complete(null)
        pendingConsent = null
        pendingData = null
    }

    /** Abandons the exchange — the back button, and every teardown. */
    fun cancel() {
        pendingConsent?.complete(null)
        pendingConsent = null
        pendingData = null
        presentmentJob?.cancel()
        presentmentJob = null
        sharedDocuments = emptyList()
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
        showConsent = { requester, trustMetadata, data ->
            awaitConsent(
                requester = requester,
                trustMetadata = trustMetadata,
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
        trustMetadata: TrustMetadata?,
        data: CredentialPresentmentData,
    ): CredentialPresentmentSelection? {
        val consent = CompletableDeferred<CredentialPresentmentSelection?>()
        pendingConsent = consent
        pendingData = data
        mutableState.value = IosRemotePresentationState.Requesting(
            request = data.toPresentmentRequest(
                // A name without trust behind it is still worth showing. Unlike proximity there is
                // usually *something* here: an OpenID4VP request over a URI scheme must be signed, so
                // the verifier's certificate is present even when nothing vouches for it.
                requesterName = trustMetadata?.displayName ?: requester.certificateCommonName(),
                requesterIsTrusted = trustMetadata != null,
            ),
        )

        // Bounded, so a request left unanswered ends in a message rather than a screen that waits for
        // ever. The verifier's own transaction expires on a similar scale.
        val selection = withTimeoutOrNull(CONSENT_TIMEOUT) { consent.await() }
        if (selection != null) {
            mutableState.value = IosRemotePresentationState.Sending
        }
        return selection
    }

    /**
     * Turns a multipaz failure into something a screen can show.
     *
     * The message is used only when it is likely to mean anything to a person. That rules out the most
     * common one: `uriSchemePresentment` checks the verifier's HTTP status with a bare `check(...)` and
     * discards the body, so a rejected response arrives as `IllegalStateException("Check failed.")` —
     * the verifier's actual explanation never reaches the wallet. Showing either that string or the
     * exception's class name would be worse than a plain sentence. (Worth reporting upstream; the
     * verifier's body is exactly what a user would need.)
     */
    private fun fail(cause: Throwable) {
        Logger.w(TAG, "remote presentation failed: ${cause::class.simpleName}: ${cause.message}")
        mutableState.value = IosRemotePresentationState.Failed(
            message = cause.message?.takeIf { it.isNotBlank() && it != CHECK_FAILED } ?: SHARING_FAILED
        )
    }

    private companion object {
        const val TAG = "IosRemotePresenter"

        val CONSENT_TIMEOUT = 2.minutes

        const val NOTHING_TO_SHARE = "This wallet holds nothing the verifier asked for."

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

/** The same, for the certificate chain a stored event kept when the `Requester` itself is long gone. */
internal fun X509CertChain?.commonName(): String? =
    this?.certificates?.firstOrNull()?.subject?.components
        ?.get(OID.COMMON_NAME.oid)?.value?.takeIf { it.isNotBlank() }
