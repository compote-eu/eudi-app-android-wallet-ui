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

// What a verifier asked for and what the user agreed to release — in the app's own vocabulary rather
// than multipaz's, and deliberately without a protocol in it.
//
// This started as the proximity presenter's private half. It is shared now because remote
// presentation needs exactly the same translation: multipaz reduces both ISO 18013-5 and OpenID4VP to
// the *same* `CredentialPresentmentData` before asking for consent, so a consent step written against
// that data is protocol-neutral for free. Duplicating it per protocol would have meant two copies of
// the cross product and two copies of the claim round trip — and a claim whose ref no longer matches
// its path is a claim the user ticks and the wallet never sends.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.ClaimType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.multipaz.claim.Claim
import org.multipaz.claim.MdocClaim
import org.multipaz.presentment.CredentialMatchSourceOpenID4VP
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.CredentialPresentmentSetOption
import org.multipaz.presentment.CredentialPresentmentSetOptionMemberMatch
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim

/** multipaz's name for "one credential that could answer one part of the request", used a lot below. */
internal typealias MemberMatch = CredentialPresentmentSetOptionMemberMatch

/**
 * What a verifier asked for, as a consent screen needs to show it.
 *
 * [combinations] are the *alternative* ways to satisfy the request — a different document for the same
 * requirement, or a different credential of the same document. Usually there is exactly one; a wallet
 * holding two PIDs when a verifier asks for one produces two, and the screen lets the user pick.
 */
data class IosPresentmentRequest(
    /** The verifier's name if its certificate is trusted, null when it is unknown. */
    val requesterName: String?,
    val requesterIsTrusted: Boolean,
    val combinations: List<Combination>,
) {
    data class Combination(val documents: List<RequestedDocument>)

    /**
     * [documentId] and [credentialId] together name the credential that would answer, and are what
     * [IosPresentmentDisclosure] hands back — so the app never has to hold a multipaz object to say yes.
     */
    data class RequestedDocument(
        val documentId: String,
        val credentialId: String,
        val documentName: String,
        val docType: String,
        val format: IosPresentmentFormat,
        /**
         * The DCQL credential-query id this document answers, or null under ISO 18013-5, which has no
         * query ids. Carried because one DCQL query may ask for the same document type twice: the id is
         * what keeps the two apart, both in the screen's row ids and in the response.
         */
        val queryId: String?,
        val claims: List<RequestedClaimInfo>,
    )

    data class RequestedClaimInfo(
        val claim: ClaimPathDomain,
        val displayName: String,
        /** The stored value, so consent shows what would actually leave the wallet. */
        val value: String,
        /** What the verifier says it will *keep* — shown to the user, never a reason to force-share. */
        val intentToRetain: Boolean,
    )
}

/** Which of the two credential formats a requested document is in. */
enum class IosPresentmentFormat { MsoMdoc, SdJwtVc }

/**
 * What the user agreed to release: for one credential, the claims kept.
 *
 * Anything absent stays home — dropping a document from the list refuses it entirely, and dropping a
 * claim from [claims] narrows the response to the rest, because multipaz builds the response from
 * exactly the claims the selection carries.
 */
data class IosPresentmentDisclosure(
    val documentId: String,
    val credentialId: String,
    val claims: Set<ClaimPathDomain>,
)

// The consent step's two translations, deliberately at file level: neither touches a presenter's state,
// and out here they can be exercised without a radio or a verifier — which is the only way anything
// about presentment gets tested on this platform.

/** multipaz's view of a request, as a consent screen needs to see it. */
internal fun CredentialPresentmentData.toPresentmentRequest(
    requesterName: String?,
    requesterIsTrusted: Boolean,
): IosPresentmentRequest = IosPresentmentRequest(
    requesterName = requesterName,
    requesterIsTrusted = requesterIsTrusted,
    combinations = combinationsOfMatches().map { matches ->
        IosPresentmentRequest.Combination(documents = matches.map { it.toRequestedDocument() })
    },
)

/**
 * The app's answer turned back into multipaz's matches, narrowed to the claims the user kept.
 *
 * The response is built from `match.claims.keys`, so dropping entries here is what makes selective
 * disclosure real rather than cosmetic. A credential nobody chose, or one whose every claim was
 * unchecked, is left out entirely.
 */
internal fun CredentialPresentmentData.toSelection(
    disclosures: List<IosPresentmentDisclosure>,
): CredentialPresentmentSelection {
    val wanted = disclosures.associateBy { it.documentId to it.credentialId }

    val matches = combinationsOfMatches().flatten()
        .distinctBy { it.credential.document.identifier to it.credential.identifier }
        .mapNotNull { match ->
            val disclosure = wanted[
                match.credential.document.identifier to match.credential.identifier
            ] ?: return@mapNotNull null

            val keptClaims = match.claims.filter { (requested, claim) ->
                claimPath(requested, claim) in disclosure.claims
            }
            if (keptClaims.isEmpty()) null else match.copy(claims = keptClaims)
        }

    return CredentialPresentmentSelection(matches = matches)
}

