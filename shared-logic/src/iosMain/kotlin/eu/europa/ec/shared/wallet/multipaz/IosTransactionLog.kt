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

// What the wallet has done, read back out of multipaz's event log.
//
// The log is written by multipaz rather than by this app — the presentment functions and
// `ProvisioningModel` each record their own event once the work has succeeded — so everything here is
// the *read* half: turning `Event`s into the neutral shape the shared transactions interactor consumes.
// Kept in :shared-logic beside the store, because it names multipaz types and the bridge in :shared-ui
// must not.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.ClaimDomain
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.multipaz.eventlogger.Event
import org.multipaz.eventlogger.EventPresentment
import org.multipaz.eventlogger.EventProvisioning
import org.multipaz.eventlogger.EventSimple

/**
 * One thing the wallet did, in the app's own vocabulary.
 *
 * The same seven fields `PlatformTransactionLog` carries in :shared-ui, minus its UI enums — those live
 * above this module, so the kind is [IosTransactionKind] here and the bridge maps it across. There is no
 * status: multipaz logs an event only after the work has succeeded, so every entry is a completed one.
 */
data class IosTransaction(
    val id: String,
    val kind: IosTransactionKind,
    val createdAt: LocalDateTime,
    /** The verifier, when there was one. Null for issuance, which has no counterparty. */
    val relyingPartyName: String?,
    /** Document display names, already localized by whoever wrote the event. */
    val documentNames: List<String>,
    /**
     * What was actually shared, one entry per document — empty for an issuance, which shares nothing.
     *
     * The claims come back out of the event exactly as they went in, as multipaz's own
     * `RequestedClaim`-to-`Claim` map, so they are translated with the very function the consent screen
     * used. That is the point: a transaction shown weeks later names its claims the way the user saw
     * them when they agreed.
     */
    val sharedDocuments: List<IosSharedDocument>,
)

/** One document as it appeared in a completed presentation. */
data class IosSharedDocument(
    val documentId: String,
    val documentName: String,
    val claims: List<ClaimDomain.Primitive>,
)

/** Which of the wallet's activities an entry records. Signing is absent because iOS cannot sign. */
enum class IosTransactionKind { Presentation, Issuance }

/**
 * Every transaction the wallet has recorded, newest first.
 *
 * Ordering is done here rather than left to the caller because the log's own order is chronological by
 * storage key — oldest first — and every reader of this wants the opposite.
 *
 * Reversed *before* sorting, and the sort is stable, so two events recorded in the same instant still
 * come back newest-first rather than in whichever order the tie happened to leave them. That is not
 * hypothetical: one presentation can log an event per credential.
 */
internal suspend fun MultipazWalletStore.transactions(): List<IosTransaction> =
    eventLogger().getEvents()
        .mapNotNull { it.toTransaction() }
        .asReversed()
        .sortedByDescending { it.createdAt }

/** One transaction by id, or null when the log has no such entry — an expired one, or a bad link. */
internal suspend fun MultipazWalletStore.transaction(id: String): IosTransaction? =
    transactions().firstOrNull { it.id == id }

/**
 * One multipaz event as a transaction, or null for one the History tab has nothing to say about.
 *
 * `EventSimple` is dropped rather than shown: it is multipaz's free-text event, carrying a string and
 * no document, so rendering it as a transaction would put a row in the list that names nothing the user
 * did. Nothing in this wallet writes one today; dropping it is what keeps that true if something starts.
 */
private fun Event.toTransaction(): IosTransaction? {
    val createdAt = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())

    return when (this) {
        is EventPresentment -> IosTransaction(
            id = identifier,
            kind = IosTransactionKind.Presentation,
            createdAt = createdAt,
            // Falls back to the verifier's certificate, and usually needs to. multipaz fills
            // `requesterName` only from trust metadata or a web origin, and `uriSchemePresentment`
            // hands it `origin = ""` — so a URI-scheme presentation records a *blank* name, and the
            // History row would say nothing where the consent screen had named the verifier. Reading
            // the certificate here is what keeps the two consistent.
            relyingPartyName = presentmentData.requesterName?.takeIf { it.isNotBlank() }
                ?: presentmentData.requesterCertChain.commonName(),
            // A document multipaz could not name is listed by nothing rather than by its identifier:
            // an opaque id in a "you shared X with Y" row reads as a bug to a user.
            documentNames = presentmentData.requestedDocuments.mapNotNull { it.documentName },
            sharedDocuments = presentmentData.requestedDocuments.mapNotNull { document ->
                IosSharedDocument(
                    documentId = document.documentId,
                    documentName = document.documentName ?: return@mapNotNull null,
                    claims = document.claims.map { (requested, claim) ->
                        ClaimDomain.Primitive(
                            key = claimPath(requested, claim).segments.last().toString(),
                            displayTitle = claim.displayName,
                            path = claimPath(requested, claim),
                            value = claim.readableValue(),
                            // Nothing here is a choice any more; the sharing already happened.
                            isRequired = false,
                        )
                    },
                )
            },
        )

        is EventProvisioning -> IosTransaction(
            id = identifier,
            kind = IosTransactionKind.Issuance,
            createdAt = createdAt,
            relyingPartyName = null,
            documentNames = listOfNotNull(documentName),
            sharedDocuments = emptyList(),
        )

        is EventSimple -> null
    }
}
