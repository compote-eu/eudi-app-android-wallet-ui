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

/**
 * What the extension hands back to iOS, in a shape Swift can read without touching a multipaz type.
 *
 * Deliberately not [IosDcApiOutcome]: that one carries the reasons apart so the *wallet* can log them,
 * while the extension only needs "here is a response" or "there is not one, and here is why in words".
 * Keeping the two separate is what lets the outcome type keep multipaz's distinctions without those
 * leaking across the Kotlin/Native bridge.
 */
data class IosDcApiResult(
    /** The JSON to return through `context.sendResponse`, or null when nothing is being shared. */
    val responseJson: String?,
    /** Null on success; otherwise a sentence already fit to show. */
    val errorMessage: String?,
    /** True when the user declined, which the extension reports differently from a failure. */
    val declined: Boolean,
) {
    val isSuccess: Boolean get() = responseJson != null
}

/**
 * The extension's whole entry into the wallet: opens the store, answers one request, and nothing else.
 *
 * ## Why a bridge, and why it opens its own store
 *
 * The document-provider extension is a **separate process**. It does not inherit the app's Koin graph,
 * its `IosWalletEngine`, or anything else the app set up at launch — so it cannot reach the wallet
 * through any of the usual singletons and has to construct what it needs itself. That is the same shape
 * the in-house reference uses, and the reason its equivalent has a `create()` rather than a constructor.
 *
 * The store it opens is the app's, because [MultipazWalletStore] now lives in the shared app-group
 * container. If that container is ever unavailable the app falls back to its own private one and this
 * bridge will honestly find an empty wallet — which is why that fallback logs.
 *
 * ## Consent
 *
 * [present] suspends until [onConsent] answers, and it hands over [IosPresentmentRequest] — the same
 * type the wallet's own consent screen already renders for remote and proximity presentation. That is
 * the point: the extension shows the shared Compose screen rather than a second consent UI written in
 * SwiftUI, which had been scoped as this feature's one unavoidable architectural cost and is not one.
 *
 * Returning null from [onConsent] is a refusal. So is returning disclosures that keep nothing: the
 * response is built from exactly the claims the selection carries, so an empty selection would
 * otherwise mean "share a document with no claims in it".
 */
class IosDocumentProviderBridge private constructor(
    private val store: MultipazWalletStore,
) {

    /**
     * Answers one Digital Credentials request.
     *
     * @param protocol the request's `protocol`; iOS's ISO 18013 scene sends `org-iso-mdoc`.
     * @param data the request's `data` field as JSON text.
     * @param origin the requesting website's origin. multipaz binds it into the session transcript,
     *   so it must be what the OS reported and never a placeholder.
     * @param appId `<teamId>.<bundleId>` when a native app is asking, null for the web.
     */
    suspend fun present(
        protocol: String,
        data: String,
        origin: String,
        appId: String? = null,
        onConsent: suspend (IosPresentmentRequest) -> List<IosPresentmentDisclosure>?,
    ): IosDcApiResult {
        val outcome = IosDcApiPresenter(store, store.documentManagerId).present(
            protocol = protocol,
            data = data,
            origin = origin,
            appId = appId,
        ) { requester, trustMetadata, presentmentData ->
            val request = presentmentData.toPresentmentRequest(
                // A name with nothing vouching for it is still worth showing, and is usually all there
                // is: iOS tells the extension the origin, not who owns it.
                requesterName = trustMetadata?.displayName ?: requester.certificateCommonName() ?: origin,
                requesterIsTrusted = trustMetadata != null,
            )
            onConsent(request)?.let { disclosures -> presentmentData.toSelection(disclosures) }
        }

        return when (outcome) {
            is IosDcApiOutcome.Sent -> IosDcApiResult(
                responseJson = outcome.responseJson,
                errorMessage = null,
                declined = false,
            )

            IosDcApiOutcome.Declined -> IosDcApiResult(
                responseJson = null,
                errorMessage = null,
                declined = true,
            )

            IosDcApiOutcome.NothingToShare -> IosDcApiResult(
                responseJson = null,
                errorMessage = NOTHING_TO_SHARE,
                declined = false,
            )

            is IosDcApiOutcome.Failed -> IosDcApiResult(
                responseJson = null,
                errorMessage = outcome.message,
                declined = false,
            )
        }
    }

    companion object {

        /**
         * Opens the wallet the app writes to.
         *
         * Suspending, and therefore `async` from Swift — the store creates its secure area and touches
         * SQLite. The extension has a moment to do this before it must show anything, which is why the
         * app's synchronously-constructible [IosWalletEngine] facade is not needed here.
         */
        suspend fun create(): IosDocumentProviderBridge =
            IosDocumentProviderBridge(MultipazWalletStore.open())

        const val NOTHING_TO_SHARE = "This wallet holds nothing that was asked for."
    }
}
