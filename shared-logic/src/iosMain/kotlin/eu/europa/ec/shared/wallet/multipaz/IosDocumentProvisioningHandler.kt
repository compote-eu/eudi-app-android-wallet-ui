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

import eu.europa.ec.shared.wallet.config.iosWalletConfig

import eu.europa.ec.shared.wallet.document.IssuerMetadata
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import kotlinx.io.bytestring.ByteString
import org.multipaz.document.Document
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.CredentialMetadata
import org.multipaz.provisioning.Display
import org.multipaz.provisioning.DocumentProvisioningHandler
import org.multipaz.provisioning.DocumentProvisioningSettings
import org.multipaz.provisioning.ProvisioningMetadata
import kotlin.math.min

/**
 * Creates provisioned documents the way **this wallet's** reader expects to find them.
 *
 * Without this, issuance would appear to work and produce nothing: `MultipazDocumentReader` skips any
 * document whose metadata is not an [EudiDocumentMetadata] — that is how it avoids reading documents
 * belonging to another app sharing the store — so a document created with multipaz's own metadata would
 * be written, certified, and then invisible in the Documents list.
 *
 * Everything about *credentials* is inherited from multipaz's [DocumentProvisioningHandler], which knows
 * how to create and clean up key-bound mdoc and SD-JWT credentials. Only the document-level parts are
 * overridden: which metadata to attach, and when the document counts as issued.
 *
 * The credential *domain* is exactly our `documentManagerId`, because that is the value the read path
 * filters credentials on — `DocumentManagerImpl` creates its credentials in a domain named after the
 * document manager and `IssuedDocument.getCredentials()` keeps only those, and the iOS reader is a port
 * of that. A domain merely *derived* from the id (a prefix, say) is not enough: the credentials would be
 * created and certified, and then counted as zero, so a freshly issued document would render as
 * `0/3` — issued, with nothing to present.
 *
 * That single domain also means asking for **one** batch rather than multipaz's default two. multipaz
 * splits credentials by whether user authentication is required, one domain each, and this wallet's own
 * configuration sets `userAuthenticationRequired = false` on every flavour, so the user-auth half is
 * declined rather than renamed: two batches in one domain would silently halve the batch size
 * (`maxBatchSize / 2` each) and mint Secure Enclave keys nothing here would ever choose to present.
 *
 * Note the metadata hook multipaz offers (`AbstractDocumentMetadataHandler`) is deliberately *not* used:
 * it is handed only the display data, and [EudiDocumentMetadata] needs the credential **format**, which
 * is available here on [CredentialMetadata] and nowhere in that callback.
 */
