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

package eu.europa.ec.corelogic.worker

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.shared.wallet.revocation.DocumentStatusDomain
import eu.europa.ec.shared.wallet.revocation.RevocationActionDomain
import eu.europa.ec.shared.wallet.revocation.StatusSignerTrustDomain
import eu.europa.ec.shared.wallet.revocation.StatusTrustPolicyDomain
import eu.europa.ec.shared.wallet.revocation.revocationAction
import eu.europa.ec.corelogic.model.RevokedDocumentParcel
import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.corelogic.util.CoreActions.REVOCATION_IDS_DETAILS_EXTRA
import eu.europa.ec.eudi.statium.Status
import eu.europa.ec.eudi.wallet.document.IssuedDocument
import eu.europa.ec.storagelogic.dao.RevokedDocumentDao
import eu.europa.ec.storagelogic.model.RevokedDocument
import org.koin.android.annotation.KoinWorker

/**
 * [RevocationWorkManager] is a [CoroutineWorker] responsible for checking the revocation status of issued documents
 * and updating the local storage and sending broadcasts when revocations are detected.
 *
 * It utilizes Koin for dependency injection to obtain instances of [eu.europa.ec.storagelogic.dao.RevokedDocumentDao] and [WalletCoreDocumentsController].
 *
 * Key functionalities:
 *  - Periodically retrieves all issued documents from the [WalletCoreDocumentsController].
 *  - Checks the status of each document for revocation using [WalletCoreDocumentsController.resolveDocumentStatus].
 *  - Identifies documents with statuses [Status.Invalid] or [Status.Suspended] as revoked.
 *  - Stores revoked documents in the [eu.europa.ec.storagelogic.dao.RevokedDocumentDao].
 *  - Sends three broadcasts to notify the application about the revoked documents:
 *      - `CoreActions.REVOCATION_WORK_MESSAGE_ACTION`: Includes a list of [RevokedDocumentPayload] with names and IDs.
 *      - `CoreActions.REVOCATION_WORK_REFRESH_ACTION`: A general refresh action without specific data.
 *      - `CoreActions.REVOCATION_WORK_REFRESH_DETAILS_ACTION`: Includes a list of revoked document IDs in `REVOCATION_IDS_DETAILS_EXTRA`.
 *
 *  The worker returns:
 *      - [Result.success] if the revocation check and updates were successful.
 *      - [Result.failure] if an [IllegalArgumentException] occurred during the process.  This indicates a configuration or data issue.
 *
 * @param appContext The application context.
 * @param workerParams Parameters for the worker.
 */
/**
 * wallet-core's status, in the vocabulary the shared policy speaks.
 *
 * `Status` has more cases than the wallet acts on; everything outside the three below is a value this
 * wallet does not interpret, which is exactly [DocumentStatusDomain.Unknown].
 */
private fun Status.toDocumentStatusDomain(): DocumentStatusDomain = when (this) {
    is Status.Valid -> DocumentStatusDomain.Valid
    is Status.Invalid -> DocumentStatusDomain.Invalid
    is Status.Suspended -> DocumentStatusDomain.Suspended
    else -> DocumentStatusDomain.Unknown
}

@KoinWorker
class RevocationWorkManager(
    appContext: Context,
    workerParams: WorkerParameters,
    private val revokedDocumentDao: RevokedDocumentDao,
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
    private val walletEngine: WalletEngine,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val REVOCATION_WORK_NAME = "revocationWorker"
    }

    override suspend fun doWork(): Result {
        try {

            val storedRevokedDocuments = walletEngine.getRevokedDocumentIds()
            val fromRevokedToValid = mutableListOf<String>()
            val revokedDocuments = mutableListOf<IssuedDocument>()

            walletCoreDocumentsController
                .getAllIssuedDocuments()
                .forEach { document ->
                    walletCoreDocumentsController.resolveDocumentStatus(document).fold(
                        onSuccess = { status ->
                            // The decision is shared with iOS rather than written twice: both
                            // platforms map their library's result onto the same reading and ask
                            // `revocationAction`. See `eu.europa.ec.shared.wallet.revocation`, whose
                            // tests run on the JVM and on Kotlin/Native.
                            //
                            // `NotEvaluated` is the honest trust value here, not a placeholder:
                            // wallet-core *does* evaluate the signer's chain, but under
                            // `TrustPolicy.Action.INFORM` it logs the result and discards it, so
                            // `resolveStatus` returns a bare status and this worker cannot see it.
                            // Set the resolver's policy to ENFORCE in `WalletCoreConfigImpl` and an
                            // untrusted list arrives as a failure below instead.
                            val action = revocationAction(
                                status = status.toDocumentStatusDomain(),
                                signerTrust = StatusSignerTrustDomain.NotEvaluated,
                                policy = StatusTrustPolicyDomain.Inform,
                                currentlyFlagged = storedRevokedDocuments.any { it == document.id },
                            )
                            when (action) {
                                RevocationActionDomain.Flag -> revokedDocuments.add(document)
                                RevocationActionDomain.Clear -> fromRevokedToValid.add(document.id)
                                RevocationActionDomain.Leave -> {}
                            }
                        },
                        // A trust failure under ENFORCE lands here (StatusListNotTrustedException),
                        // as does any transport or parse error. Leaving the document as it was is the
                        // same outcome the shared policy produces for a reading it may not use.
                        onFailure = {}
                    )
                }

            if (fromRevokedToValid.isNotEmpty()) {
                removeRevokedDocumentsFromStorage(fromRevokedToValid)
            }

            if (revokedDocuments.isNotEmpty()) {
                storeRevokedDocuments(revokedDocuments)
                sendRevocationBroadcasts(revokedDocuments)
            }

            if (fromRevokedToValid.isNotEmpty() || revokedDocuments.isNotEmpty()) {
                notifyDocumentsList()
            }

            return Result.success()
        } catch (_: IllegalArgumentException) {
            return Result.failure()
        }
    }

    @Throws(IllegalArgumentException::class)
    private suspend fun storeRevokedDocuments(revokedDocuments: List<IssuedDocument>) {
        revokedDocumentDao.storeAll(
            revokedDocuments.map { RevokedDocument(identifier = it.id) }
        )
    }

    @Throws(IllegalArgumentException::class)
    private suspend fun removeRevokedDocumentsFromStorage(ids: List<String>) {
        ids.forEach {
            revokedDocumentDao.delete(it)
        }
    }

    private fun sendRevocationBroadcasts(revokedDocuments: List<IssuedDocument>) {

        val messageIntent = Intent(CoreActions.REVOCATION_WORK_MESSAGE_ACTION).apply {
            setPackage(applicationContext.packageName)
            putParcelableArrayListExtra(
                CoreActions.REVOCATION_IDS_EXTRA,
                ArrayList(
                    revokedDocuments.map {
                        RevokedDocumentParcel(name = it.name, id = it.id)
                    }
                )
            )
        }

        val detailsIntent = Intent(CoreActions.REVOCATION_WORK_REFRESH_DETAILS_ACTION).apply {
            setPackage(applicationContext.packageName)
            putStringArrayListExtra(
                REVOCATION_IDS_DETAILS_EXTRA,
                ArrayList(
                    revokedDocuments.map { it.id }
                )
            )
        }

        applicationContext.sendBroadcast(messageIntent)
        applicationContext.sendBroadcast(detailsIntent)
    }

    private fun notifyDocumentsList() {
        val refreshIntent = Intent(CoreActions.REVOCATION_WORK_REFRESH_ACTION).apply {
            setPackage(applicationContext.packageName)
        }
        applicationContext.sendBroadcast(refreshIntent)
    }
}