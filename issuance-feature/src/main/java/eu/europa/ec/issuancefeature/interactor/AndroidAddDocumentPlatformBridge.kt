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

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.interactor.DeviceAuthenticationInteractor
import eu.europa.ec.corelogic.controller.FetchScopedDocumentsPartialState
import eu.europa.ec.corelogic.controller.IssuanceMethod
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * Android's issuance: wallet-core all the way down, plus `BiometricPrompt` for the user-present step.
 *
 * The whole of it is delegation — which is the point. Everything that decides *anything* is in the shared
 * [AddDocumentInteractorImpl]; what remains here is the wallet-core call surface, so the two platforms
 * cannot drift on the parts that are theirs to share.
 */
class AndroidAddDocumentPlatformBridge(
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
    private val deviceAuthenticationInteractor: DeviceAuthenticationInteractor,
    private val resourceProvider: ResourceProvider,
) : AddDocumentPlatformBridge {

    override fun localeTag(): String = resourceProvider.getLocale().toLanguageTag()

    override suspend fun getScopedDocuments(locale: String): FetchScopedDocumentsPartialState =
        walletCoreDocumentsController.getScopedDocuments(Locale.forLanguageTag(locale))

    override fun issueDocuments(
        issuanceMethod: IssuanceMethod,
        configIds: List<String>,
        issuerId: String,
    ): Flow<IssueDocumentsPartialState> = walletCoreDocumentsController.issueDocuments(
        issuanceMethod = issuanceMethod,
        configIds = configIds,
        issuerId = issuerId,
    )

    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) {
        when (deviceAuthenticationInteractor.getBiometricsAvailability()) {
            is BiometricsAvailability.CanAuthenticate -> {
                deviceAuthenticationInteractor.authenticateWithBiometrics(
                    context = context,
                    crypto = crypto,
                    notifyOnAuthenticationFailure = notifyOnAuthenticationFailure,
                    resultHandler = resultHandler
                )
            }

            is BiometricsAvailability.NonEnrolled -> {
                deviceAuthenticationInteractor.launchBiometricSystemScreen()
            }

            is BiometricsAvailability.Failure -> {
                resultHandler.onAuthenticationFailure()
            }
        }
    }

    override fun resumeOpenId4VciWithAuthorization(uri: String) {
        walletCoreDocumentsController.resumeOpenId4VciWithAuthorization(uri)
    }
}
