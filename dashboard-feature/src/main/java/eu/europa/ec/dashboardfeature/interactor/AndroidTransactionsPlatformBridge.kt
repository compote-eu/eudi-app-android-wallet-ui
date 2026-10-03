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

package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.ClaimRefDomain
import eu.europa.ec.corelogic.model.CredentialClaimsDomain
import eu.europa.ec.corelogic.model.CredentialRefDomain
import eu.europa.ec.corelogic.model.InteractingPartyDomain
import eu.europa.ec.corelogic.model.LocalizedTextDomain
import eu.europa.ec.corelogic.model.TransactionLogDataDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcFormat
import eu.europa.ec.eudi.wallet.transactionLogging.TransactionLog
import kotlinx.datetime.toKotlinLocalDateTime

/**
 * Android's [TransactionsPlatformBridge]: reads wallet-core's transaction log as the shared domain.
 *
 * The mapping below is an adapter for wallet-core 0.30.2, whose log holds presentations only, as
 * `TransactionLogDataDomain`. wallet-core 0.31.0 replaces that log with TS10 entries, which
 * `:core-logic` maps into [TransactionLogDomain] itself; this adapter goes with that bump.
 */
class AndroidTransactionsPlatformBridge(
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
) : TransactionsPlatformBridge {

    override suspend fun getTransactionLogs(): List<TransactionLogDomain> =
        walletCoreDocumentsController.getTransactionLogs().mapNotNull { transaction ->
            when (transaction) {
                is TransactionLogDataDomain.PresentationLog -> transaction.toTransactionLogDomain()
                // Never produced by 0.30.2: its log has no writer for either.
                is TransactionLogDataDomain.IssuanceLog,
                is TransactionLogDataDomain.SigningLog -> null
            }
        }

    override suspend fun getTransactionLog(id: String): TransactionLogDomain? =
        (walletCoreDocumentsController.getTransactionLog(id) as? TransactionLogDataDomain.PresentationLog)
            ?.toTransactionLogDomain()

    override suspend fun deleteTransactionLog(id: String) =
        walletCoreDocumentsController.deleteTransactionLog(id)
}

private fun TransactionLogDataDomain.PresentationLog.toTransactionLogDomain(): TransactionLogDomain.Presentation {
    val presented = documents.map { document ->
        CredentialClaimsDomain(
            credential = CredentialRefDomain(
                identifier = when (val format = document.format) {
                    is MsoMdocFormat -> format.docType
                    is SdJwtVcFormat -> format.vct
                    else -> ""
                },
            ),
            claims = document.claims.map { claim ->
                ClaimRefDomain(segments = claim.path.map { segment -> ClaimPathSegment.Key(segment) })
            },
        )
    }
    return TransactionLogDomain.Presentation(
        id = id,
        time = creationLocalDateTime.toKotlinLocalDateTime(),
        result = when (status) {
            TransactionLog.Status.Completed -> TransactionResultDomain.Completed
            TransactionLog.Status.Incomplete,
            TransactionLog.Status.Error -> TransactionResultDomain.NotCompleted(reason = null)
        },
        party = InteractingPartyDomain(
            // 0.30.2 records the name without a language.
            name = relyingParty.name.takeIf { it.isNotBlank() }?.let { LocalizedTextDomain("und", it) },
            identifier = null,
            contacts = emptyList(),
        ),
        partyType = null,
        intermediary = null,
        registration = null,
        // 0.30.2 records what was presented only.
        claimsRequested = presented,
        claimsPresented = presented,
    )
}
