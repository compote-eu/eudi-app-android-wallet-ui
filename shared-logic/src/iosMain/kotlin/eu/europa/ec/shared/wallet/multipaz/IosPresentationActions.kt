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

// The data-deletion requests and transaction reports a user started from a presentation's details — iOS's
// counterpart of the rows Android's `WalletCoreTransactionLogController` keeps beside wallet-core's log,
// under the same rules: stored only under a presentation that exists, once, and never rewritten. An
// identical retry — the same attempt saved again after a failed save — succeeds without a second row.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.extension.toCommunicationMethodDomainOrNull
import eu.europa.ec.corelogic.extension.toStoredCommunicationMethod
import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.ClaimRefDomain
import eu.europa.ec.corelogic.model.CommunicationMethodDomain
import eu.europa.ec.corelogic.model.CredentialClaimsDomain
import eu.europa.ec.corelogic.model.CredentialRefDomain
import eu.europa.ec.corelogic.model.DpaContactDomain
import eu.europa.ec.corelogic.model.InteractingPartyDomain
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.QualifiedIdentifierDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.multipaz.eventlogger.EventPresentment
import kotlin.time.Instant

/** One attempt, as the actions table stores it. */
@Serializable
internal data class PresentationActionRecord(
    val id: String,
    val parentPresentationId: String,
    val kind: Kind,
    /** `CommunicationMethodDomain`'s stored form, the one Android's table uses. */
    val communicationMethod: String,
    val timeEpochMillis: Long,
    /** For a deletion request: who it went to and what had been shared, as Android's TS10 entry keeps them. */
    val partyName: String? = null,
    val partyIdentifier: QualifiedIdentifierRecord? = null,
    val claims: List<CredentialClaimsRecord> = emptyList(),
    /** For a report: the authority it went to. */
    val authorityName: String? = null,
    val authorityCountry: String? = null,
) {
    @Serializable
    enum class Kind { DataDeletionRequest, DpaReport }
}

@Serializable
internal data class CredentialClaimsRecord(val credential: String, val claims: List<List<ClaimSegmentRecord>>)

/** A claim path segment: a [key], an [index], or — neither — every element of an array. */
@Serializable
internal data class ClaimSegmentRecord(val key: String? = null, val index: Int? = null)

/** What wallet-core's `toDataDeletionRequestEntry` records: the presentation's party and its shared claims. */
internal fun TransactionLogDomain.Presentation.toDataDeletionRequestRecord(
    id: String,
    time: Instant,
    communicationMethod: CommunicationMethodDomain,
) = PresentationActionRecord(
    id = id,
    parentPresentationId = this.id,
    kind = PresentationActionRecord.Kind.DataDeletionRequest,
    communicationMethod = communicationMethod.toStoredCommunicationMethod(),
    timeEpochMillis = time.toEpochMilliseconds(),
    partyName = party.name?.text,
    partyIdentifier = party.identifier?.let { QualifiedIdentifierRecord(it.schemeUri, it.value) },
    claims = claimsPresented.map { credential ->
        CredentialClaimsRecord(
            credential = credential.credential.identifier,
            claims = credential.claims.map { claim -> claim.segments.map { it.toRecord() } },
        )
    },
)

/** What wallet-core's `toDpaReportEntry` records: the authority's name and country. */
internal fun DpaContactDomain.toDpaReportRecord(
    id: String,
    time: Instant,
    parentPresentationId: String,
    communicationMethod: CommunicationMethodDomain,
) = PresentationActionRecord(
    id = id,
    parentPresentationId = parentPresentationId,
    kind = PresentationActionRecord.Kind.DpaReport,
    communicationMethod = communicationMethod.toStoredCommunicationMethod(),
    timeEpochMillis = time.toEpochMilliseconds(),
    authorityName = name?.text,
    authorityCountry = country?.text,
)

