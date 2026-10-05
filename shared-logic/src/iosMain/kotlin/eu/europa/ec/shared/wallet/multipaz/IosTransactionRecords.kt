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

// The transactions the wallet records itself, beside multipaz's event log — iOS's counterpart of the
// entries Android's wallet-core writes through its own producers (`CredentialDeletionLogger` and the
// rest). multipaz logs a presentation only once the verifier accepted it and a document only once it is
// issued; what else the History tab shows on Android is written here and merged in by `transactionLogs()`.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.CredentialRefDomain
import eu.europa.ec.corelogic.model.InteractingPartyDomain
import eu.europa.ec.corelogic.model.IssuanceDetailsDomain
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.QualifiedIdentifierDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import eu.europa.ec.shared.wallet.platform.iosUserLanguage
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.multipaz.document.Document
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One transaction this wallet recorded itself. A record with the same [id] replaces the row, which is how
 * a transaction written when it starts is brought up to date when it ends — wallet-core's way too.
 */
@Serializable
internal sealed interface IosTransactionRecord {
    val id: String
    val timeEpochMillis: Long
    val completed: Boolean

    /** Why the transaction did not complete, when it did not and the reason is known. */
    val reason: String?

    /**
     * A credential deleted from the wallet — wallet-core's `CredentialDeletion`: the credential's type and
     * who issued it, read before the deletion since both go with the document.
     */
    @Serializable
    @SerialName("deletion")
    data class Deletion(
        override val id: String,
        override val timeEpochMillis: Long,
        override val completed: Boolean,
        override val reason: String? = null,
        val credentialIdentifier: String,
        val issuerName: String? = null,
        val issuerNameLanguage: String? = null,
        val issuerIdentifier: QualifiedIdentifierRecord? = null,
    ) : IosTransactionRecord

    /**
     * An issuance or a re-issuance — wallet-core's `CredentialIssuance` and `CredentialReissuance`, as its
     * `CredentialIssuanceLogger` writes them: one row per session, counting the credentials asked for and
     * the ones issued. A deferred credential gets a row of its own, keyed `deferred:<documentId>`, which
     * its collection later brings up to date.
     */
    @Serializable
    @SerialName("issuance")
    data class Issuance(
        override val id: String,
        override val timeEpochMillis: Long,
        override val completed: Boolean,
        override val reason: String? = null,
        val reissuance: Boolean = false,
        val requestedCount: Int,
        val issuedCount: Int,
        val credentialIdentifiers: List<String> = emptyList(),
        /** False for an offer or a background renewal, as TS10 §3.5 keeps it; null when unknown. */
        val userTriggered: Boolean? = null,
        val issuer: IssuerPartyRecord = IssuerPartyRecord(),
    ) : IosTransactionRecord

    /**
     * A presentation, or an attempt at one — wallet-core's `Presentation`, as its `PresentationLogListener`
     * writes it: when a request the wallet can answer arrives, and brought up to date when it ends.
     *
     * @property requesterName the name the consent screen gave the relying party, for when its
     *   registration names it not.
     * @property claimsRequested what the consent screen asked for, per credential.
     * @property claimsPresented what was actually sent, which the user may have narrowed.
     */
    @Serializable
    @SerialName("presentation")
    data class Presentation(
        override val id: String,
        override val timeEpochMillis: Long,
        override val completed: Boolean,
        override val reason: String? = null,
        val requesterName: String? = null,
        val party: PresentationPartyRecord? = null,
        val claimsRequested: List<CredentialClaimsRecord> = emptyList(),
        val claimsPresented: List<CredentialClaimsRecord> = emptyList(),
        val transactionData: List<TransactionDataRecord> = emptyList(),
    ) : IosTransactionRecord
}

/**
 * One `transaction_data` entry as the request carried it: its type and its decoded JSON, read into the
 * shared domain by the consent screen's own parser when the History shows it.
 */
@Serializable
internal data class TransactionDataRecord(val type: String, val json: String, val displayName: String? = null)

/**
 * Who issued, as wallet-core names the interacting party of an issuance: from the issuer's registration
 * certificate when one was verified, otherwise by the name its metadata displays.
 */
