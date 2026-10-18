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

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.businesslogic.extension.ioDispatcher
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.uilogic.component.IssuerDetailsCardDataUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The shared half of the document-details feature: the state machine over what
 * [DocumentDetailsPlatformBridge] assembles.
 *
 * What is genuinely common — and therefore cannot drift between platforms — is which *document state*
 * the issuer card shows (revoked beats expired beats issued), whether the credential counter appears,
 * and the bookmark round-trip, which already goes through the `WalletEngine` seam on both platforms.
 * The claim tree itself comes from the bridge, because Android and iOS build it from different inputs;
 * see that interface for why.
 */
class DocumentDetailsInteractorImpl(
    private val strings: StringCatalog,
    private val walletEngine: WalletEngine,
    private val platform: DocumentDetailsPlatformBridge,
) : DocumentDetailsInteractor {

    private val genericErrorMsg
        get() = strings[Res.string.generic_error_message]

    override fun getDocumentDetails(
        documentId: String,
        wasIssuerDetailsExpanded: Boolean?,
    ): Flow<DocumentDetailsInteractorPartialState> =
        flow<DocumentDetailsInteractorPartialState> {
            val details = platform.getDocumentDetails(
                documentId = documentId,
                locale = platform.localeTag(),
            )

            if (details == null) {
                emit(DocumentDetailsInteractorPartialState.Failure(error = genericErrorMsg))
                return@flow
            }

            val documentIsRevoked = walletEngine.isDocumentRevoked(documentId)
            val domain = details.documentDetailsDomain

            emit(
                DocumentDetailsInteractorPartialState.Success(
                    issuerDetails = IssuerDetailsCardDataUi(
                        issuerName = details.issuerName,
                        issuerLogo = details.issuerLogoUri,
                        // Revocation outranks expiry: a revoked credential must not read as merely
                        // out of date, because the remedies differ.
                        documentState = when {
                            documentIsRevoked -> IssuerDetailsCardDataUi.DocumentState.Revoked

                            details.isExpired -> IssuerDetailsCardDataUi.DocumentState.Expired(
                                issuanceDate = domain.documentIssuanceDate,
                                expirationDate = domain.documentExpirationDate,
                            )

                            else -> IssuerDetailsCardDataUi.DocumentState.Issued(
                                issuanceDate = domain.documentIssuanceDate,
                                expirationDate = domain.documentExpirationDate,
                            )
                        },
                        isExpanded = wasIssuerDetailsExpanded == true,
                    ),
                    documentDetailsDomain = domain,
                    documentIsBookmarked = walletEngine.isDocumentBookmarked(documentId),
                    // Gated here, not in either platform's `getDocumentDetails` — see
                    // `DocumentDetailsPlatformBridge.showBatchIssuanceCounter`.
                    documentCredentialsInfoUi = details.credentialsInfo
                        ?.takeIf { platform.showBatchIssuanceCounter() },
                )
            )
        }
            // `safeAsync` cannot be used: resolving the generic error message is suspending and its
            // handler is not. This is what that function expands to.
            .flowOn(ioDispatcher)
            .catch { throwable ->
                emit(
                    DocumentDetailsInteractorPartialState.Failure(
                        error = throwable.message ?: genericErrorMsg
                    )
                )
            }

    override fun storeBookmark(
        documentId: String,
    ): Flow<DocumentDetailsInteractorStoreBookmarkPartialState> =
        flow<DocumentDetailsInteractorStoreBookmarkPartialState> {
            walletEngine.storeBookmark(documentId)
            emit(DocumentDetailsInteractorStoreBookmarkPartialState.Success(documentId))
        }
            .flowOn(ioDispatcher)
            .catch { emit(DocumentDetailsInteractorStoreBookmarkPartialState.Failure) }

    override fun deleteBookmark(
        documentId: String,
    ): Flow<DocumentDetailsInteractorDeleteBookmarkPartialState> =
        flow<DocumentDetailsInteractorDeleteBookmarkPartialState> {
            walletEngine.deleteBookmark(documentId)
            emit(DocumentDetailsInteractorDeleteBookmarkPartialState.Success)
        }
            .flowOn(ioDispatcher)
            .catch { emit(DocumentDetailsInteractorDeleteBookmarkPartialState.Failure) }

    //region delegated to the platform — see DocumentDetailsPlatformBridge for why each is not shared

    override fun deleteDocument(
        documentId: String,
    ): Flow<DocumentDetailsInteractorDeleteDocumentPartialState> =
        platform.deleteDocument(documentId)

    override fun reIssueDocument(
        documentId: String,
        issuerId: String,
    ): Flow<DocumentDetailsInteractorIssuancePartialState> =
        platform.reIssueDocument(documentId = documentId, issuerId = issuerId)

    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) = platform.handleUserAuth(
        context = context,
        crypto = crypto,
        notifyOnAuthenticationFailure = notifyOnAuthenticationFailure,
        resultHandler = resultHandler,
    )

    override fun resumeOpenId4VciWithAuthorization(uri: String) =
        platform.resumeOpenId4VciWithAuthorization(uri)

    //endregion
}