private fun MemberMatch.toRequestedDocument(): IosPresentmentRequest.RequestedDocument {
    val document = credential.document
    return IosPresentmentRequest.RequestedDocument(
        documentId = document.identifier,
        credentialId = credential.identifier,
        documentName = document.displayName ?: document.identifier,
        docType = document.eudiMetadata?.format?.identifier.orEmpty(),
        format = when (document.eudiMetadata?.format) {
            is StoredDocumentFormat.SdJwtVc -> IosPresentmentFormat.SdJwtVc
            // mdoc for anything unreadable too: it is what every credential this wallet stores in the
            // mdoc-only proximity path is, and the consent screen has to label the card as something.
            else -> IosPresentmentFormat.MsoMdoc
        },
        queryId = (source as? CredentialMatchSourceOpenID4VP)?.credentialQuery?.id,
        claims = claims.map { (requested, claim) -> toClaimInfo(requested, claim) },
    )
}

private fun toClaimInfo(
    requested: RequestedClaim,
    claim: Claim,
) = IosPresentmentRequest.RequestedClaimInfo(
    claim = claimPath(requested, claim),
    displayName = claim.displayName,
    value = (claim as? MdocClaim)?.value?.toClaimString() ?: claim.render(),
    intentToRetain = (requested as? MdocRequestedClaim)?.intentToRetain == true,
)

/**
 * Every way this request could be answered.
 *
 * Each credential set must be satisfied by one of its options, each option's members must each be
 * satisfied by one of their matches, and an optional set may be skipped — so the alternatives are a
 * cross product. For a request over one document type it collapses to a single combination; two PIDs
 * in the wallet make two, which is the case worth getting right, because picking one for the user
 * would silently share the wrong document.
 */
private fun CredentialPresentmentData.combinationsOfMatches(): List<List<MemberMatch>> {
    var combinations = listOf(emptyList<MemberMatch>())

    for (credentialSet in credentialSets) {
        val alternatives = buildList {
            credentialSet.options.forEach { addAll(it.memberCombinations()) }
            // An optional set the wallet need not answer: leaving it out is an answer too.
            if (credentialSet.optional) add(emptyList())
        }
        if (alternatives.isEmpty()) continue

        combinations = combinations
            .flatMap { chosen -> alternatives.map { chosen + it } }
            .take(MAX_COMBINATIONS)
    }

    return combinations.filter { it.isNotEmpty() }
}

/** The same cross product one level down: each member contributes exactly one of its matches. */
private fun CredentialPresentmentSetOption.memberCombinations(): List<List<MemberMatch>> {
    var combinations = listOf(emptyList<MemberMatch>())

    for (member in members) {
        if (member.matches.isEmpty()) continue
        combinations = combinations
            .flatMap { chosen -> member.matches.map { chosen + it } }
            .take(MAX_COMBINATIONS)
    }

    return combinations
}

/**
 * How a claim is named across the seam: the app's own [ClaimPathDomain], which is what the request
 * screen's row ids are built from anyway.
 *
 * Using it here rather than a private ref type is what makes the round trip exact. An mdoc claim is
 * named by namespace *and* data element, because the same identifier under two namespaces is two
 * different claims; an SD-JWT claim is named by its whole claims path pointer, because `address.locality`
 * and `address.country` share their first segment and only the full path tells them apart.
 */
private fun claimPath(requested: RequestedClaim, claim: Claim): ClaimPathDomain = when (requested) {
    is MdocRequestedClaim -> ClaimPathDomain(
        segments = listOf(ClaimPathSegment.Key(requested.dataElementName)),
        type = ClaimType.MsoMdoc(namespace = requested.namespaceName),
    )

    is JsonRequestedClaim -> ClaimPathDomain(
        segments = requested.claimPath.toSegments()
            // A pointer that resolved to nothing addressable would collide with every other such
            // pointer; the claim's own name is at least unique among the claims of one credential.
            .ifEmpty { listOf(ClaimPathSegment.Key(claim.displayName)) },
        type = ClaimType.SdJwtVc,
    )
}

/** OpenID4VP's claims path pointer: object keys, array indices, and the `null` wildcard. */
private fun JsonArray.toSegments(): List<ClaimPathSegment> = map { element ->
    when {
        element is JsonNull -> ClaimPathSegment.AllElements
        element is JsonPrimitive && element.isString -> ClaimPathSegment.Key(element.content)
        element is JsonPrimitive -> element.intOrNull
            ?.let { ClaimPathSegment.Index(it) }
            ?: ClaimPathSegment.Key(element.content)

        else -> ClaimPathSegment.Key(element.toString())
    }
}

/**
 * A ceiling on the alternatives offered. The cross product is one combination for an ordinary request;
 * this only bites on a pathological one, where a screen listing hundreds of choices would be useless.
 */
private const val MAX_COMBINATIONS = 16
