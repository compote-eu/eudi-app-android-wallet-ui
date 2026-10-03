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

// multipaz's event log read as the shared TransactionLogDomain — iOS's counterpart of Android's
// `TransactionEntry.toTransactionLogDomain` in :core-logic. Like that mapper it copies identifiers and
// claim paths and never claim values, so the History screens show the same kind of record on both
// platforms.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.ClaimRefDomain
import eu.europa.ec.corelogic.model.CredentialClaimsDomain
import eu.europa.ec.corelogic.model.CredentialRefDomain
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.corelogic.model.InteractingPartyDomain
import eu.europa.ec.corelogic.model.IssuanceDetailsDomain
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.multipaz.claim.Claim
import org.multipaz.eventlogger.Event
import org.multipaz.eventlogger.EventPresentment
import org.multipaz.eventlogger.EventPresentmentDataDocument
import org.multipaz.eventlogger.EventProvisioning
import org.multipaz.eventlogger.EventSimple
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim

/**
 * The language tag for text multipaz records without one — a verifier's or issuer's name. BCP 47's
 * "undetermined" rather than a guess.
 */
private const val UNDETERMINED_LANGUAGE = "und"

/**
 * Every transaction the wallet has recorded, newest first, as the shared domain.
 *
 * The log's own order is chronological by storage key, oldest first, so it is reversed *before* a stable
 * sort: events recorded in the same instant — one presentation can log an event per credential — still
 * come back newest-first.
 */
internal suspend fun MultipazWalletStore.transactionLogs(): List<TransactionLogDomain> =
    eventLogger().getEvents()
        .mapNotNull { event -> event.toTransactionLogDomain(formatOf = ::documentFormatType) }
        .asReversed()
        .sortedByDescending { transaction -> transaction.time }

/** One transaction by id, or null when the log has no such entry. */
internal suspend fun MultipazWalletStore.transactionLog(id: String): TransactionLogDomain? =
    transactionLogs().firstOrNull { transaction -> transaction.id == id }

/**
 * Removes one event from the log. multipaz deletes by the stored event, so it is looked up first; an id
 * the log no longer holds — already deleted, or expired — leaves nothing to do.
 */
internal suspend fun MultipazWalletStore.deleteTransactionLog(id: String) {
    val logger = eventLogger()
    logger.getEvents().firstOrNull { event -> event.identifier == id }?.let { event -> logger.deleteEvent(event) }
}

private suspend fun MultipazWalletStore.documentFormatType(documentId: String): FormatType? =
    documentStore.lookupDocument(documentId)?.eudiMetadata?.format?.identifier

/**
 * One event as a transaction, or null for one the History tab has nothing to say about.
 *
 * Every entry is completed: multipaz logs an event only once the work has succeeded, so a failed or
 * cancelled exchange leaves none — as on Android, where a cancelled presentation logs nothing either.
 */
internal suspend fun Event.toTransactionLogDomain(
    formatOf: suspend (documentId: String) -> FormatType?,
): TransactionLogDomain? {
    val time = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())

    return when (this) {
        is EventPresentment -> {
            val presented = presentmentData.requestedDocuments.map { document ->
                document.toCredentialClaims(formatOf)
            }
            TransactionLogDomain.Presentation(
                id = identifier,
                time = time,
                result = TransactionResultDomain.Completed,
                party = InteractingPartyDomain(
                    // The certificate's name when multipaz recorded none: multipaz fills
                    // `requesterName` only from trust metadata or a web origin, and
                    // `uriSchemePresentment` passes `origin = ""`, so a URI-scheme presentation records
                    // a blank name where the consent screen had named the verifier.
                    name = (presentmentData.requesterName?.takeIf { it.isNotBlank() }
                        ?: presentmentData.requesterCertChain.commonName())
                        ?.let { name -> LocalizedTextDomain(UNDETERMINED_LANGUAGE, name) },
                    identifier = null,
                    contacts = emptyList(),
                ),
                partyType = null,
                intermediary = null,
                registration = null,
                // multipaz records what was shared, keyed by what was asked for, and nothing about
                // requested claims that were not shared. So the request is recorded as what was shared.
                claimsRequested = presented,
                claimsPresented = presented,
            )
        }

        is EventProvisioning -> {
            val details = IssuanceDetailsDomain(
                issuer = InteractingPartyDomain(
                    name = issuerData.display.text.takeIf { it.isNotBlank() }
                        ?.let { name -> LocalizedTextDomain(UNDETERMINED_LANGUAGE, name) },
                    identifier = null,
                    contacts = emptyList(),
                ),
                issuerType = null,
                // multipaz logs one event per document, and only once it is issued.
                requestedCount = 1,
                issuedCount = 1,
                credentials = listOfNotNull(formatOf(documentId)?.let(::CredentialRefDomain)),
                isUserTriggered = null,
            )
            if (initialProvisioning) {
                TransactionLogDomain.CredentialIssuance(identifier, time, TransactionResultDomain.Completed, details)
            } else {
                TransactionLogDomain.CredentialReissuance(identifier, time, TransactionResultDomain.Completed, details)
            }
        }

        is EventSimple -> null
    }
}

private suspend fun EventPresentmentDataDocument.toCredentialClaims(
    formatOf: suspend (documentId: String) -> FormatType?,
): CredentialClaimsDomain {
    val requestedClaims = claims.keys
    // The type the verifier asked for, read from the request itself so it survives the document's
    // deletion; the stored document only when the request named none.
    val identifier = requestedClaims.firstNotNullOfOrNull { it.credentialIdentifier() }
        ?: formatOf(documentId)
        .orEmpty()
    return CredentialClaimsDomain(
        credential = CredentialRefDomain(identifier = identifier),
        claims = claims.map { (requested, claim) -> ClaimRefDomain(segments = requested.logSegments(claim)) },
    )
}

private fun RequestedClaim.credentialIdentifier(): FormatType? = when (this) {
    is MdocRequestedClaim -> docType
    is JsonRequestedClaim -> vctValues.firstOrNull()
}?.takeIf { it.isNotBlank() }

/**
 * The claim's path as the log records it: for an mdoc the namespace, then the element — what Android's
 * TS10 entries hold — rather than the element alone with the namespace as a type, as the consent
 * screen's [claimPath] keeps it.
 */
private fun RequestedClaim.logSegments(claim: Claim): List<ClaimPathSegment> = when (this) {
    is MdocRequestedClaim -> listOf(ClaimPathSegment.Key(namespaceName), ClaimPathSegment.Key(dataElementName))
    is JsonRequestedClaim -> claimPath(this, claim).segments
}
