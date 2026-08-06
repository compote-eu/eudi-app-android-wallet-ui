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

package eu.europa.ec.corelogic.wallet

import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.extension.getExpiryDate
import eu.europa.ec.corelogic.extension.identifierString
import eu.europa.ec.corelogic.extension.isExpired
import eu.europa.ec.corelogic.extension.localizedIssuerMetadata
import eu.europa.ec.eudi.wallet.document.Document
import eu.europa.ec.eudi.wallet.document.IssuedDocument
import eu.europa.ec.eudi.wallet.document.UnsignedDocument
import eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcFormat
import eu.europa.ec.shared.wallet.WalletDocument
import eu.europa.ec.shared.wallet.WalletDocumentIssuanceState
import eu.europa.ec.shared.wallet.WalletEngine
import java.util.Locale
import kotlin.time.toKotlinInstant

/**
 * Android [WalletEngine] implementation. It delegates to the existing wallet-core controller,
 * so the seam can be adopted incrementally without touching wallet-core wiring. As more
 * capabilities move behind [WalletEngine], consumers stop depending on the controller directly.
 *
 * This file is also where the wallet-core → [WalletDocument] mapping lives, which is the point of
 * the seam: `eu.europa.ec.eudi.wallet.document.*` appears here and nowhere above it.
 */
class WalletEngineImpl(
    private val documentsController: WalletCoreDocumentsController,
) : WalletEngine {

    override suspend fun getRevokedDocumentIds(): List<String> =
        documentsController.getRevokedDocumentIds()

    override suspend fun isDocumentRevoked(documentId: String): Boolean =
        documentsController.isDocumentRevoked(documentId)

    override suspend fun isDocumentBookmarked(documentId: String): Boolean =
        documentsController.isDocumentBookmarked(documentId)

    override suspend fun storeBookmark(bookmarkId: String) =
        documentsController.storeBookmark(bookmarkId)

    override suspend fun deleteBookmark(bookmarkId: String) =
        documentsController.deleteBookmark(bookmarkId)

    override fun getMainPidDocument(): WalletDocument? =
        documentsController.getMainPidDocument()?.let { doc ->
            WalletDocument(
                id = doc.id,
                claims = doc.data.claims.associate {
                    it.identifierString to it.value?.toString().orEmpty()
                },
            )
        }

    override fun getAllDocuments(): List<WalletDocument> =
        documentsController.getAllDocuments().map { WalletDocument(id = it.id) }

    override suspend fun getAllDocumentsWithDetails(locale: String): List<WalletDocument> {
        val userLocale = Locale.forLanguageTag(locale)
        return documentsController.getAllDocuments().map { document ->
            document.toWalletDocument(userLocale)
        }
    }

    private suspend fun Document.toWalletDocument(userLocale: Locale): WalletDocument {
        val issuerDisplay = localizedIssuerMetadata(userLocale)
        val base = WalletDocument(
            id = id,
            name = name,
            formatType = when (val f = format) {
                is MsoMdocFormat -> f.docType
                is SdJwtVcFormat -> f.vct
            },
            isRevoked = documentsController.isDocumentRevoked(id),
            issuerName = issuerDisplay?.name,
            issuerLogoUri = issuerDisplay?.logo?.uri?.toString(),
        )

        return when (this) {
            is IssuedDocument -> base.copy(
                issuanceState = WalletDocumentIssuanceState.Issued,
                issuedAt = issuedAt.toKotlinInstant(),
                expiresAt = getExpiryDate(),
                isExpired = isExpired(),
                credentialsCount = credentialsCount(),
                initialCredentialsCount = initialCredentialsCount(),
                isLowOnCredentials = documentsController.isDocumentLowOnCredentials(this),
            )

            // DeferredDocument is an UnsignedDocument, so this covers both. Nothing credential- or
            // validity-related is knowable yet, which is why those fields keep their absent
            // defaults rather than being faked to zero-with-meaning.
            is UnsignedDocument -> base.copy(
                issuanceState = WalletDocumentIssuanceState.Pending,
            )
        }
    }
}
