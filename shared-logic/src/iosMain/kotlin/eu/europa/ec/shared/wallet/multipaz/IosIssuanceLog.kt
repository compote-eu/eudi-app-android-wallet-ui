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

package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.platform.iosUserLanguage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.document.Document
import org.multipaz.util.Logger
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The History rows of one issuance session — wallet-core 0.31.0's `CredentialIssuanceLogger`
 * (`IssuanceAggregator`), whose rules this follows rather than chooses:
 *
 * - **nothing is written until [started]**, which is called once the user has authorized — multipaz
 *   creates the first document only then. A login the user abandons leaves no row, exactly as Android's
 *   `IssueEvent.Started` comes after `authorize`.
 * - [started] writes the row *ahead*, not completed, so an issuance the process does not survive is still
 *   there; [finish] brings the same row up to date.
 * - a deferred credential gets an "awaiting" row of its own, `deferred:<documentId>`, which its collection
 *   updates ([recordDeferredResolution]) — unless the session deferred everything, when the session row
 *   itself says it is awaiting.
 *
 * A failure to write is logged and never fails the issuance.
 */
internal class IosIssuanceLog(
    private val store: suspend () -> MultipazWalletStore,
    private val reissuance: Boolean,
    /** TS10 §3.5 `isUserTriggered`: false for an offer and for a background renewal. */
    val userTriggered: Boolean,
    private val requested: Int,
    /** The issuer's verified registration, which names it; null when the check was off or found none. */
    private val registration: IssuerRegistration?,
    now: Instant = Clock.System.now(),
) {
    @OptIn(ExperimentalUuidApi::class)
    private val id = Uuid.random().toString()
    private val time = now
    private val mutex = Mutex()
    private var isStarted = false
    private var isFinished = false
    private var issuerName: String? = null
    private var issuerNameLanguage: String? = null
    private val issuedIdentifiers = mutableListOf<String>()
    private val deferredDocumentIds = mutableListOf<String>()
    private var failureReason: String? = null

    /**
     * The session got past authorization. The first call writes the row; [issuerName] — the name the
     * issuer's metadata displays — is kept for a party the registration does not name.
     */
    suspend fun started(issuerName: String?, issuerNameLanguage: String? = null) {
        val row = mutex.withLock {
            if (this.issuerName == null) {
                this.issuerName = issuerName
                this.issuerNameLanguage = issuerNameLanguage
            }
            if (isStarted) return
            isStarted = true
            entry(completed = false, reason = null)
        }
        write(listOf(row))
    }

    /** One configuration ended with [documentId]: issued, or parked to be collected later. */
    suspend fun documentFinished(documentId: String) {
        val metadata = runCatching { store().documentStore.lookupDocument(documentId)?.eudiMetadata }.getOrNull()
            ?: return
        when {
            metadata.issuedAt != null -> issued(metadata.format.identifier)
            metadata.deferredTransactionId != null -> deferred(documentId)
        }
    }

    /** A credential of type [credentialIdentifier] — a doctype or a vct — was issued. */
    suspend fun issued(credentialIdentifier: String) = mutex.withLock {
        issuedIdentifiers += credentialIdentifier
    }

    /** The issuer deferred [documentId]'s credential; it is collected later. */
    suspend fun deferred(documentId: String) = mutex.withLock {
        deferredDocumentIds += documentId
    }

    /** One configuration failed; the last reason is the one the row keeps, as wallet-core keeps it. */
    suspend fun failed(error: Throwable) = mutex.withLock {
        failureReason = error.noncompletionReason(default = REASON_ISSUANCE_FAILED)
    }

    /**
     * The session is over. Writes nothing for one that never [started]. [overallError] is a failure of the
     * whole session rather than of one configuration.
     */
    suspend fun finish(overallError: Throwable? = null) {
        val rows = mutex.withLock {
            if (!isStarted || isFinished) return
            isFinished = true
            val nonDeferred = requested - deferredDocumentIds.size
            when {
                overallError != null -> listOf(
                    entry(completed = false, reason = overallError.noncompletionReason(REASON_ISSUANCE_FAILED)),
                )

                nonDeferred <= 0 && deferredDocumentIds.isNotEmpty() ->
                    listOf(entry(completed = false, reason = REASON_ISSUANCE_DEFERRED))

                else -> {
                    val complete = issuedIdentifiers.size == nonDeferred
                    listOf(
                        entry(
                            completed = complete,
                            reason = if (complete) null else failureReason ?: REASON_ISSUANCE_FAILED,
                            requestedCount = nonDeferred,
                        )
                    ) + deferredDocumentIds.map(::awaiting)
                }
            }
        }
        write(rows)
    }

    /** Who issued, as the rows name them — kept with each document too; see [EudiDocumentMetadata.issuerParty]. */
    suspend fun issuerParty(): IssuerPartyRecord = mutex.withLock { party() }

    private fun party() = registration.toIssuerPartyRecord(fallbackName = issuerName, fallbackLanguage = issuerNameLanguage)

    private fun entry(completed: Boolean, reason: String?, requestedCount: Int = requested) =
        IosTransactionRecord.Issuance(
            id = id,
            timeEpochMillis = time.toEpochMilliseconds(),
            completed = completed,
            reason = reason,
            reissuance = reissuance,
            requestedCount = requestedCount,
            issuedCount = issuedIdentifiers.size,
            credentialIdentifiers = issuedIdentifiers.toList(),
            userTriggered = userTriggered,
            issuer = party(),
        )

    /** Always an issuance, as wallet-core's: its collection cannot tell it came from a re-issuance. */
    private fun awaiting(documentId: String) = IosTransactionRecord.Issuance(
        id = deferredRowId(documentId),
        timeEpochMillis = time.toEpochMilliseconds(),
        completed = false,
        reason = REASON_ISSUANCE_DEFERRED,
        requestedCount = 1,
        issuedCount = 0,
        userTriggered = userTriggered,
        issuer = party(),
    )

    private suspend fun write(rows: List<IosTransactionRecord>) {
        runCatching { rows.forEach { store().recordTransaction(it) } }
            .onFailure { Logger.w(TAG, "could not record the issuance", it) }
    }

    private companion object {
        const val TAG = "IosIssuanceLog"
    }
}

