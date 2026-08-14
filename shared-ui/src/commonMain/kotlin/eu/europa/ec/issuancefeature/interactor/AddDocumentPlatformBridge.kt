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
import eu.europa.ec.corelogic.controller.FetchScopedDocumentsPartialState
import eu.europa.ec.corelogic.controller.IssuanceMethod
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow

/**
 * The issuance operations themselves — everything the add-document screen needs that only a platform can
 * do.
 *
 * **Thin on purpose.** Of `AddDocumentInteractorImpl`'s work, only these four calls needed the platform;
 * the rest — grouping configurations by issuer, folding an issuer's PIDs into one "PID combined" entry,
 * sorting, deciding what "no options" means, and building the deferred-issuance success route — is
 * decision-making that both platforms want identically, so it lives in commonMain.
 *
 * Android implements this over wallet-core's `WalletCoreDocumentsController`. iOS cannot yet: OpenID4VCI
 * there means multipaz's `ProvisioningModel`, which works (it issues real PIDs in the issuance spike) but
 * has no configuration source for *which* issuers to offer, so its implementation reports failure rather
 * than appearing to offer something.
 */
interface AddDocumentPlatformBridge {

    /** BCP-47 tag used to pick localized configuration names. */
    fun localeTag(): String

    /** What the configured issuers offer, localized with [locale]. */
    suspend fun getScopedDocuments(locale: String): FetchScopedDocumentsPartialState

    /**
     * Starts issuance for [configIds] at [issuerId], reporting each step. May emit
     * [IssueDocumentsPartialState.UserAuthRequired], which the caller answers with [handleUserAuth].
     */
    fun issueDocuments(
        issuanceMethod: IssuanceMethod,
        configIds: List<String>,
        issuerId: String,
    ): Flow<IssueDocumentsPartialState>

    /**
     * Raises the device's own authentication prompt, calling back on the outcome.
     *
     * Android routes this through `BiometricPrompt`; iOS's Secure Enclave raises its own dialog when a
     * key is used, so there is nothing to raise separately.
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
