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

import eu.europa.ec.shared.wallet.platform.IosRegistrationCheckSetting
import eu.europa.ec.shared.wallet.trust.IosEtsiTrust
import eu.europa.ec.shared.wallet.trust.ReaderTrustSource
import eu.europa.ec.shared.wallet.trust.certChain
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.presentment.ConsentData
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
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

    /**
     * Blocked before consent: the verifier authenticated, and nothing this wallet trusts vouches for its
     * certificate, or the trust lists could not say ([isUntrustedReader]). Nothing was shown or built.
     */
    data object VerifierNotTrusted : IosDcApiOutcome

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
 * our pin, with its own tests, and it is the exact sibling of `uriSchemePresentment` — which
 * [IosRemotePresenter] has driven in production against the EUDI dev verifier since `8f4751dd`. So this
 * class is that presenter with the protocol function swapped: same [SimplePresentmentSource], same
 * consent seam, same three-way reading of the outcome. Its `org-iso-mdoc` branch is our copy now
 * ([iosDigitalCredentialsPresentment]), so a request is matched the way Android matches it.
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
    /**
     * The user's registration-check setting, read where the app writes it — the app group, which this
     * process can see ([IosRegistrationCheckSetting]).
     */
    private val isRegistrationCheckEnabled: suspend () -> Boolean = { IosRegistrationCheckSetting.isEnabled() },
) {

    /**
     * Runs one request end to end and returns what to hand back to iOS.
     *
     * @param protocol the `protocol` field of the request; `org-iso-mdoc` is what iOS sends for
     *   [org.multipaz.digitalcredentials.DigitalCredentials]' ISO 18013 scene, and the only one
     *   registration advertises.
     * @param data the request's `data` field, as JSON text — the form multipaz's string overload of
     *   `digitalCredentialsPresentment` takes "for interoperability with Swift", which is exactly the
     *   boundary this crosses. [iosDigitalCredentialsPresentment] keeps it, and answers `org-iso-mdoc`
     *   with the matching Android uses.
     * @param origin the requesting website's origin, or the app id for a native requester. Passed
     *   through unchanged: multipaz binds it into the session transcript, so inventing a value here
     *   would produce a response the verifier cannot validate.
     * @param appId `<teamId>.<bundleId>` when a native app is asking, null for the web.
     * @param onConsent the wallet's answer, given what the reader's registration says (see
     *   [readerRegistrationOutcome]). Returning null — or a selection with no matches — is a refusal,
     *   which is how multipaz reads it too.
     */
    suspend fun present(
        protocol: String,
        data: String,
        origin: String,
        appId: String? = null,
        onConsent: suspend (
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
            data: ConsentData,
            registration: RelyingPartyRegistrationOutcome,
        ) -> CredentialSelection?,
    ): IosDcApiOutcome {
        var shared: List<String> = emptyList()
        var deviceRequest: DeviceRequest? = null
        // This runs in the document-provider extension, whose store is the app's, in the shared group.
        val history = IosPresentationLog { store }

        return try {
            // Nothing is preselected: iOS's picker preselects nothing that reaches us here. Android's
            // Credential Manager can, which is why multipaz has the parameter at all.
            val responseJson = iosDigitalCredentialsPresentment(
                protocol = protocol,
                data = data,
                appId = appId,
                origin = origin,
                onDeviceRequest = { deviceRequest = it },
                source = presentmentSource { requester, trustedRequesterIdentity, consentData ->
                    // Before [onConsent], so the extension never shows the request.
                    if (isUntrustedReader(requester, trustedRequesterIdentity)) throw UntrustedVerifierException()
                    // Two different certificates throw here and fail the request, as on Android.
                    val registration = readerRegistrationOutcome(
                        isRegistrationCheckEnabled = isRegistrationCheckEnabled,
                        deviceRequest = deviceRequest,
                        readerTrust = readerTrust,
                        reader = requester.certChain?.certificates?.firstOrNull(),
                    )

                    history.requestReceived(
                        requesterName = requesterName(trustedRequesterIdentity?.trustMetadata, requester.certChain)
                            ?: origin,
                        party = registration.partyRecord(),
                        request = consentData.credentialQueryResult,
                    )
                    onConsent(requester, trustedRequesterIdentity, consentData, registration)?.also { selection ->
                        history.presented(selection)
                        shared = selection.matches
                            .map { it.credential.document.localizedName() ?: it.credential.document.identifier }
                            .distinct()
                    }
                },
            )
            // Handed back to iOS, which delivers it: wallet-core's DC API success (`IntentToSend`).
            history.completed()
            IosDcApiOutcome.Sent(responseJson = responseJson, sharedDocuments = shared)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { history.stopped() }
            throw cancelled
        } catch (canceled: PresentmentCanceledException) {
            Logger.i(TAG, "declined by the user")
            history.stopped()
            IosDcApiOutcome.Declined
        } catch (unsatisfiable: PresentmentCannotSatisfyRequestException) {
            Logger.i(TAG, "nothing matches: ${unsatisfiable.message}")
            history.nothingToShare(requesterName = origin, party = null)
            IosDcApiOutcome.NothingToShare
        } catch (refused: UntrustedVerifierException) {
            // Refused before consent, so no request was ever shown and there is no row to end.
            Logger.w(TAG, "blocked: ${refused.message}")
            IosDcApiOutcome.VerifierNotTrusted
        } catch (failure: Throwable) {
            Logger.w(TAG, "presentment failed: ${failure::class.simpleName}: ${failure.message}")
            history.failed(failure)
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
     * The History row is [IosPresentationLog]'s, written from this process into the app's store.
     */
    private suspend fun presentmentSource(
        showConsent: suspend (
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
            data: ConsentData,
        ) -> CredentialSelection?,
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
