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

// The multipaz-facing half of [PresentationMatchDomain], split out when the model itself moved to
// :shared-logic's commonMain. Only this file names multipaz types, which is the whole reason the
// model could move: the data was already a "flat, pure-domain snapshot" — just co-located with its
// mapper.
//
// `from` is an extension on the model's (now empty) companion rather than a top-level function, so
// the existing `PresentationMatchDomain.from(match)` call site in WalletCorePresentationController
// reads exactly as before; it only needs this file's import.
package eu.europa.ec.corelogic.model

import eu.europa.ec.corelogic.extension.toClaimPath
import org.multipaz.presentment.CredentialMatchSourceIso18013
import org.multipaz.presentment.CredentialMatchSourceOpenID4VP
import org.multipaz.presentment.CredentialPresentmentSetOptionMemberMatch

fun PresentationMatchDomain.Companion.from(
    match: CredentialPresentmentSetOptionMemberMatch,
): PresentationMatchDomain {
    val (documentId, credentialId, queryId) = match.identityKey
    return PresentationMatchDomain(
        documentId = documentId,
        credentialId = credentialId,
        queryId = queryId,
        requestedClaims = match.claims.keys.map { requestedClaim ->
            requestedClaim.toClaimPath()
        },
    )
}

/**
 * Identity of a Wallet Core match as `(documentId, credentialId, queryId)` — the key the controller
 * re-pairs a [PresentationSelectionDomain] to its raw match by. `queryId` (the DCQL query id, null
 * for proximity and DC-API) separates two matches that hit the same credential via different queries.
 */
internal val CredentialPresentmentSetOptionMemberMatch.identityKey: Triple<String, String, String?>
    get() = Triple(
        credential.document.identifier,
        credential.identifier,
        when (val matchSource = source) {
            // OpenID4VP/DCQL — the query id is mandatory
            is CredentialMatchSourceOpenID4VP -> matchSource.credentialQuery.id
            // BLE proximity and DC-API : no DCQL, no query id
            is CredentialMatchSourceIso18013 -> null
        },
    )
