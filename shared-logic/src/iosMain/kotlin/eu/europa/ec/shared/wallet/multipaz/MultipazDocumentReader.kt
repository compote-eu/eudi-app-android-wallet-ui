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

import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.claim.JsonClaim
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.util.Logger
import kotlin.coroutines.cancellation.CancellationException
import eu.europa.ec.shared.wallet.document.StoredCredential
import eu.europa.ec.shared.wallet.document.StoredDocument
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.document.Document
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.revocation.RevocationStatus

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
        // Documents stamped before the policy was decided per format carry the wrong *kind* for PID;
        // see `correctedForFormat`. The stored count is kept — only the kind is app policy.
        policy = metadata.credentialPolicy.correctedForFormat(metadata.format.identifier),
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
 * How this document's revocation status can be checked, straight from the credential the issuer
 * signed: the mdoc MSO's `status` element, which multipaz parses into a [RevocationStatus].
 *
 * Read from the first certified mdoc credential. Every credential in a batch shares one issuer and
 * one status entry, so which one is asked does not matter — and Android reaches the same data through
 * wallet-core's `resolveStatus`, which reads the same MSO.
 *
 * **Both formats.** mdoc keeps its status in the MSO; an SD-JWT VC keeps it in the JWT payload under
 * `status`, and multipaz parses both into the same [RevocationStatus], so nothing above here needs to
 * know which it was.
 *
 * Null when the issuer published no status entry at all, which is the common case for *mdoc* test
 * issuers — but notably **not** for SD-JWT here: `dev.issuer-backend.eudiw.dev` does publish a
 * `status_list` for its SD-JWT PID, so that is the first revocation check on iOS that runs against a
 * real issuer rather than a fixture.
 */
internal suspend fun Document.revocationStatus(): RevocationStatus? {
    val credentials = getCertifiedCredentials()

    credentials.filterIsInstance<MdocCredential>().firstOrNull()?.let { mdoc ->
        return mdoc.mso.revocationStatus
    }
    // An SD-JWT VC keeps its status in the JWT payload rather than an MSO, and multipaz reads it into
    // the same `RevocationStatus` — so the checker above this needs no idea which format it came from.
    return credentials.filterIsInstance<SdJwtVcCredential>().firstOrNull()?.sdJwtRevocationStatus()
}

/**
 * The `status` claim of this SD-JWT VC, or null if there is none or it cannot be read.
 *
 * Reads the body without verifying the signature, which is deliberate and not a shortcut: the value is
 * a *pointer* — an index and a URL — and what makes the answer trustworthy is the signature on the
 * **status list token** that the pointer leads to, which multipaz's checker verifies. Trusting the
 * pointer buys nothing an attacker could not achieve by serving a different list.
 *
 * Guarded for the same reason as claim reading: this runs while the document list is built.
 */
private suspend fun SdJwtVcCredential.sdJwtRevocationStatus(): RevocationStatus? =
    try {
        SdJwt.fromCompactSerialization(issuerProvidedData.decodeToString()).revocationStatus
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Logger.w(READER_TAG, "could not read the SD-JWT status claim for $vct: ${t.message}")
        null
    }

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
 * One stored SD-JWT VC claim: its name and its value, **still as JSON**.
 *
 * The value is deliberately not flattened to a string here, unlike [StoredMdocClaim]: an SD-JWT claim
 * can be an object or an array, and the details screen renders those as nested `ClaimDomain.Group`s.
 * Flattening at this level would throw the structure away before anything could use it.
 */
data class StoredJsonClaim(
    val name: String,
    val value: JsonElement,
)

/**
 * The document's mdoc claims keyed by data-element identifier, each with its namespace.
 *
 * mdoc only — SD-JWT VC documents are read by [readJsonClaims] instead, because the two formats have
 * genuinely different shapes: mdoc data elements are flat within a namespace, SD-JWT claims nest.
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
 * The document's SD-JWT VC claims, top-level, with nested values left as JSON.
 *
 * multipaz returns only the top-level claims by its own design, with any nesting inside each
 * [StoredJsonClaim.value] — so building the tree is this wallet's job, not multipaz's.
 */
