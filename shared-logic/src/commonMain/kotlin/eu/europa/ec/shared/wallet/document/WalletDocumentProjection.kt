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

package eu.europa.ec.shared.wallet.document

import eu.europa.ec.shared.wallet.WalletDocument
import eu.europa.ec.shared.wallet.WalletDocumentIssuanceState
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Turns a platform-neutral [StoredDocument] into the app-facing [WalletDocument].
 *
 * This is the KMP counterpart of `WalletEngineImpl.toWalletDocument` on Android, and it is
 * deliberately field-for-field faithful to it so the same wallet yields the same list on both
 * platforms — including which fields the *list* projection leaves absent:
 *  - **claims stay whatever [StoredDocument] carries.** Android's list mapper does not read claims
 *    at all (only `getMainPidDocument` does), so the caller controls the cost by choosing whether to
 *    parse them into [StoredDocument.claims] in the first place.
 *  - **a pending document keeps the absent defaults** for every credential- and validity-related
 *    field, rather than being faked to zero-with-meaning — nothing about them is knowable until the
 *    issuer answers.
 *  - **issuer display is resolved for pending documents too**, since it comes from metadata the
 *    issuer published up front rather than from the credential.
 *
 * @param locale a BCP-47 language tag used to pick the issuer's localized display.
 * @param isRevoked supplied by the caller because revocation lives outside the document store.
 * @param now the instant expiry is judged against; injectable so the decision is testable.
 */
internal fun StoredDocument.toWalletDocument(
    locale: String,
    isRevoked: Boolean = false,
    now: Instant = Clock.System.now(),
): WalletDocument {
    val issuerDisplay = issuerMetadata?.issuerDisplay.localizedOrFirst(locale) { it.locale }

    val base = WalletDocument(
        id = id,
        name = name,
        formatType = formatType,
        claims = claims,
        isRevoked = isRevoked,
        issuerName = issuerDisplay?.name,
        issuerLogoUri = issuerDisplay?.logo?.uri,
    )

    if (issuedAt == null) {
        return base.copy(issuanceState = WalletDocumentIssuanceState.Pending)
    }

    val usable = usableCredentials()
    val expiresAt = usable.walletExpiresAt()

    return base.copy(
        issuanceState = WalletDocumentIssuanceState.Issued,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        // A document with no credentials left has a null expiry; that is an exhausted state, not an
        // expiry, so it must not read as expired.
        isExpired = expiresAt?.let { it < now } == true,
        credentialsCount = usable.size,
        initialCredentialsCount = policy.numberOfCredentials,
        isLowOnCredentials = isLowOnCredentials(policy, usable.size),
    )
}
