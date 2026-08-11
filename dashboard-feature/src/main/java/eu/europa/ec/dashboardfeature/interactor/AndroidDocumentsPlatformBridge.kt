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

import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.controller.storage.PrefKeys
import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.corelogic.config.WalletCoreConfig
import eu.europa.ec.corelogic.controller.DeleteDocumentPartialState
import eu.europa.ec.corelogic.controller.IssueDeferredDocumentPartialState
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.model.DeferredDocumentDataDomain
import eu.europa.ec.corelogic.model.DocumentCategories
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.eudi.wallet.document.DocumentId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Android's [DocumentsPlatformBridge]: the parts of the documents feature that genuinely need
 * wallet-core, Android preferences, or the app's build configuration.
 *
 * It translates wallet-core's own partial states into the interactor's before returning, so no
 * wallet-core type ever crosses into commonMain — that translation is why the bridge exists rather than
 * the shared interactor simply taking a controller.
 */
class AndroidDocumentsPlatformBridge(
    private val resourceProvider: ResourceProvider,
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
    private val walletCoreConfig: WalletCoreConfig,
    private val configLogic: ConfigLogic,
    private val prefKeys: PrefKeys,
) : DocumentsPlatformBridge {

    private val genericErrorMsg
        get() = resourceProvider.genericErrorMessage()

    override val documentCategories: DocumentCategories
        get() = walletCoreConfig.documentCategories

    override val forcePidActivation: Boolean
        get() = configLogic.forcePidActivation

    override fun localeTag(): String = resourceProvider.getLocale().toLanguageTag()

    override suspend fun showBatchIssuanceCounter(): Boolean = prefKeys.getShowBatchIssuanceCounter()

    override fun tryIssuingDeferredDocuments(
        deferredDocuments: Map<String, FormatType>,
        dispatcher: CoroutineDispatcher,
    ): Flow<DocumentInteractorRetryIssuingDeferredDocumentsPartialState> = flow {

        val successResults: MutableList<DeferredDocumentDataDomain> = mutableListOf()
        val failedResults: MutableList<DocumentId> = mutableListOf()

        withContext(dispatcher) {
            val allJobs = deferredDocuments.keys.map { deferredDocumentId ->
                async {
                    tryIssuingDeferredDocumentSuspend(deferredDocumentId)
                }
            }

            allJobs.forEach { job ->
                when (val result = job.await()) {
                    is DocumentInteractorRetryIssuingDeferredDocumentPartialState.Failure -> {
                        failedResults.add(result.documentId)
                    }

                    is DocumentInteractorRetryIssuingDeferredDocumentPartialState.Success -> {
                        successResults.add(result.deferredDocumentData)
                    }

                    is DocumentInteractorRetryIssuingDeferredDocumentPartialState.NotReady -> {}

                    is DocumentInteractorRetryIssuingDeferredDocumentPartialState.Expired -> {
                        deleteDocument(result.documentId).lastOrNull()
                    }

                    is DocumentInteractorRetryIssuingDeferredDocumentPartialState.IssuerNotTrusted -> {
                        deleteDocument(result.documentId).lastOrNull()
                    }
                }
            }
        }

        emit(
            DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result(
                successfullyIssuedDeferredDocuments = successResults,
                failedIssuedDeferredDocuments = failedResults
            )
        )

    }.safeAsync {
        DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Failure(
            errorMessage = it.localizedMessage ?: genericErrorMsg
        )
    }

    private suspend fun tryIssuingDeferredDocumentSuspend(
        deferredDocumentId: DocumentId,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): DocumentInteractorRetryIssuingDeferredDocumentPartialState {
        return withContext(dispatcher) {
            walletCoreDocumentsController.issueDeferredDocument(deferredDocumentId)
                .map { result ->
                    when (result) {
                        is IssueDeferredDocumentPartialState.Failed -> {
                            DocumentInteractorRetryIssuingDeferredDocumentPartialState.Failure(
                                documentId = result.documentId,
                                errorMessage = result.errorMessage
                            )
                        }

                        is IssueDeferredDocumentPartialState.Issued -> {
                            DocumentInteractorRetryIssuingDeferredDocumentPartialState.Success(
                                deferredDocumentData = result.deferredDocumentData
                            )
                        }

                        is IssueDeferredDocumentPartialState.NotReady -> {
                            DocumentInteractorRetryIssuingDeferredDocumentPartialState.NotReady(
                                deferredDocumentData = result.deferredDocumentData
                            )
                        }

                        is IssueDeferredDocumentPartialState.Expired -> {
                            DocumentInteractorRetryIssuingDeferredDocumentPartialState.Expired(
                                documentId = result.documentId
                            )
                        }

                        is IssueDeferredDocumentPartialState.IssuerNotTrusted -> {
                            DocumentInteractorRetryIssuingDeferredDocumentPartialState.IssuerNotTrusted(
                                documentId = result.documentId
                            )
                        }
                    }
                }.firstOrNull()
                ?: DocumentInteractorRetryIssuingDeferredDocumentPartialState.Failure(
                    documentId = deferredDocumentId,
                    errorMessage = genericErrorMsg
                )
        }
    }

    override fun deleteDocument(
        documentId: String,
    ): Flow<DocumentInteractorDeleteDocumentPartialState> =
        flow {
            walletCoreDocumentsController.deleteDocument(documentId).collect { response ->
                when (response) {
                    is DeleteDocumentPartialState.Failure -> {
                        emit(
                            DocumentInteractorDeleteDocumentPartialState.Failure(
                                errorMessage = response.errorMessage
                            )
                        )
                    }

                    is DeleteDocumentPartialState.Success -> {
                        if (configLogic.forcePidActivation
                            && walletCoreDocumentsController.getAllDocuments().isEmpty()
                        ) {
                            emit(DocumentInteractorDeleteDocumentPartialState.AllDocumentsDeleted)
                        } else
                            emit(DocumentInteractorDeleteDocumentPartialState.SingleDocumentDeleted)
                    }
                }
            }
        }.safeAsync {
            DocumentInteractorDeleteDocumentPartialState.Failure(
                errorMessage = it.localizedMessage ?: genericErrorMsg
            )
        }
}
