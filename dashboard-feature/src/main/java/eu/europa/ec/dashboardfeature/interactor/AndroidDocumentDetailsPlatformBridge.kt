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

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.controller.storage.PrefKeys
import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.businesslogic.provider.UuidProvider
import eu.europa.ec.commonfeature.interactor.DeviceAuthenticationInteractor
import eu.europa.ec.corelogic.controller.DeleteAllDocumentsPartialState
import eu.europa.ec.corelogic.controller.DeleteDocumentPartialState
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.corelogic.extension.isExpired
import eu.europa.ec.corelogic.extension.localizedIssuerMetadata
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.corelogic.model.toDocumentIdentifier
import eu.europa.ec.dashboardfeature.ui.documents.detail.transformer.DocumentDetailsTransformer
import eu.europa.ec.dashboardfeature.ui.documents.detail.transformer.DocumentDetailsTransformer.createDocumentCredentialsInfoUi
import eu.europa.ec.eudi.wallet.document.DocumentId
import eu.europa.ec.eudi.wallet.document.IssuedDocument
import eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcFormat
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import java.util.Locale
import eu.europa.ec.uilogic.component.IssuerDetailsCardDataUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Android's [DocumentDetailsPlatformBridge]: the claim tree from wallet-core's nested `DocumentClaim`
 * structure, plus deletion, re-issuance and the biometric prompt.
 *
 * These bodies are unchanged from when they lived in `DocumentDetailsInteractorImpl` — only the seam
 * around them moved, so Android's claim rendering is byte-for-byte what it was.
 */
