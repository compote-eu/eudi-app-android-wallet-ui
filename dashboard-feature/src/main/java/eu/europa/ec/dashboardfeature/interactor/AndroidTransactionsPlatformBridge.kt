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
import eu.europa.ec.corelogic.model.TransactionLogDataDomain
import eu.europa.ec.corelogic.model.TransactionLogDataDomain.Companion.getTransactionDocumentNames
import eu.europa.ec.dashboardfeature.ui.transactions.model.toTransactionStatusUi
import eu.europa.ec.dashboardfeature.ui.transactions.model.toTransactionTypeUi
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import kotlinx.datetime.toKotlinLocalDateTime

/**
 * Android's [TransactionsPlatformBridge]: reads wallet-core's transaction log and reduces each entry to
 * the neutral [PlatformTransactionLog].
 *
 * This mapping is the whole reason the bridge exists. `TransactionLogDataDomain` is built on wallet-core's
 * `TransactionLog`/`PresentedDocument` types and `java.time`, none of which can cross into commonMain —
 * and **this is where the java.time-to-kotlinx conversion now happens**, which is what let the interactor
 * drop java.time entirely.
 */
class AndroidTransactionsPlatformBridge(
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
    private val resourceProvider: ResourceProvider,
) : TransactionsPlatformBridge {

    override fun localeTag(): String = resourceProvider.getLocale().toLanguageTag()

    override suspend fun getTransactionLogs(): List<PlatformTransactionLog> =
        walletCoreDocumentsController.getTransactionLogs().map { transaction ->
            PlatformTransactionLog(
                id = transaction.id,
                name = transaction.name,
                status = transaction.status.toTransactionStatusUi(),
                type = transaction.toTransactionTypeUi(),
                createdAt = transaction.creationLocalDateTime.toKotlinLocalDateTime(),
                relyingPartyName = when (transaction) {
                    // TODO Update this once Core supports Issuance transactions
                    is TransactionLogDataDomain.IssuanceLog -> null
                    is TransactionLogDataDomain.PresentationLog -> transaction.relyingParty.name
                    is TransactionLogDataDomain.SigningLog -> null
                },
                documentNames = transaction.getTransactionDocumentNames(
                    userLocale = resourceProvider.getLocale()
                ),
            )
        }
}
