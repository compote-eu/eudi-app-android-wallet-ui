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

import eu.europa.ec.corelogic.model.ClaimRefDomain
import eu.europa.ec.corelogic.model.CredentialClaimsDomain
import eu.europa.ec.corelogic.model.CredentialRefDomain
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.presentment.CredentialQueryResult
import org.multipaz.presentment.CredentialSelection
import org.multipaz.util.Logger
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The History row of one presentation — wallet-core 0.31.0's `PresentationLogListener`, whose rules this
 * follows rather than chooses, for all three ways iOS presents (OpenID4VP, ISO 18013-5 proximity and the
 * Digital Credentials API):
 *
 * - a row exists once a request the wallet can answer has arrived ([requestReceived]), written ahead as
 *   not completed, so an exchange the process does not survive still shows; a request refused before
 *   consent — an untrusted verifier — leaves none, as Android's unprocessable request leaves none;
 * - it records what the consent screen asked for and, separately, what was sent ([presented]);
 * - it ends once: completed when the response went out, otherwise not completed with wallet-core's
 *   reason — stopped, rejected by the verifier, a transfer error, or nothing to share.
 *
 * A failure to write is logged and never fails the presentation.
 */
internal class IosPresentationLog(private val store: suspend () -> MultipazWalletStore) {

    @OptIn(ExperimentalUuidApi::class)
    private val id = Uuid.random().toString()
    private val mutex = Mutex()
    private var row: IosTransactionRecord.Presentation? = null
    private var isFinished = false

    /**
     * A request the wallet can answer arrived and is about to be shown. A second request in the same
     * exchange updates the same row.
     *
     * @param requesterName what the consent screen calls the relying party.
     * @param party what its registration certificate says, when the check read one.
     */
    suspend fun requestReceived(
        requesterName: String?,
        party: PresentationPartyRecord?,
        request: CredentialQueryResult,
    ) = update { current ->
        IosTransactionRecord.Presentation(
            id = id,
            timeEpochMillis = current?.timeEpochMillis ?: Clock.System.now().toEpochMilliseconds(),
            completed = false,
            requesterName = requesterName,
            party = party,
            claimsRequested = request.requestedClaimsRecords(),
            transactionData = request.transactionDataRecords(),
        )
    }

    /**
     * The wallet holds nothing the request asks for. wallet-core still records the request, with this
     * reason, and keeps it through the stop that follows.
     */
    suspend fun nothingToShare(requesterName: String?, party: PresentationPartyRecord?) = finish { current ->
        (current ?: IosTransactionRecord.Presentation(
            id = id,
            timeEpochMillis = Clock.System.now().toEpochMilliseconds(),
            completed = false,
            requesterName = requesterName,
            party = party,
        )).copy(completed = false, reason = REASON_REQUEST_NOT_SATISFIABLE)
    }

    /** The user agreed; [selection] is what is sent. Recorded before it goes, as wallet-core records it. */
    suspend fun presented(selection: CredentialSelection) = update { current ->
        current?.copy(claimsPresented = selection.presentedClaimsRecords())
    }

    /** The response went out — and, for OpenID4VP, the verifier accepted it. */
    suspend fun completed() = finish { current -> current?.copy(completed = true, reason = null) }

    /** The verifier refused the response. */
    suspend fun rejected() = finish { current -> current?.copy(completed = false, reason = REASON_VERIFIER_REJECTED) }

    /** The exchange failed. */
    suspend fun failed(error: Throwable) = finish { current ->
        current?.copy(completed = false, reason = error.noncompletionReason(default = REASON_TRANSFER_ERROR))
    }

    /**
     * The exchange ended without a response: declined, abandoned or torn down. wallet-core keeps a reason
     * the row already had; here every other ending finishes the row itself, so a later stop changes nothing.
     */
    suspend fun stopped() = finish { current -> current?.copy(completed = false, reason = REASON_STOPPED) }

    private suspend fun update(change: (IosTransactionRecord.Presentation?) -> IosTransactionRecord.Presentation?) {
        val written = mutex.withLock {
            if (isFinished) return
            change(row)?.also { row = it }
        } ?: return
        write(written)
    }

    private suspend fun finish(change: (IosTransactionRecord.Presentation?) -> IosTransactionRecord.Presentation?) {
        val written = mutex.withLock {
            if (isFinished) return
            isFinished = true
            change(row)?.also { row = it }
        } ?: return
        write(written)
    }

    private suspend fun write(record: IosTransactionRecord.Presentation) {
        runCatching { store().recordTransaction(record) }
            .onFailure { Logger.w(TAG, "could not record the presentation", it) }
    }

    private companion object {
        const val TAG = "IosPresentationLog"
    }
}

/** What a relying party's registration says about it, when the check read a certificate — verified or not. */
internal fun RelyingPartyRegistrationOutcome.partyRecord(): PresentationPartyRecord? = when (this) {
    is RelyingPartyRegistrationOutcome.Verified -> registration
    is RelyingPartyRegistrationOutcome.Failed -> registration
    RelyingPartyRegistrationOutcome.NotChecked, RelyingPartyRegistrationOutcome.NotOffered -> null
}?.toPresentationPartyRecord()

internal fun CredentialClaimsRecord.toDomain() = CredentialClaimsDomain(
    credential = CredentialRefDomain(identifier = credential),
    claims = claims.map { segments -> ClaimRefDomain(segments = segments.map { it.toDomain() }) },
)

/** wallet-core's reasons, word for word, so both platforms show the same text. */
internal const val REASON_STOPPED = "Presentation stopped before completion"
internal const val REASON_TRANSFER_ERROR = "Transfer error"
internal const val REASON_VERIFIER_REJECTED = "Verifier rejected the response"
internal const val REASON_REQUEST_NOT_SATISFIABLE = "Request could not be satisfied — no matching credential available"