@Serializable
internal data class IssuerPartyRecord(
    val name: String? = null,
    val nameLanguage: String? = null,
    val identifier: QualifiedIdentifierRecord? = null,
    /** wallet-core's provider type — `PIDProvider`, `QEAAProvider`, `PubEEAProvider` or `NonQEAAProvider`. */
    val type: String? = null,
    val contacts: List<String> = emptyList(),
)

/**
 * wallet-core 0.31.0's `CredentialIssuanceLogger.toInteractingParty`: the registered name (legal name,
 * then a natural person's names, then the name), identifier, provider type and contacts when the issuer's
 * registration was verified; [fallbackName] alone otherwise.
 */
internal fun IssuerRegistration?.toIssuerPartyRecord(
    fallbackName: String?,
    fallbackLanguage: String?,
): IssuerPartyRecord {
    if (this == null) return IssuerPartyRecord(name = fallbackName, nameLanguage = fallbackLanguage)
    val registeredName = legalName
        ?: listOfNotNull(givenName, familyName).joinToString(" ").ifBlank { null }
        ?: name
    return IssuerPartyRecord(
        name = registeredName ?: fallbackName,
        nameLanguage = if (registeredName != null) null else fallbackLanguage,
        identifier = subject?.toQualifiedIdentifierOrNull(),
        type = when {
            IssuerEntitlements.PID in entitlements -> "PIDProvider"
            IssuerEntitlements.QEAA in entitlements -> "QEAAProvider"
            // Spelled as wallet-core spells it, so the two platforms record the same value.
            IssuerEntitlements.PUB_EAA in entitlements -> "PubEEAProvider"
            IssuerEntitlements.NON_Q_EAA in entitlements -> "NonQEAAProvider"
            else -> null
        },
        contacts = listOfNotNull(country, supportUri, infoUri),
    )
}

/** Stores [record], replacing the row with the same id. Kept as long as the event log keeps its events. */
internal suspend fun MultipazWalletStore.recordTransaction(record: IosTransactionRecord) {
    val table = transactionRecordsTable()
    val encoded = recordJson.encodeToString(IosTransactionRecord.serializer(), record).encodeToByteString()
    val expiration = Instant.fromEpochMilliseconds(record.timeEpochMillis) + MultipazWalletStore.EVENT_RETENTION
    if (table.get(key = record.id) != null) {
        table.update(key = record.id, data = encoded, expiration = expiration)
    } else {
        table.insert(key = record.id, data = encoded, expiration = expiration)
    }
    transactionRecordChanges.tryEmit(Unit)
}

/** Every recorded transaction, as the shared domain; order is the caller's to decide. */
internal suspend fun MultipazWalletStore.recordedTransactions(): List<TransactionLogDomain> =
    transactionRecordsTable().enumerateWithData()
        .mapNotNull { (_, data) -> data.toTransactionRecordOrNull()?.toDomain() }

/** The recorded transaction [id], or null when there is none or it cannot be read. */
internal suspend fun MultipazWalletStore.transactionRecord(id: String): IosTransactionRecord? =
    transactionRecordsTable().get(key = id)?.toTransactionRecordOrNull()

/** Removes the recorded transaction [id], if there is one. */
internal suspend fun MultipazWalletStore.deleteTransactionRecord(id: String) {
    if (transactionRecordsTable().delete(key = id)) transactionRecordChanges.tryEmit(Unit)
}

