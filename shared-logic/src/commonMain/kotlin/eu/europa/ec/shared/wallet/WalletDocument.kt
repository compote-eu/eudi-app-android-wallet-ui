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

package eu.europa.ec.shared.wallet

import kotlin.time.Instant

/**
 * Whether a document is fully issued, or still waiting on its issuer.
 *
 * Deliberately the *domain* distinction rather than the UI one: the presentation layer folds
 * revocation and expiry into its own richer state, and adds a `Failed` case the engine cannot know
 * about (it is decided by the app after a deferred-issuance retry).
 */
enum class WalletDocumentIssuanceState {
    /** Signed and usable — wallet-core's `IssuedDocument`. */
    Issued,

    /** Awaiting issuance — wallet-core's `UnsignedDocument` / `DeferredDocument`. */
    Pending,
}

/**
 * App-owned, platform-neutral document model exposed across the [WalletEngine] seam, so
 * shared/presentation code no longer depends on the Android-only wallet-core
 * `Document` / `IssuedDocument` types. Each platform's WalletEngine maps its native document into
 * this type; the model grows field-by-field as consumers migrate.
 *
 * All fields past [id] default to an empty/absent value, so the cheap projections of the seam
 * ([WalletEngine.getAllDocuments], which only needs identity) stay cheap. The document-list fields
 * are populated only by [WalletEngine.getAllDocumentsWithDetails].
 */
data class WalletDocument(
    val id: String,
    /** The document's display name, as supplied by the issuer's metadata. */
    val name: String = "",
    /**
     * The document's format identifier — an mdoc docType or an SD-JWT VC vct. A plain String at
     * this boundary; `eu.europa.ec.corelogic.model.FormatType` is a typealias for exactly this,
     * and `toDocumentIdentifier()` turns it into the app's own identifier type.
     */
    val formatType: String = "",
    val issuanceState: WalletDocumentIssuanceState = WalletDocumentIssuanceState.Issued,
    /**
     * Top-level claim values keyed by claim identifier (the wallet-core `identifierString`),
     * stringified. A first, deliberately-flat claims representation — sufficient for simple
     * by-key lookups; it will gain structure (namespaces / nested claims / typed values) as
     * richer consumers migrate.
     */
    val claims: Map<String, String> = emptyMap(),
    /** When the document was issued; null for a document that is not issued yet. */
    val issuedAt: Instant? = null,
    /**
     * The latest instant the document is valid until, or null when it has no credentials left to
     * ask (e.g. an exhausted once-only batch) — which is *not* the same as being expired.
     */
    val expiresAt: Instant? = null,
    val isExpired: Boolean = false,
    val isRevoked: Boolean = false,
    /** Credentials still available to present. */
    val credentialsCount: Int = 0,
    /** How many credentials the document was issued with, i.e. the batch size. */
    val initialCredentialsCount: Int = 0,
    /**
     * Whether the document is running out of credentials and should be re-issued. The policy that
     * decides this is the engine's, not the caller's — it depends on the credential policy the
     * document was created with.
     */
    val isLowOnCredentials: Boolean = false,
    /**
     * The issuer's display name in the requested locale, or null when the issuer published no
     * metadata for it. Callers supply their own "unknown issuer" wording; the engine does not
     * resolve strings.
     */
    val issuerName: String? = null,
    /** URI of the issuer's logo in the requested locale, or null when there is none. */
    val issuerLogoUri: String? = null,
)
