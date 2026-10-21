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

// The History's records read as the shared TransactionLogDomain — iOS's counterpart of Android's
// `TransactionEntry.toTransactionLogDomain` in :core-logic. Like that mapper it keeps identifiers and
// claim paths and never claim values, so the History screens show the same kind of record on both
// platforms.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.DpaContactDomain
import eu.europa.ec.corelogic.model.InteractingPartyDomain
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.PresentationRegistrationDomain
import eu.europa.ec.corelogic.model.QualifiedIdentifierDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import org.multipaz.claim.Claim
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim

/**
 * The language tag for text recorded without one — an intermediary's or a data protection authority's
 * name. BCP 47's "undetermined" rather than a guess.
 */
private const val UNDETERMINED_LANGUAGE = "und"

/**
 * Every transaction the wallet has recorded, newest first, as the shared domain: the documents signed
 * through the RQES flow (`IosSigningRecords.kt`) and the transactions the wallet records itself
 * (`IosTransactionRecords.kt`).
 */
internal suspend fun MultipazWalletStore.transactionLogs(): List<TransactionLogDomain> =
    (signingTransactions() + recordedTransactions()).sortedByDescending { transaction -> transaction.time }

/** One transaction by id, or null when the log has no such entry. */
internal suspend fun MultipazWalletStore.transactionLog(id: String): TransactionLogDomain? =
    transactionLogs().firstOrNull { transaction -> transaction.id == id }

/**
 * Removes one entry: a signing record or a recorded transaction. An id nothing holds — already deleted —
 * leaves nothing to do.
 */
internal suspend fun MultipazWalletStore.deleteTransactionLog(id: String) {
    // A presentation's deletion requests and reports go with it, as Android's cascade removes them.
    deletePresentationActions(id)
    deleteSigningRecord(id)
    deleteTransactionRecord(id)
}

internal fun QualifiedIdentifierRecord.toDomain() = QualifiedIdentifierDomain(schemeUri = schemeUri, value = value)

/** Android's rule: an intermediary appears only when the certificate named one. */
internal fun PresentationPartyRecord.intermediaryDomain(): InteractingPartyDomain? = InteractingPartyDomain(
    name = intermediaryName?.let { name -> LocalizedTextDomain(UNDETERMINED_LANGUAGE, name) },
    identifier = intermediaryIdentifier?.toDomain(),
    contacts = emptyList(),
).takeIf { intermediary -> intermediary.name != null || intermediary.identifier != null }

/** Android's `toPresentationRegistrationDomain`: null when the certificate declared none of it. */
internal fun PresentationPartyRecord.registrationDomain(languageCode: String): PresentationRegistrationDomain? {
    val localizedPurpose = purpose.map { LocalizedText(it.language, it.value) }.forLocale(languageCode)
    val authority = DpaContactDomain(
        name = authorityName?.let { name -> LocalizedTextDomain(UNDETERMINED_LANGUAGE, name) },
        country = null,
        contacts = authorityContacts,
    ).takeIf { dpa -> dpa.name != null || dpa.contacts.isNotEmpty() }
    if (registrarUrl == null && localizedPurpose == null && privacyPolicyUrls.isEmpty() && authority == null) {
        return null
    }
    return PresentationRegistrationDomain(
        registrarUrl = registrarUrl,
        purpose = localizedPurpose,
        privacyPolicyUrls = privacyPolicyUrls,
        dpa = authority,
    )
}

/**
 * The claim's path as the log records it: for an mdoc the namespace, then the element — what Android's
 * TS10 entries hold — rather than the element alone with the namespace as a type, as the consent
 * screen's [claimPath] keeps it.
 */
internal fun RequestedClaim.logSegments(claim: Claim): List<ClaimPathSegment> = when (this) {
    is MdocRequestedClaim -> listOf(ClaimPathSegment.Key(namespaceName), ClaimPathSegment.Key(dataElementName))
    is JsonRequestedClaim -> claimPath(this, claim).segments
}