class AndroidDocumentDetailsPlatformBridge(
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
    private val walletEngine: WalletEngine,
    private val deviceAuthenticationInteractor: DeviceAuthenticationInteractor,
    private val resourceProvider: ResourceProvider,
    private val uuidProvider: UuidProvider,
    private val configLogic: ConfigLogic,
    private val prefKeys: PrefKeys,
) : DocumentDetailsPlatformBridge {

    private val genericErrorMsg
        get() = resourceProvider.genericErrorMessage()

    override suspend fun getDocumentDetails(
        documentId: String,
        locale: String,
    ): PlatformDocumentDetails? {
        val issuedDocument = walletCoreDocumentsController
            .getDocumentById(documentId = documentId) as? IssuedDocument
            ?: return null

        val documentDetailsDomain = DocumentDetailsTransformer.transformToDocumentDetailsDomain(
            document = issuedDocument,
            resourceProvider = resourceProvider,
            uuidProvider = uuidProvider,
        ).getOrThrow()

        val userLocale = Locale.forLanguageTag(locale)
        val issuerDisplay = issuedDocument.localizedIssuerMetadata(userLocale)

        return PlatformDocumentDetails(
            documentDetailsDomain = documentDetailsDomain,
            issuerName = issuerDisplay?.name,
            issuerLogoUri = issuerDisplay?.logo?.uri?.toString(),
            isExpired = issuedDocument.isExpired(),
            credentialsInfo = if (prefKeys.getShowBatchIssuanceCounter()) {
                createDocumentCredentialsInfoUi(
                    document = issuedDocument,
                    resourceProvider = resourceProvider,
                )
            } else {
                null
            },
        )
    }

    override fun localeTag(): String = resourceProvider.getLocale().toLanguageTag()

    override fun deleteDocument(
        documentId: DocumentId
    ): Flow<DocumentDetailsInteractorDeleteDocumentPartialState> =
        flow {
            val document = walletCoreDocumentsController.getDocumentById(documentId = documentId)
            val format = document?.format
            val docType = (format as? MsoMdocFormat)?.docType ?: (format as? SdJwtVcFormat)?.vct
            val docIdentifier = docType?.toDocumentIdentifier()

            val shouldDeleteAllDocuments: Boolean = if (configLogic.forcePidActivation
                && (docIdentifier == DocumentIdentifier.MdocPid || docIdentifier == DocumentIdentifier.SdJwtPid)
            ) {

                val allPidDocuments = walletCoreDocumentsController.getAllDocumentsByType(
                    documentIdentifiers = listOf(
                        DocumentIdentifier.MdocPid,
                        DocumentIdentifier.SdJwtPid
                    )
                )

                if (allPidDocuments.count() > 1) {
                    walletEngine.getMainPidDocument()?.id == documentId
                } else {
                    true
                }
            } else {
                false
            }

            if (shouldDeleteAllDocuments) {
                walletCoreDocumentsController.deleteAllDocuments()
                    .map {
                        when (it) {
                            is DeleteAllDocumentsPartialState.Failure -> DocumentDetailsInteractorDeleteDocumentPartialState.Failure(
                                errorMessage = it.errorMessage
                            )

                            is DeleteAllDocumentsPartialState.Success -> DocumentDetailsInteractorDeleteDocumentPartialState.AllDocumentsDeleted
                        }
                    }
            } else {
                walletCoreDocumentsController.deleteDocument(documentId = documentId).map {
                    when (it) {
                        is DeleteDocumentPartialState.Failure -> DocumentDetailsInteractorDeleteDocumentPartialState.Failure(
                            errorMessage = it.errorMessage
                        )

                        is DeleteDocumentPartialState.Success -> DocumentDetailsInteractorDeleteDocumentPartialState.SingleDocumentDeleted
                    }
                }
            }.collect {
                emit(it)
            }
        }.safeAsync {
            DocumentDetailsInteractorDeleteDocumentPartialState.Failure(
                errorMessage = it.localizedMessage ?: genericErrorMsg
            )
        }

    override fun reIssueDocument(
        documentId: String,
        issuerId: String
    ): Flow<DocumentDetailsInteractorIssuancePartialState> = flow {

        walletCoreDocumentsController.reIssueDocument(
            documentId = documentId,
            issuerId = issuerId,
            allowAuthorizationFallback = true
        ).collect { state ->

            val successIds: MutableList<String> = mutableListOf()
            var isDeferred = false
            var issuerNotTrusted = false
            var error: String? = null
            var authenticationData: Pair<BiometricCrypto, DeviceAuthenticationResult>? = null

            when (state) {
                is IssueDocumentsPartialState.DeferredSuccess -> {
                    isDeferred = true
                }

                is IssueDocumentsPartialState.Failure -> {
                    error = state.errorMessage
                }

                is IssueDocumentsPartialState.IssuerNotTrusted -> {
                    issuerNotTrusted = true
                }

                is IssueDocumentsPartialState.PartialSuccess -> {
                    successIds.addAll(state.documentIds)
                }

                is IssueDocumentsPartialState.PartialSuccessWithUntrustedIssuer -> {
                    successIds.addAll(state.issuedDocumentIds)
                }

                is IssueDocumentsPartialState.Success -> {
                    successIds.addAll(state.documentIds)
                }

                is IssueDocumentsPartialState.UserAuthRequired -> {
                    authenticationData = state.crypto to state.resultHandler
                }
            }

            val state = if (issuerNotTrusted) {
                DocumentDetailsInteractorIssuancePartialState.IssuerNotTrusted
            } else if (successIds.isNotEmpty() || isDeferred) {
                DocumentDetailsInteractorIssuancePartialState.Success
            } else if (error != null) {
                DocumentDetailsInteractorIssuancePartialState.Failure(error)
            } else if (authenticationData != null) {
                DocumentDetailsInteractorIssuancePartialState.UserAuthRequired(
                    authenticationData.first,
                    authenticationData.second
                )
            } else {
                DocumentDetailsInteractorIssuancePartialState.Failure(genericErrorMsg)
            }

            emit(state)
        }
    }.safeAsync {
        DocumentDetailsInteractorIssuancePartialState.Failure(
            errorMessage = it.localizedMessage ?: genericErrorMsg
        )
    }

    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult
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
