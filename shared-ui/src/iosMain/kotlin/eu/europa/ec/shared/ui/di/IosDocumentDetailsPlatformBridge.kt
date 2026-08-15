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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.businesslogic.util.DAY_MONTH_YEAR_FULL_PATTERN
import eu.europa.ec.businesslogic.util.formatInstant
import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.corelogic.model.toDocumentIdentifier
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorDeleteDocumentPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorIssuancePartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.PlatformDocumentDetails
import eu.europa.ec.dashboardfeature.ui.documents.detail.model.DocumentDetailsDomain
import eu.europa.ec.dashboardfeature.ui.documents.model.DocumentCredentialsInfoUi
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.wallet.WalletDocument
import eu.europa.ec.shared.wallet.multipaz.IosIssuanceProgress
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
import eu.europa.ec.shared.wallet.multipaz.StoredMdocClaim
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * iOS's [DocumentDetailsPlatformBridge], building the claim tree from multipaz's mdoc claims.
 *
 * **Flat, not nested, and that is the current honest limit.** multipaz hands back a flat list of mdoc
 * data elements, so every claim becomes a `ClaimDomain.Primitive` — correct for mdoc, which *is* flat
 * within a namespace. Nesting only arises for SD-JWT VC, whose parsing needs the JVM-only
 * `eudi-lib-jvm-sdjwt-kt`; when that arrives this is where the grouping goes.
 *
 * Claim display titles are the raw data-element identifiers rather than localized names: the localized
 * names live in the issuer metadata's per-claim display, which the engine does not surface yet. Android
 * resolves them through `getLocalizedClaimName`, so this is a known cosmetic gap, not a wrong value.
 */
internal class IosDocumentDetailsPlatformBridge(
    private val engine: IosWalletEngine,
) : DocumentDetailsPlatformBridge {

    override fun localeTag(): String = NSLocale.currentLocale.languageCode

    override suspend fun getDocumentDetails(
        documentId: String,
        locale: String,
    ): PlatformDocumentDetails? {
        val document = engine.getAllDocumentsWithDetails(locale)
            .firstOrNull { it.id == documentId }
            ?: return null

        val claims = engine.getNamespacedClaims(documentId)
        // Both come from the issuer metadata stored at issuance, and both are what re-issuance needs.
        // Empty for a document this wallet did not provision — a seeded fixture — which is also the
        // case `reIssueDocument` refuses.
        val issuer = engine.getIssuerReference(documentId)

        return PlatformDocumentDetails(
            documentDetailsDomain = DocumentDetailsDomain(
                docName = document.name,
                docId = document.id,
                issuerId = issuer?.issuerId.orEmpty(),
                documentConfigId = issuer?.documentConfigId.orEmpty(),
                documentIdentifier = document.formatType.toDocumentIdentifier(),
                documentClaims = claims.toClaimDomains(document.formatType),
                documentIssuanceDate = document.issuedAt
                    ?.formatInstant(DAY_MONTH_YEAR_FULL_PATTERN)
                    .orEmpty(),
                documentExpirationDate = document.expiresAt
                    ?.formatInstant(DAY_MONTH_YEAR_FULL_PATTERN),
            ),
            issuerName = document.issuerName,
            issuerLogoUri = document.issuerLogoUri,
            isExpired = document.isExpired,
            credentialsInfo = document.credentialsInfo(),
        )
    }

    override fun deleteDocument(
        documentId: String,
    ): Flow<DocumentDetailsInteractorDeleteDocumentPartialState> = flow {
        engine.deleteDocument(documentId).fold(
            onSuccess = {
                emit(
                    if (engine.hasAnyDocument()) {
                        DocumentDetailsInteractorDeleteDocumentPartialState.SingleDocumentDeleted
                    } else {
                        DocumentDetailsInteractorDeleteDocumentPartialState.AllDocumentsDeleted
                    }
                )
            },
            onFailure = { throwable ->
                emit(
                    DocumentDetailsInteractorDeleteDocumentPartialState.Failure(
                        errorMessage = throwable.message.orEmpty()
                    )
                )
            },
        )
    }

    /** iOS has no OpenID4VCI implementation, so this fails rather than appearing to start something. */
    /**
     * Tops the document's credentials back up.
     *
     * [issuerId] is accepted and not used: the document records who issued it, and
     * [IosCredentialIssuer.refreshCredentials] reads that rather than trusting the caller — refreshing
     * against an issuer that never knew this document would fail in a more confusing way.
     *
     * **No browser, and no fallback to one.** multipaz keeps the original authorization on the document
     * and refreshes against it silently, which is the case this button exists for. When that is not
     * possible — nothing stored, or the issuer refuses — it says so, because re-authorizing *into an
     * existing document* is not reachable from outside multipaz: `ProvisioningModel.launch` takes the
     * target document but its coroutine context can only be built by a private method whose
     * `ProvisioningEnvironment` is `internal`. Android's `allowAuthorizationFallback = true` has no
     * counterpart here until that changes; see the ledger's upstream list.
     */
    override fun reIssueDocument(
        documentId: String,
        issuerId: String,
    ): Flow<DocumentDetailsInteractorIssuancePartialState> = flow {
        emit(
            when (val progress = engine.refreshCredentials(documentId)) {
                // `Success` carries nothing: the screen reloads the document, and the refresh wrote
                // into the one it already has.
                is IosIssuanceProgress.Issued -> DocumentDetailsInteractorIssuancePartialState.Success

                is IosIssuanceProgress.Failure -> DocumentDetailsInteractorIssuancePartialState.Failure(
                    errorMessage = progress.message,
                )
            }
        )
    }

    /**
     * Reports failure rather than raising a prompt, and that is the right answer rather than a pending
     * one: multipaz's `SecureEnclaveSecureArea` presents the LocalAuthentication dialog *itself* when a
     * key is used, so there is no separate prompt for the app to schedule. Presentation reaches the same
     * conclusion in `IosPresentationInteractors`; re-issuance never gets this far, since
     * [reIssueDocument] refuses first.
     */
    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) {
        resultHandler.onAuthenticationFailure()
    }

    /** No-op: there is no in-flight OpenID4VCI session on iOS to resume. */
    override fun resumeOpenId4VciWithAuthorization(uri: String) = Unit

    /**
     * The `n/m` counter, shown whenever the document reports a batch. Android gates this on a stored
     * preference; iOS has no preferences store wired up, matching the Android default of on.
     */
    private fun WalletDocument.credentialsInfo(): DocumentCredentialsInfoUi? =
        if (initialCredentialsCount > 0) {
            DocumentCredentialsInfoUi(
                availableCredentials = credentialsCount,
                totalCredentials = initialCredentialsCount,
                title = "$credentialsCount/$initialCredentialsCount",
            )
        } else {
            null
        }

    /**
     * One [ClaimDomain.Primitive] per mdoc data element, keyed and pathed by its identifier.
     *
     * `nameSpace` is carried in the path's [ClaimType.MsoMdoc], which is where `ClaimDomain.nameSpace`
     * reads it from — so the model stays exactly as the shared UI expects.
     */
    private fun Map<String, StoredMdocClaim>.toClaimDomains(
        formatType: String,
    ): List<ClaimDomain> = map { (identifier, claim) ->
        ClaimDomain.Primitive(
            key = identifier,
            displayTitle = identifier,
            path = ClaimPathDomain(
                segments = listOf(ClaimPathSegment.Key(identifier)),
                type = ClaimType.MsoMdoc(namespace = claim.nameSpace ?: formatType),
            ),
            value = claim.value,
            isRequired = false,
        )
    }

}