/** The shared domain's view of an attempt, or null for a row this version cannot read. */
internal fun PresentationActionRecord.toDomain(): TransactionLogDomain.PresentationAction? {
    val method = communicationMethod.toCommunicationMethodDomainOrNull() ?: return null
    val time = Instant.fromEpochMilliseconds(timeEpochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return when (kind) {
        PresentationActionRecord.Kind.DataDeletionRequest -> TransactionLogDomain.DataDeletionRequest(
            id = id,
            time = time,
            result = TransactionResultDomain.Completed,
            parentPresentationId = parentPresentationId,
            communicationMethod = method,
            party = InteractingPartyDomain(
                name = partyName?.let { LocalizedTextDomain(UNDETERMINED_LANGUAGE_TAG, it) },
                identifier = partyIdentifier?.let { QualifiedIdentifierDomain(it.schemeUri, it.value) },
                contacts = emptyList(),
            ),
            claims = claims.map { credential ->
                CredentialClaimsDomain(
                    credential = CredentialRefDomain(identifier = credential.credential),
                    claims = credential.claims.map { path -> ClaimRefDomain(segments = path.map { it.toDomain() }) },
                )
            },
        )

        PresentationActionRecord.Kind.DpaReport -> TransactionLogDomain.DpaReport(
            id = id,
            time = time,
            result = TransactionResultDomain.Completed,
            parentPresentationId = parentPresentationId,
            communicationMethod = method,
            dpaName = authorityName?.let { LocalizedTextDomain(UNDETERMINED_LANGUAGE_TAG, it) },
            dpaCountry = authorityCountry?.let { LocalizedTextDomain(UNDETERMINED_LANGUAGE_TAG, it) },
        )
    }
}

private fun ClaimPathSegment.toRecord() = when (this) {
    is ClaimPathSegment.Key -> ClaimSegmentRecord(key = name)
    is ClaimPathSegment.Index -> ClaimSegmentRecord(index = index)
    ClaimPathSegment.AllElements -> ClaimSegmentRecord()
}

private fun ClaimSegmentRecord.toDomain(): ClaimPathSegment = when {
    key != null -> ClaimPathSegment.Key(key)
    index != null -> ClaimPathSegment.Index(index)
    else -> ClaimPathSegment.AllElements
}

/**
 * Saves [record] under its presentation, by the rules in this file's header: never under a missing
 * presentation or one that is not a presentation, never under itself, and never over a different attempt
 * with the same id. True when the attempt is now stored, including an identical retry.
 */
internal suspend fun MultipazWalletStore.recordPresentationAction(record: PresentationActionRecord): Boolean {
    if (record.id.isBlank() || record.parentPresentationId.isBlank() || record.id == record.parentPresentationId) {
        return false
    }
    val table = presentationActionsTable()
    val encoded = actionJson.encodeToString(PresentationActionRecord.serializer(), record).encodeToByteString()
    table.get(key = record.id, partitionId = record.parentPresentationId)?.let { stored ->
        return stored == encoded
    }
    val parent = eventLogger().getEvents()
        .firstOrNull { event -> event.identifier == record.parentPresentationId } as? EventPresentment
        ?: return false
    table.insert(
        key = record.id,
        data = encoded,
        partitionId = record.parentPresentationId,
        // It goes with its presentation, as Android's rows go with theirs (a cascading foreign key).
        expiration = parent.timestamp + MultipazWalletStore.EVENT_RETENTION,
    )
    presentationActionChanges.tryEmit(Unit)
    return true
}

/**
 * The attempts recorded under the presentation [presentationId], newest first. None once the presentation
 * is gone — and then its rows are removed, as Android's cascade removes them.
 */
internal suspend fun MultipazWalletStore.presentationActions(
    presentationId: String,
): List<TransactionLogDomain.PresentationAction> {
    val table = presentationActionsTable()
    if (eventLogger().getEvents().none { event -> event.identifier == presentationId }) {
        deletePresentationActions(presentationId)
        return emptyList()
    }
    return table.enumerateWithData(partitionId = presentationId)
        .mapNotNull { (_, data) -> data.toActionRecordOrNull() }
        .sortedByDescending { record -> record.timeEpochMillis }
        .mapNotNull { record -> record.toDomain() }
}

/** [presentationActions], again whenever an attempt is recorded or the log changes. */
internal fun MultipazWalletStore.observePresentationActions(
    presentationId: String,
): Flow<List<TransactionLogDomain.PresentationAction>> = flow {
    val logger = eventLogger()
    emitAll(
        merge(presentationActionChanges, logger.eventFlow)
            .onStart { emit(Unit) }
            .map { presentationActions(presentationId) }
    )
}

/** Removes every attempt recorded under [presentationId]. */
internal suspend fun MultipazWalletStore.deletePresentationActions(presentationId: String) {
    presentationActionsTable().deletePartition(presentationId)
    presentationActionChanges.tryEmit(Unit)
}

private fun ByteString.toActionRecordOrNull(): PresentationActionRecord? =
    runCatching { actionJson.decodeFromString(PresentationActionRecord.serializer(), decodeToString()) }.getOrNull()

private val actionJson = Json { ignoreUnknownKeys = true }

/** BCP 47's "undetermined": multipaz and the certificates record these names without a language. */
private const val UNDETERMINED_LANGUAGE_TAG = "und"
