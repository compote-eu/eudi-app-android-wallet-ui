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

package eu.europa.ec.issuancefeature.interactor

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow

/**
 * A credential offer, as far as this wallet cares: what it contains, who is offering it, and whether a
 * transaction code is needed to accept it.
 *
 * Neutral by necessity. The Android side of this is wallet-core's `Offer`, which comes from the JVM-only
 * OpenID4VCI library and so cannot be named in commonMain — and the *deliberate* consequence is that
 * every judgement about an offer is made here rather than there: whether the code length is acceptable,
 * whether a PID is required, what to do when the issuer publishes no name.
 */
sealed class PlatformOfferResolution {

    /**
     * @param documentNames the offered documents' localized names, in offer order.
     * @param containsPid whether any offered document is a PID, which decides whether an offer may be
     *   accepted by a wallet that has none yet.
     * @param txCodeLength how many characters the issuer's transaction code has, or null when it wants
     *   none. Deliberately not validated here: which lengths this wallet accepts is shared policy.
     * @param txCodeIsNumeric false for a free-text code. Reported rather than judged, for the same reason.
     */
    data class Success(
        val documentNames: List<String>,
        val issuerName: String?,
        val issuerLogoUri: String?,
        val containsPid: Boolean,
        val txCodeLength: Int?,
        val txCodeIsNumeric: Boolean,
    ) : PlatformOfferResolution()

    /** No document in the offer — kept distinct from a failure, since the issuer *did* answer. */
    data class NoDocuments(
        val issuerName: String?,
        val issuerLogoUri: String?,
    ) : PlatformOfferResolution()

    data object IssuerNotTrusted : PlatformOfferResolution()

    data class Failure(val errorMessage: String) : PlatformOfferResolution()
}

/**
 * The offer operations a platform must perform itself.
 *
 * **This bridge owns the resolved offer.** Issuance needs the very object resolution produced — on
 * Android an `Offer` holding the issuer's metadata and credential configurations — and that object cannot
 * cross into shared code. So the platform keeps it, keyed by the offer URI it came from, and
 * [issueResolvedOffer] looks it back up. That is the one piece of state deliberately left on this side of
 * the seam; everything the *screen* shows about an offer is [PlatformOfferResolution].
 */
interface DocumentOfferPlatformBridge {

    /** BCP-47 tag used to pick localized document and issuer names. */
    fun localeTag(): String

    /**
     * Whether this build refuses to hold documents before a PID. Comes from the app's configuration,
     * which is Android-only (`ConfigLogic`), so it is reported here rather than read directly — the rule
     * it feeds ("an offer with no PID is refused unless the wallet already has one") stays shared.
     */
    val forcePidActivation: Boolean

    /** Fetches and remembers the offer at [offerUri]. */
    suspend fun resolveOffer(offerUri: String, locale: String): PlatformOfferResolution

    /**
     * Issues the offer last resolved for [offerUri], with [txCode] when the issuer asked for one.
     *
     * Fails if nothing was resolved for that URI — the flow always resolves first, and inventing a second
     * fetch here would silently accept a *different* offer than the one the user saw.
     */
    fun issueResolvedOffer(offerUri: String, txCode: String?): Flow<IssueDocumentsPartialState>

    /**
     * Raises the device's own authentication prompt, calling back on the outcome. Android routes this
     * through `BiometricPrompt`; iOS's Secure Enclave raises its own dialog when a key is used.
     */
    fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    )

    /** Hands an in-flight OpenID4VCI authorization redirect back to the issuance flow. */
    fun resumeOpenId4VciWithAuthorization(uri: String)
}
