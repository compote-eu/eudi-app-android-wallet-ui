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

import eu.europa.ec.shared.wallet.document.StoredCredential
import eu.europa.ec.shared.wallet.document.StoredDocument
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.document.Document
import org.multipaz.mdoc.credential.MdocCredential

/**
 * This wallet's metadata on a multipaz document, or null when the document was not written by us —
 * a store built without our metadata factory, or another component sharing the same storage.
 */
internal val Document.eudiMetadata: EudiDocumentMetadata?
    get() = metadata as? EudiDocumentMetadata

/**
 * Reads one multipaz [Document] into the platform-neutral [StoredDocument] the projection consumes —
 * the iOS counterpart of the Android document manager's `IdentityDocument.toDocument()` plus
 * `IssuedDocument`'s accessors, and the only place in the wallet that touches `org.multipaz.document`.
 *
 * @param readClaims whether to parse the document's claims. Off by default because it means decoding
 * each credential's issuer-signed CBOR, and the document *list* does not display claims — Android's
 * list mapper does not read them either. Turn it on for the single-document reads that need them.
 */
internal suspend fun Document.toStoredDocument(readClaims: Boolean = false): StoredDocument? {
    // A document written by another app (or by a store built without our factory) has metadata we
    // cannot interpret; skip it rather than failing the whole list.
    val metadata = eudiMetadata ?: return null

    val credentials = getCertifiedCredentials().filterIsInstance<SecureAreaBoundCredential>()

    return StoredDocument(
        id = identifier,
        // multipaz's own displayName, set at creation; fall back to the format identifier exactly as
        // the Android `IdentityDocument.documentName` extension does.
        name = displayName ?: metadata.format.identifier,
        formatType = metadata.format.identifier,
        documentManagerId = metadata.documentManagerId,
        policy = metadata.credentialPolicy,
        issuedAt = metadata.issuedAt,
        claims = if (readClaims) readClaims(credentials) else emptyMap(),
        certifiedCredentials = credentials.map { it.toStoredCredential() },
        issuerMetadata = metadata.issuerMetadata,
    )
}

private suspend fun SecureAreaBoundCredential.toStoredCredential() = StoredCredential(
    alias = alias,
    domain = domain,
    usageCount = usageCount,
    validFrom = validFrom,
    validUntil = validUntil,
    // 🚩 ACCEPTED DIVERGENCE: on iOS `SecureEnclaveSecureArea.getKeyInvalidated` returns a hardcoded
    // `false`, so this is currently always false and the invalidated-credential filter is a no-op.
    // Consequences: credential counts can over-report, and a document whose secure-enclave key is
    // gone stays listed and fails at *signing* time instead of disappearing from the list.
    //
    // Accepted rather than worked around, deliberately. Answering the question honestly on iOS means
    // probing the Secure Enclave per credential, which can raise a LocalAuthentication prompt — far
    // too expensive for rendering a list, and the wrong place to ask. The gap is upstream in multipaz
    // and belongs there; calling `isInvalidated()` anyway means iOS inherits the correct behaviour
    // the moment multipaz implements it, with no change here.
    isInvalidated = isInvalidated(),
)

/**
 * One stored mdoc data element: its namespace and its rendered value. Richer than the flat
 * `WalletDocument.claims` map, which drops the namespace — the details screen needs it, because
 * `ClaimDomain` carries the namespace in its path.
 */
data class StoredMdocClaim(
    val nameSpace: String?,
    val value: String,
)

/**
 * The document's mdoc claims keyed by data-element identifier, each with its namespace.
 *
 * mdoc only, like [readClaims], and for the same reason: SD-JWT VC parsing needs the JVM-only
 * `eudi-lib-jvm-sdjwt-kt`.
 */
internal suspend fun Document.readNamespacedClaims(): Map<String, StoredMdocClaim> {
    val mdoc = getCertifiedCredentials()
        .filterIsInstance<SecureAreaBoundCredential>()
        .filterIsInstance<MdocCredential>()
        .firstOrNull()
        ?: return emptyMap()

    return mdoc.getClaims(documentTypeRepository = null).associate { claim ->
        claim.dataElementName to StoredMdocClaim(
            nameSpace = claim.namespaceName,
            value = claim.value.toClaimString(),
        )
    }
}

/**
 * The document's top-level claims, flattened to `identifier -> value` across namespaces, matching
 * `WalletDocument.claims` on Android (which keys mdoc claims by `dataElementName`).
 *
 * **mdoc only for this milestone.** An SD-JWT VC credential yields no claims here, because parsing
 * one needs `eudi-lib-jvm-sdjwt-kt`, which has no iOS artifact. Everything *else* about an SD-JWT
 * document — name, format, credential counts, validity, issuer display — reads fine, so such a
 * document still appears correctly in the list; only its claims are absent.
 */
private suspend fun readClaims(
    credentials: List<SecureAreaBoundCredential>,
): Map<String, String> {
    val mdoc = credentials.filterIsInstance<MdocCredential>().firstOrNull() ?: return emptyMap()

    // `getClaims(null)` skips document-type lookups: the display names it would resolve are not
    // needed here, only the identifiers and values.
    return mdoc.getClaims(documentTypeRepository = null)
        .associate { it.dataElementName to it.value.toClaimString() }
}