internal fun IosTransactionRecord.toDomain(
    /** The language a registered purpose is shown in, as Android picks it by the user's locale. */
    languageCode: String = iosUserLanguage(),
): TransactionLogDomain {
    val time = Instant.fromEpochMilliseconds(timeEpochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val result = if (completed) TransactionResultDomain.Completed else TransactionResultDomain.NotCompleted(reason)
    return when (this) {
        is IosTransactionRecord.Presentation -> TransactionLogDomain.Presentation(
            id = id,
            time = time,
            result = result,
            party = InteractingPartyDomain(
                // The registered name first, as wallet-core takes it; otherwise what consent called them.
                name = (party?.name?.takeIf { it.isNotBlank() } ?: requesterName?.takeIf { it.isNotBlank() })
                    ?.let { name -> LocalizedTextDomain(UNDETERMINED_LANGUAGE, name) },
                identifier = party?.identifier?.toDomain(),
                contacts = party?.contacts.orEmpty(),
            ),
            partyType = null,
            intermediary = party?.intermediaryDomain(),
            registration = party?.registrationDomain(languageCode),
            claimsRequested = claimsRequested.map { it.toDomain() },
            claimsPresented = claimsPresented.map { it.toDomain() },
            transactionData = transactionData.map { it.toPresentationTransactionDataDomain() },
        )

        is IosTransactionRecord.Issuance -> {
            val details = IssuanceDetailsDomain(
                issuer = InteractingPartyDomain(
                    name = issuer.name?.let { name ->
                        LocalizedTextDomain(issuer.nameLanguage ?: UNDETERMINED_LANGUAGE, name)
                    },
                    identifier = issuer.identifier?.let { QualifiedIdentifierDomain(it.schemeUri, it.value) },
                    contacts = issuer.contacts,
                ),
                issuerType = issuer.type,
                requestedCount = requestedCount,
                issuedCount = issuedCount,
                credentials = credentialIdentifiers.map(::CredentialRefDomain),
                isUserTriggered = userTriggered,
            )
            if (reissuance) {
                TransactionLogDomain.CredentialReissuance(id, time, result, details)
            } else {
                TransactionLogDomain.CredentialIssuance(id, time, result, details)
            }
        }

        is IosTransactionRecord.Deletion -> TransactionLogDomain.CredentialDeletion(
            id = id,
            time = time,
            result = result,
            credential = CredentialRefDomain(identifier = credentialIdentifier),
            issuer = InteractingPartyDomain(
                name = issuerName?.let { name ->
                    LocalizedTextDomain(issuerNameLanguage ?: UNDETERMINED_LANGUAGE, name)
                },
                identifier = issuerIdentifier?.let { QualifiedIdentifierDomain(it.schemeUri, it.value) },
                contacts = emptyList(),
            ),
        )
    }
}

/**
 * The deletion row for this document, still marked completed, or null when there is none to write:
 * wallet-core logs a deletion only for an *issued* document, so deleting a pending or deferred one leaves
 * no row. The issuer is named as wallet-core names it: as its issuance recorded them — the registered
 * name and identifier when its registration was verified — and otherwise by the first name the issuer's
 * metadata gives.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun Document.deletionRecord(at: Instant): IosTransactionRecord.Deletion? {
    val metadata = eudiMetadata?.takeIf { it.issuedAt != null } ?: return null
    val party = metadata.issuerParty
    val issuerDisplay = metadata.issuerMetadata?.issuerDisplay?.firstOrNull()
    return IosTransactionRecord.Deletion(
        id = Uuid.random().toString(),
        timeEpochMillis = at.toEpochMilliseconds(),
        completed = true,
        credentialIdentifier = metadata.format.identifier,
        issuerName = party?.name ?: issuerDisplay?.name,
        issuerNameLanguage = if (party?.name != null) party.nameLanguage else issuerDisplay?.locale,
        issuerIdentifier = party?.identifier,
    )
}

/** This row as the deletion's [outcome] left it. */
internal fun IosTransactionRecord.Deletion.after(outcome: Result<*>): IosTransactionRecord.Deletion =
    outcome.exceptionOrNull()?.let { error ->
        copy(completed = false, reason = error.noncompletionReason(default = REASON_DELETION_FAILED))
    } ?: this

/**
 * wallet-core's `toNoncompletionReason`: the error's first line, or its cause's, when one is short enough
 * to show; otherwise [default].
 */
internal fun Throwable.noncompletionReason(default: String): String =
    message.asReason() ?: cause?.message.asReason() ?: default

private fun String?.asReason(): String? =
    this?.substringBefore('\n')?.trim()?.takeIf { it.isNotBlank() && it.length <= MAX_REASON_LENGTH }

private fun ByteString.toTransactionRecordOrNull(): IosTransactionRecord? =
    runCatching { recordJson.decodeFromString(IosTransactionRecord.serializer(), decodeToString()) }.getOrNull()

private val recordJson = Json { ignoreUnknownKeys = true }

/** wallet-core's limit on a recorded reason. */
private const val MAX_REASON_LENGTH = 120

/** wallet-core's reason for a deletion that failed without a usable message. */
internal const val REASON_DELETION_FAILED = "Deletion did not complete"

/** BCP 47's "undetermined", for a name recorded without a language. */
private const val UNDETERMINED_LANGUAGE = "und"
