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

// Pure-data half of the presentation-combination model. The Wallet Core / multipaz mapper that
// builds these — `PresentationMatchDomain.from` and `identityKey` — stays in :core-logic as
// `PresentationMatchMapper.kt`, since it names multipaz types. Splitting along that line is what
// lets the model reach commonMain; the doc below already promised it "holds no Wallet Core types".
package eu.europa.ec.corelogic.model

/**
 * One way to satisfy the verifier's request — the user picks one. A 1:1 projection of a Wallet Core
 * `presentmentSelections` entry; the Wallet Core SDK has already done the DCQL expansion, so the
 * app does none of its own.
 *
 * @property matches the documents disclosed for this combination, in Wallet Core's order.
 */
data class PresentationCombinationDomain(
    val matches: List<PresentationMatchDomain>,
)

/**
 * A flat, pure-domain snapshot of one Wallet Core SDK match — a candidate credential the verifier
 * asked for and the wallet can fulfil. Built via `PresentationMatchDomain.from` (in :core-logic's
 * `PresentationMatchMapper.kt`), so it holds no Wallet Core types; the raw match stays in the
 * controller.
 *
 * @property documentId Wallet Core's `Document.identifier`.
 * @property credentialId Wallet Core's `Credential.identifier`.
 * @property requestedClaims the claim paths the verifier asked for and the wallet matched, as the
 * app's own [ClaimPathDomain].
 */
data class PresentationMatchDomain(
    val documentId: String,
    val credentialId: String,
    val queryId: String?,
    val requestedClaims: List<ClaimPathDomain>,
) {
    /** Empty, but kept so :core-logic's mapper can extend it and preserve the `from(…)` call site. */
    companion object
}