internal suspend fun Document.readJsonClaims(): List<StoredJsonClaim> {
    val sdJwt = getCertifiedCredentials()
        .filterIsInstance<SdJwtVcCredential>()
        .firstOrNull()
        ?: return emptyList()

    return sdJwt.jsonClaimsOrEmpty().map { StoredJsonClaim(name = it.claimName, value = it.value) }
}

/**
 * The document's top-level claims, flattened to `identifier -> value`, matching
 * `WalletDocument.claims` on Android (which keys mdoc claims by `dataElementName`).
 *
 * Handles **both** formats. It used to be mdoc-only, on the stated grounds that SD-JWT VC parsing
 * needed the JVM-only `eudi-lib-jvm-sdjwt-kt` — which was wrong: multipaz has carried
 * `org.multipaz.sdjwt` and `SdJwtVcCredential.getClaims` all along, so an SD-JWT document was showing
 * no claims for no reason.
 */
private suspend fun readClaims(
    credentials: List<SecureAreaBoundCredential>,
): Map<String, String> {
    // `getClaims(null)` skips document-type lookups in both branches: the display names it would
    // resolve are not needed here — those come from the issuer's own metadata — only values are.
    credentials.filterIsInstance<MdocCredential>().firstOrNull()?.let { mdoc ->
        return mdoc.getClaims(documentTypeRepository = null)
            .associate { it.dataElementName to it.value.toClaimString() }
    }
    credentials.filterIsInstance<SdJwtVcCredential>().firstOrNull()?.let { sdJwt ->
        return sdJwt.jsonClaimsOrEmpty().associate { it.claimName to it.value.toClaimString() }
    }
    return emptyMap()
}

/**
 * This credential's claims, or none if it cannot be read.
 *
 * **Guarded on purpose.** `getClaims` verifies the SD-JWT against the certificate chain in its own
 * header and *throws* when there is no `x5c` at all ("Only X509-certified keys are supported"). These
 * readers run while the **document list** is being built, so an unreadable credential must cost that
 * document its claims and nothing more — letting it throw would empty the whole list.
 */
private suspend fun SdJwtVcCredential.jsonClaimsOrEmpty(): List<JsonClaim> =
    jsonClaimsUnfiltered().filterNot { it.claimName.isEnvelopeClaim() }

/**
 * Whether this is JWT/SD-JWT VC *envelope* metadata rather than something the issuer attested about the
 * holder.
 *
 * multipaz walks the whole verified payload, so `vct`, `iss` and `cnf` arrive alongside `family_name` —
 * and a details screen listing "cnf" as an attribute is both noise and mildly leaky, since that is the
 * holder key confirmation. Checked against this issuer rather than assumed: its
 * `credential_metadata.claims` declares **31** claims for the SD-JWT PID and **none** of them is an
 * envelope field, so nothing below is a real attribute here.
 */
private fun String.isEnvelopeClaim(): Boolean =
    this in EnvelopeClaims || startsWith("_") || endsWith("#integrity")

/** RFC 7519 registered claims plus SD-JWT VC's own metadata. */
private val EnvelopeClaims = setOf(
    "iss", "sub", "aud", "exp", "nbf", "iat", "jti",
    "vct", "cnf", "status",
)

private suspend fun SdJwtVcCredential.jsonClaimsUnfiltered(): List<JsonClaim> =
    try {
        // `getClaimsImpl`, not `getClaims`: the latter is declared on the concrete
        // `KeyBound`/`Keyless` classes, while the interface this is written against exposes the impl.
        getClaimsImpl(documentTypeRepository = null)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Logger.w(READER_TAG, "could not read SD-JWT claims for $vct: ${t.message}")
        emptyList()
    }

/** The claim's name, which multipaz puts in a single-segment `claimPath`. */
private val JsonClaim.claimName: String
    get() = claimPath.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty()

/** A JSON value as the flat claims map wants it: scalars bare, structures as their JSON text. */
internal fun JsonElement.toClaimString(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> toString()
}

private const val READER_TAG = "MultipazDocumentReader"