/**
 * Brings a deferred credential's "awaiting" row up to date once its collection has ended — wallet-core's
 * `deferredResolutionEntry`: completed with the credential, or not completed with why. The issuer and
 * `isUserTriggered` are the awaiting row's; a credential parked before rows were kept is named from its
 * metadata, in [language].
 */
internal suspend fun MultipazWalletStore.recordDeferredResolution(
    document: Document,
    completed: Boolean,
    reason: String?,
    now: Instant = Clock.System.now(),
    language: String = iosUserLanguage(),
) {
    runCatching {
        val id = deferredRowId(document.identifier)
        val awaiting = transactionRecord(id) as? IosTransactionRecord.Issuance
        val metadata = document.eudiMetadata
        val issuerDisplay = document.localizedIssuerDisplay(language)
        // A session that deferred everything wrote no awaiting row; the document kept what it would have
        // said, as wallet-core keeps the registration with a deferred document.
        recordTransaction(
            IosTransactionRecord.Issuance(
                id = id,
                timeEpochMillis = now.toEpochMilliseconds(),
                completed = completed,
                reason = if (completed) null else reason ?: REASON_DEFERRED_COLLECTION_FAILED,
                requestedCount = 1,
                issuedCount = if (completed) 1 else 0,
                credentialIdentifiers = listOfNotNull(metadata?.format?.identifier.takeIf { completed }),
                userTriggered = awaiting?.userTriggered ?: metadata?.userTriggered,
                issuer = awaiting?.issuer
                    ?: metadata?.issuerParty
                    ?: IssuerPartyRecord(name = issuerDisplay?.name, nameLanguage = issuerDisplay?.locale),
            )
        )
    }.onFailure { Logger.w("IosIssuanceLog", "could not record the deferred collection", it) }
}

/** wallet-core's `deferredTxId`: a deferred credential's row, so its collection updates the same one. */
internal fun deferredRowId(documentId: String) = "deferred:$documentId"

/** wallet-core's reasons, word for word, so both platforms show the same text. */
internal const val REASON_ISSUANCE_FAILED = "Issuance did not complete"
internal const val REASON_ISSUANCE_DEFERRED = "Credential issuance deferred — awaiting the credential"
internal const val REASON_DEFERRED_COLLECTION_FAILED = "Could not collect the deferred credential"
