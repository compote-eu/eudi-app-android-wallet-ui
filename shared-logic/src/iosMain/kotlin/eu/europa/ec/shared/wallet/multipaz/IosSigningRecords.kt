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

// The documents signed through the RQES flow — iOS's counterpart of the signing entries Android's
// wallet-core records through `RqesSigningLogger`. The signing SDK is Swift and reports each document to a
// `TransactionLogger` there; Swift hands it on as an [IosSigningRecord], and it is kept here, beside the
// event log, for the History tab to merge in.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.InteractingPartyDomain
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * One document signed — or not — through the RQES flow, as the signing SDK reports it (its
 * `TransactionEntry.SigningSealing`, field for field). Built in Swift, so every field is a plain value.
 *
 * @property id the SDK's per-document transaction identifier; the same id again replaces the entry.
 * @property signingTransactionId shared by every document signed in one run.
 * @property fileSize in bytes, as the SDK's string.
 * @property serviceName the signing service's name, in [serviceNameLanguage].
 */
@Serializable
data class IosSigningRecord(
    val id: String,
    val signingTransactionId: String?,
    val timeEpochMillis: Long,
    val completed: Boolean,
    val reason: String?,
    val certificateIdentifier: String?,
    val dtbsr: String?,
    val fileName: String?,
    val fileSize: String?,
    val serviceName: String?,
    val serviceNameLanguage: String?,
)

/**
 * Stores [record], replacing an entry with the same id — the SDK's own rule: "repeated calls for the same
 * identifier replace the previous snapshot". Kept as long as the event log keeps its events. False for a
 * record with no id.
 */
internal suspend fun MultipazWalletStore.recordSigning(record: IosSigningRecord): Boolean {
    if (record.id.isBlank()) return false
    val table = signingRecordsTable()
    val encoded = signingJson.encodeToString(IosSigningRecord.serializer(), record).encodeToByteString()
    val expiration = Instant.fromEpochMilliseconds(record.timeEpochMillis) + MultipazWalletStore.EVENT_RETENTION
    if (table.get(key = record.id) != null) {
        table.update(key = record.id, data = encoded, expiration = expiration)
    } else {
        table.insert(key = record.id, data = encoded, expiration = expiration)
    }
    return true
}

/** Every recorded signing, as the shared domain; order is the caller's to decide. */
internal suspend fun MultipazWalletStore.signingTransactions(): List<TransactionLogDomain.SigningSealing> =
    signingRecordsTable().enumerateWithData()
        .mapNotNull { (_, data) -> data.toSigningRecordOrNull()?.toDomain() }

/** Removes the signing entry [id], if there is one. */
internal suspend fun MultipazWalletStore.deleteSigningRecord(id: String) {
    signingRecordsTable().delete(key = id)
}

/**
 * As Android maps wallet-core's entry: the service is known by name only — the SDK records no identifier
 * or contact for it here — and no service type is shown.
 */
internal fun IosSigningRecord.toDomain(): TransactionLogDomain.SigningSealing =
    TransactionLogDomain.SigningSealing(
        id = id,
        time = Instant.fromEpochMilliseconds(timeEpochMillis).toLocalDateTime(TimeZone.currentSystemDefault()),
        result = if (completed) TransactionResultDomain.Completed else TransactionResultDomain.NotCompleted(reason),
        service = InteractingPartyDomain(
            name = serviceName?.let { name -> LocalizedTextDomain(serviceNameLanguage ?: UNDETERMINED_LANGUAGE, name) },
            identifier = null,
            contacts = emptyList(),
        ),
        serviceType = null,
        signingTransactionId = signingTransactionId,
        certificateSerialNumber = certificateIdentifier,
        fileName = fileName,
        fileSizeBytes = fileSize?.toLongOrNull(),
        dtbsr = dtbsr,
    )

private fun ByteString.toSigningRecordOrNull(): IosSigningRecord? =
    runCatching { signingJson.decodeFromString(IosSigningRecord.serializer(), decodeToString()) }.getOrNull()

private val signingJson = Json { ignoreUnknownKeys = true }

/** BCP 47's "undetermined", for a service name recorded without a language. */
private const val UNDETERMINED_LANGUAGE = "und"
