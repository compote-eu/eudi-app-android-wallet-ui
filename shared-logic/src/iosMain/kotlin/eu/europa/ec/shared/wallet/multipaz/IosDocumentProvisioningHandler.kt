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
    /**
     * The issuer's per-claim display names, filled in by [OpenID4VciCompatibilityEngine] while it reads
     * the metadata. Null leaves claims unnamed and the reader falls back to the identifier.
     */
    private val claimDisplay: IssuerClaimDisplayNotice? = null,
    /**
     * The issuer's advertised reuse policy, filled in by [OpenID4VciCompatibilityEngine] while it reads
     * the metadata. When the issuer published one it decides the batch size and the stored policy;
     * null falls back to [credentialPolicyFor], which decides by format.
     */
    private val reusePolicy: IssuerReusePolicyNotice? = null,
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
        val format = credentialMetadata.format.toStoredFormat()
        // The issuer's advertised batch size wins over the wallet's preference: asking for 20 when the
        // issuer's policy says 7 is what produced a "7/20" counter — 20 requested, 7 ever certified.
        val credentialCount = issuerPolicyFor(format.identifier, credentialMetadata.maxBatchSize)
            ?.numberOfCredentials
            ?: min(credentialMetadata.maxBatchSize, batchSize)

        return documentStore.createDocument(
            displayName = credentialMetadata.display.text,
            typeDisplayName = credentialMetadata.display.text,
            cardArt = credentialMetadata.display.logo,
            issuerLogo = issuerMetadata.display.logo,
            authorizationData = documentAuthorizationData,
            metadata = EudiDocumentMetadata.create(
                documentManagerId = store.documentManagerId,
                format = format,
                // The issuer's own policy when it published one — batch size, kind and threshold all
                // come from `credential_reuse_policy`, as they do on Android. Only an issuer that
                // publishes nothing falls back to deciding by format.
                credentialPolicy = issuerPolicyFor(format.identifier, credentialMetadata.maxBatchSize)
                    ?: credentialPolicyFor(
                        formatType = format.identifier,
                        numberOfCredentials = credentialCount,
                    ),
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
        // The issuer's URL, which is what an identifier is — re-issuance resolves it to find where a
        // document came from. It must not be `display.text`, the issuer's *name*: that matches nothing,
        // because every document would claim to come from "Digital Credentials Issuer".
        credentialIssuerIdentifier = issuerMetadata.url,
        display = listOf(
            IssuerMetadata.Display(name = credentialMetadata.display.text)
        ),
        issuerDisplay = listOf(
            IssuerMetadata.IssuerDisplay(name = issuerMetadata.display.text)
        ),
        // The issuer's own per-claim names, so the details screen can say "Family Name(s)" rather than
        // `family_name`. Joined on doctype/vct because that is the only key both sides have — see
        // [IssuerClaimDisplayNotice]. Null when the issuer published none, which is a display gap and
        // never a wrong value: the reader falls back to the identifier.
        claims = claimDisplay
            ?.claimsByDocumentType
            ?.get(credentialMetadata.format.toStoredFormat().identifier),
    )

    private fun CredentialFormat.toStoredFormat(): StoredDocumentFormat = when (this) {
        is CredentialFormat.Mdoc -> StoredDocumentFormat.MsoMdoc(docType)
        is CredentialFormat.SdJwt -> StoredDocumentFormat.SdJwtVc(vct)
    }

    /**
     * The issuer's policy for this document type, already reduced to the option this wallet supports.
     *
     * Null means the issuer published no `credential_reuse_policy`, or published one whose only options
     * this wallet cannot honour — both of which leave the caller to decide for itself.
     */
    private fun issuerPolicyFor(formatType: String, maxBatchSize: Int) =
        reusePolicy?.policiesByDocumentType?.get(formatType)?.toCredentialPolicy(maxBatchSize)

    /**
     * Settings for one document, taken from the policy stored on it.
     *
     * multipaz asks this per document, which is what lets the issuer's batch size and re-issuance
     * threshold reach the credential-creation path at all — [defaultDocumentProvisioningSettings] is
     * fixed at construction, before any metadata has been read.
     */
    override suspend fun getDocumentProvisioningSettings(
        document: Document,
        credentialMetadata: CredentialMetadata,
        issuerMetadata: ProvisioningMetadata,
    ): DocumentProvisioningSettings =
        document.eudiMetadata?.credentialPolicy
            ?.let { settingsForPolicy(store, it) }
            ?: defaultDocumentProvisioningSettings

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
        /**
         * [settingsFor], with the numbers a stored policy dictates.
         *
         * The mapping is the policy's meaning, not a translation table: a once-only credential is spent
         * by one presentation (`maxUses = 1`), a rotating one survives being presented and is replaced
         * when its remaining lifetime falls below the issuer's threshold, and a limited-time credential
         * is a single rotating one. Anything the policy leaves unsaid keeps multipaz's default.
         */
        internal fun settingsForPolicy(
            store: MultipazWalletStore,
            policy: WalletCredentialPolicy,
        ): DocumentProvisioningSettings {
            val base = settingsFor(store, policy.numberOfCredentials)
            return when (policy) {
                is WalletCredentialPolicy.OnceOnly -> base.copy(keyBoundCredentialMaxUses = 1)

                is WalletCredentialPolicy.RotatingBatch -> base.copy(
                    keyBoundCredentialMaxUses = Int.MAX_VALUE,
                    minValidTime = policy.reissueTriggerLifetimeLeft ?: base.minValidTime,
                )

                is WalletCredentialPolicy.LimitedTime -> base.copy(
                    keyBoundCredentialNumPerDomain = 1,
                    keyBoundCredentialMaxUses = Int.MAX_VALUE,
                    minValidTime = policy.reissueTriggerLifetimeLeft ?: base.minValidTime,
                )
            }
        }

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
