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
import kotlinx.coroutines.CancellationException
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.digitalCredentialsPresentment
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.Logger

/**
 * What a Digital Credentials API presentment ended as.
 *
 * Modelled on [IosRemotePresentationState]'s distinctions rather than on a bare success/failure, and for
 * the same reason: two of multipaz's outcomes are *answers*, not errors. A user who declines and a
 * wallet that holds nothing the verifier asked for are both correct endings, and the extension shows
 * them differently from a failure.
 */
sealed interface IosDcApiOutcome {

    /** The response the extension hands back to iOS, plus what it named for the transaction log. */
    data class Sent(
        val responseJson: String,
        val sharedDocuments: List<String>,
    ) : IosDcApiOutcome

    /** The user said no. Nothing was shared and nothing went wrong. */
    data object Declined : IosDcApiOutcome

    /** The wallet holds nothing this verifier asked for. */
    data object NothingToShare : IosDcApiOutcome

    /** Anything else, with a message already fit to show. */
    data class Failed(val message: String) : IosDcApiOutcome
}

/**
 * Answers a W3C Digital Credentials API request — the **responder** half of being an iOS credential
 * provider, and the counterpart to [registrableDocuments], which is the half that tells iOS what exists.
 *
 * ## Why this is small, and what that says about the earlier scoping
 *
 * **multipaz already implements the protocol.** `digitalCredentialsPresentment` is in `commonMain` at
 * our 0.99.0 pin, with its own tests, and it is the exact sibling of `uriSchemePresentment` — which
 * [IosRemotePresenter] has driven in production against the EUDI dev verifier since `8f4751dd`. So this
 * class is that presenter with the protocol function swapped: same [SimplePresentmentSource], same
 * consent seam, same three-way reading of the outcome.
 *
 * ⚠️ **Do not confuse this with `DigitalCredentials.defaultRequest`, which throws on iOS.** That is the
 * *relying-party* direction — a wallet asking someone else for credentials. It says nothing about
 * answering a request, and citing it as the blocker for being a provider was wrong twice over.
 *
 * ⚠️ **The official iOS wallet's `DcApiHandler` is not reusable here**, whatever its name suggests: it
 * is constructed from a Keychain `serviceName` + `accessGroup` and reads Wallet Kit's storage layout.
 * Our documents are in a multipaz SQLite store, so it would find nothing.
 *
 * ## What it deliberately does not do
 *
 * No UI, no OS handshake, and no store of its own. The extension supplies the store — it runs in its own
 * process and opens the wallet from a shared app-group container — and supplies consent. Keeping those
 * out is what lets this be tested with seeded documents and no device, which matters more here than
 * usual: everything downstream of it needs hardware.
 *
 * `internal` because [MultipazWalletStore] is. The extension will reach this through a bridge that opens
 * the store itself — the shape the in-house reference uses — so widening the store's visibility now
 * would be for a caller that does not exist yet.
 */
internal class IosDcApiPresenter(
    private val store: MultipazWalletStore,
    /** Where the wallet's own credentials live; anything else in the store is not offered. */
    private val credentialDomain: String = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
    /**
     * Who the verifier is. Null answers "unknown" without asking anyone, which is what the tests
     * want; this class is already `internal`, so unlike the other two presenters it needs no
     * constructor split to keep multipaz types off the Swift-facing API.
     */
    private val readerTrust: ReaderTrustSource? = IosEtsiTrust(),
) {

    /**
     * Runs one request end to end and returns what to hand back to iOS.
     *
     * @param protocol the `protocol` field of the request; `org-iso-mdoc` is what iOS sends for
     *   [org.multipaz.digitalcredentials.DigitalCredentials]' ISO 18013 scene, and the only one
     *   registration advertises.
     * @param data the request's `data` field, as JSON text. The *string* overload of
     *   `digitalCredentialsPresentment` is used on purpose — multipaz added it "for interoperability
     *   with Swift", which is exactly the boundary this crosses.
     * @param origin the requesting website's origin, or the app id for a native requester. Passed
     *   through unchanged: multipaz binds it into the session transcript, so inventing a value here
     *   would produce a response the verifier cannot validate.
     * @param appId `<teamId>.<bundleId>` when a native app is asking, null for the web.
     * @param onConsent the wallet's answer. Returning null — or a selection with no matches — is a
     *   refusal, which is how multipaz reads it too.
     */
    suspend fun present(
        protocol: String,
        data: String,
        origin: String,
        appId: String? = null,
        onConsent: suspend (
            requester: Requester,
            trustMetadata: TrustMetadata?,
            data: CredentialPresentmentData,
        ) -> CredentialPresentmentSelection?,
    ): IosDcApiOutcome {
        var shared: List<String> = emptyList()

        return try {
            val responseJson = digitalCredentialsPresentment(
                protocol = protocol,
                data = data,
                appId = appId,
                origin = origin,
                // Empty: iOS's picker preselects nothing that reaches us here. Android's Credential
                // Manager can, which is why the parameter exists at all.
                preselectedDocuments = emptyList(),
                source = presentmentSource { requester, trustMetadata, presentmentData ->
                    onConsent(requester, trustMetadata, presentmentData)?.also { selection ->
                        shared = selection.matches
                            .map { it.credential.document.displayName ?: it.credential.document.identifier }
                            .distinct()
                    }
                },
            )
            IosDcApiOutcome.Sent(responseJson = responseJson, sharedDocuments = shared)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (canceled: PresentmentCanceledException) {
            Logger.i(TAG, "declined by the user")
            IosDcApiOutcome.Declined
        } catch (unsatisfiable: PresentmentCannotSatisfyRequestException) {
            Logger.i(TAG, "nothing matches: ${unsatisfiable.message}")
            IosDcApiOutcome.NothingToShare
        } catch (failure: Throwable) {
            Logger.w(TAG, "presentment failed: ${failure::class.simpleName}: ${failure.message}")
            IosDcApiOutcome.Failed(
                message = failure.message?.takeIf { it.isNotBlank() && it != CHECK_FAILED }
                    ?: SHARING_FAILED,
            )
        }
    }

    /**
     * The wallet's answer to "what may be presented, and does the user agree".
     *
     * Both credential kinds are offered, exactly as [IosRemotePresenter] does and for the same reason:
     * a DC API request is DCQL, and a DCQL query may name an SD-JWT VC as readily as an mdoc.
     *
     * `eventLogger` is supplied so a successful exchange reaches the History tab — multipaz writes the
     * event itself once the response is out, so passing the logger *is* the whole write side. That it
     * works from a second process is one of the things the device run has to confirm.
     */
    private suspend fun presentmentSource(
        showConsent: suspend (
            requester: Requester,
            trustMetadata: TrustMetadata?,
            data: CredentialPresentmentData,
        ) -> CredentialPresentmentSelection?,
    ) = walletPresentmentSource(
        store = store,
        credentialDomain = credentialDomain,
        readerTrust = readerTrust,
        offersSdJwt = true,
        showConsent = showConsent,
    )

    private companion object {
        const val TAG = "IosDcApiPresenter"

        /** What Kotlin's `check(...)` produces with no message; useless to a person. */
        const val CHECK_FAILED = "Check failed."

        const val SHARING_FAILED = "Sharing failed. The request could not be answered."

    }
}