internal class IosDocumentProvisioningHandler(
    private val store: MultipazWalletStore,
    /**
     * How many credentials to ask for per domain. The issuer's own `maxBatchSize` caps it — both EU dev
     * issuers advertise far more (20 and 100) than a wallet needs per document.
     */
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : DocumentProvisioningHandler(
    secureArea = store.keySecureArea,
    documentStore = store.documentStore,
    // Not used: see the class note. Our metadata is attached in createDocument instead.
    metadataHandler = null,
    defaultDocumentProvisioningSettings = settingsFor(store, batchSize),
) {

    override suspend fun createDocument(
        credentialMetadata: CredentialMetadata,
        issuerMetadata: ProvisioningMetadata,
        documentAuthorizationData: ByteString?,
    ): Document {
        val credentialCount = min(credentialMetadata.maxBatchSize, batchSize)

        return documentStore.createDocument(
            displayName = credentialMetadata.display.text,
            typeDisplayName = credentialMetadata.display.text,
            cardArt = credentialMetadata.display.logo,
            issuerLogo = issuerMetadata.display.logo,
            authorizationData = documentAuthorizationData,
            metadata = EudiDocumentMetadata.create(
                documentManagerId = store.documentManagerId,
                format = credentialMetadata.format.toStoredFormat(),
                // A batch of one is a one-shot credential; more than one is a rotating batch. Android
                // takes this from per-issuer configuration, which iOS has none of yet, so it is derived
                // from what was actually requested rather than invented.
                credentialPolicy = if (credentialCount > 1) {
                    WalletCredentialPolicy.RotatingBatch(numberOfCredentials = credentialCount)
                } else {
                    WalletCredentialPolicy.OnceOnly(numberOfCredentials = 1)
                },
                issuerMetadata = issuerMetadataFrom(credentialMetadata, issuerMetadata),
            ),
        )
    }

    /**
     * Delegates the document update, then stamps `issuedAt` once credentials exist.
     *
     * `issuedAt` is what tells the rest of the wallet a document is issued rather than pending — the
     * same distinction `ApplicationMetadata.issue()` draws on Android — and multipaz calls this after
     * certifying credentials, which is exactly when it becomes true.
     */
    override suspend fun updateDocument(
        document: Document,
        display: Display?,
        documentAuthorizationData: ByteString?,
    ) {
        super.updateDocument(document, display, documentAuthorizationData)

        val metadata = document.eudiMetadata ?: return
        if (metadata.issuedAt == null && document.getCertifiedCredentials().isNotEmpty()) {
            metadata.issue()
            // `edit` is what persists the mutated metadata, as in the fixture.
            document.edit { this.metadata = metadata }
        }
    }

    /**
     * The issuer/document display data, in the shape our reader already renders.
     *
     * Only what multipaz surfaces can be filled in: a name and a logo URI per side. The issuer's richer
     * per-claim display — which the details screen would use for claim titles — is not exposed by
     * `ProvisioningMetadata`, so claims keep showing raw data-element identifiers on iOS, the same
     * limitation the fixture-backed path already has.
     */
    private fun issuerMetadataFrom(
        credentialMetadata: CredentialMetadata,
        issuerMetadata: ProvisioningMetadata,
    ): IssuerMetadata = IssuerMetadata(
        // multipaz surfaces no credential *configuration* id on `CredentialMetadata`, so the format's
        // own identifier stands in. It is shown, never matched on.
        documentConfigurationIdentifier = credentialMetadata.format.formatId,
        // The issuer's URL, which is what an identifier is. This used to be `display.text` — the
        // issuer's *name* — which nothing read until re-issuance needed to find the issuer a document
        // came from, and then matched nothing: every provisioned document claimed to come from
        // "Digital Credentials Issuer". Documents issued before this fix keep the old value and cannot
        // be refreshed; they report an unknown issuer, which is true of them.
        credentialIssuerIdentifier = issuerMetadata.url,
        display = listOf(
            IssuerMetadata.Display(name = credentialMetadata.display.text)
        ),
        issuerDisplay = listOf(
            IssuerMetadata.IssuerDisplay(name = issuerMetadata.display.text)
        ),
    )

    private fun CredentialFormat.toStoredFormat(): StoredDocumentFormat = when (this) {
        is CredentialFormat.Mdoc -> StoredDocumentFormat.MsoMdoc(docType)
        is CredentialFormat.SdJwt -> StoredDocumentFormat.SdJwtVc(vct)
    }

    companion object {
        /**
         * Per build flavour, mirroring Android's `numberOfCredentials` — 60 for `dev`, 10 for `demo`.
         *
         * ⚠️ Still capped by the issuer's own `maxBatchSize` (see [provisioningSettings]), and **each
         * credential is a Secure Enclave key**, which Android's numbers were not chosen for. This was
         * 3 while iOS had no flavours; if issuance becomes slow, this is the first thing to look at.
         */
        internal val DEFAULT_BATCH_SIZE: Int get() = iosWalletConfig.credentialBatchSize

        /**
         * How this wallet provisions credentials — one place, because two callers must agree on it.
         *
         * The handler passes these to multipaz when it provisions, and `IosCredentialIssuer` reads the
         * same values to ask, without provisioning anything, whether a refresh would have work to do.
         * Two copies that drifted would make that question answer for a wallet that does not exist.
         */
        internal fun settingsFor(
            store: MultipazWalletStore,
            batchSize: Int = DEFAULT_BATCH_SIZE,
        ) = DocumentProvisioningSettings(
            keyBoundCredentialNumPerDomain = batchSize,
            // See the class note: one domain, named exactly as the reader expects, and no user-auth
            // batch.
            requestUserAuth = false,
            mdocNoUserAuthDomain = store.documentManagerId,
            sdJwtNoUserAuthDomain = store.documentManagerId,
            sdJwtKeylessDomain = store.documentManagerId,
            // The user-auth domain names are left at multipaz's defaults: with requestUserAuth off
            // nothing is ever created in them, and naming them ours would suggest otherwise.
        )
    }
}
