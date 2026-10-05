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
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
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
    ) : IosTransactionRecord
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
}

/** Every recorded transaction, as the shared domain; order is the caller's to decide. */
internal suspend fun MultipazWalletStore.recordedTransactions(): List<TransactionLogDomain> =
    transactionRecordsTable().enumerateWithData()
        .mapNotNull { (_, data) -> data.toTransactionRecordOrNull()?.toDomain() }

/** Removes the recorded transaction [id], if there is one. */
internal suspend fun MultipazWalletStore.deleteTransactionRecord(id: String) {
    transactionRecordsTable().delete(key = id)
}

internal fun IosTransactionRecord.toDomain(): TransactionLogDomain {
    val time = Instant.fromEpochMilliseconds(timeEpochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val result = if (completed) TransactionResultDomain.Completed else TransactionResultDomain.NotCompleted(reason)
    return when (this) {
        is IosTransactionRecord.Deletion -> TransactionLogDomain.CredentialDeletion(
            id = id,
            time = time,
            result = result,
            credential = CredentialRefDomain(identifier = credentialIdentifier),
            issuer = InteractingPartyDomain(
                name = issuerName?.let { name ->
                    LocalizedTextDomain(issuerNameLanguage ?: UNDETERMINED_LANGUAGE, name)
                },
                identifier = null,
                contacts = emptyList(),
            ),
        )
    }
}

/**
 * The deletion row for this document, still marked completed, or null when there is none to write:
 * wallet-core logs a deletion only for an *issued* document, so deleting a pending or deferred one leaves
 * no row. The issuer is named as wallet-core names it, by the first name the issuer's metadata gives.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun Document.deletionRecord(at: Instant): IosTransactionRecord.Deletion? {
    val metadata = eudiMetadata?.takeIf { it.issuedAt != null } ?: return null
    val issuerDisplay = metadata.issuerMetadata?.issuerDisplay?.firstOrNull()
    return IosTransactionRecord.Deletion(
        id = Uuid.random().toString(),
        timeEpochMillis = at.toEpochMilliseconds(),
        completed = true,
        credentialIdentifier = metadata.format.identifier,
        issuerName = issuerDisplay?.name,
        issuerNameLanguage = issuerDisplay?.locale,
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
