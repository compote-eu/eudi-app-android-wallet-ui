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

package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.dashboardfeature.ui.documents.detail.model.DocumentDetailsDomain
import eu.europa.ec.dashboardfeature.ui.documents.model.DocumentCredentialsInfoUi
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow

/**
 * Everything the document-details screen needs that a platform must produce itself.
 *
 * **Fatter than [DocumentsPlatformBridge], deliberately.** For the *list*, only 4 of 702 lines needed
 * wallet-core, so the mapping was shared. For *details*, the substance is the **claim tree**, and the
 * two platforms build it from genuinely different inputs: Android walks wallet-core's nested
 * `DocumentClaim` structure through `transformPathsToDomainClaims`, while iOS has multipaz's flat
 * `MdocClaim` list. Sharing that construction would mean porting ~460 lines of Android's claim
 * rendering — user-visible and delicate — for no gain, since neither platform could use the other's
 * input shape. So the **model** is shared ([DocumentDetailsDomain], `ClaimDomain`) and the
 * construction is not.
 *
 * That also sidesteps a dependency cycle: `transformPathsToDomainClaims` lives in `:common-feature`,
 * which depends on `:core-logic`, so the Android side of this could never have hung off `WalletEngine`.
 */
interface DocumentDetailsPlatformBridge {

    /**
     * Everything about one document that only the platform can assemble, or null when there is no such
     * issued document.
     */
    suspend fun getDocumentDetails(documentId: String, locale: String): PlatformDocumentDetails?

    /** BCP-47 tag used to pick localized claim and issuer names. */
    fun localeTag(): String

    /**
     * Whether to show the `n/m` credential counter — the same user preference the documents *list*
     * reads through `DocumentsPlatformBridge.showBatchIssuanceCounter`.
     *
     * Asked here rather than applied inside [getDocumentDetails] because both platforms were applying
     * it themselves and iOS had stopped: it returned the counter unconditionally, behind a comment
     * saying iOS "has no preferences store wired up" — untrue since `IosPreferences` landed. Turning the
     * setting off therefore hid the counter in the list but not on this screen, on iOS only. One gate in
     * the shared interactor is what stops that recurring.
     */
    suspend fun showBatchIssuanceCounter(): Boolean

    fun deleteDocument(
        documentId: String,
    ): Flow<DocumentDetailsInteractorDeleteDocumentPartialState>

    /**
     * Re-runs issuance for a document whose credentials are spent or expired. iOS has no issuance path,
     * so its implementation reports a failure rather than appearing to start one.
     */
    fun reIssueDocument(
        documentId: String,
        issuerId: String,
    ): Flow<DocumentDetailsInteractorIssuancePartialState>

    /**
     * Raises the device's own authentication prompt, calling back on the outcome.
     *
     * Android routes this through `BiometricPrompt`; iOS's Secure Enclave raises its own dialog when a
     * key is used, so there is nothing to raise separately and its implementation reports failure.
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

/**
 * The platform-assembled half of the details screen: the claim tree and the dates and issuer strings
 * that come with it.
 *
 * [DocumentDetailsDomain] and `ClaimDomain` are shared models, so everything above this — the screen,
 * the view-model, the transformer to UI items — is common code on both platforms.
 */
data class PlatformDocumentDetails(
    val documentDetailsDomain: DocumentDetailsDomain,
    val issuerName: String?,
    val issuerLogoUri: String?,
    val isExpired: Boolean,
    /** Null when the credential counter is switched off by preference. */
    val credentialsInfo: DocumentCredentialsInfoUi?,
)
