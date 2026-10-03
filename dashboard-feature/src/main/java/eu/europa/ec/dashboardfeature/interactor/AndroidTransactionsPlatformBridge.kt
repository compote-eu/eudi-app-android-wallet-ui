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

import eu.europa.ec.corelogic.controller.WalletCoreTransactionLogController
import eu.europa.ec.corelogic.model.TransactionLogDomain

/**
 * Android's [TransactionsPlatformBridge]: wallet-core's TS10 transaction log, which
 * [WalletCoreTransactionLogController] already maps into the shared domain.
 */
class AndroidTransactionsPlatformBridge(
    private val walletCoreTransactionLogController: WalletCoreTransactionLogController,
) : TransactionsPlatformBridge {

    override suspend fun getTransactionLogs(): List<TransactionLogDomain> =
        walletCoreTransactionLogController.getTransactionLogs()

    override suspend fun getTransactionLog(id: String): TransactionLogDomain? =
        walletCoreTransactionLogController.getTransactionLog(id)

    override suspend fun deleteTransactionLog(id: String) =
        walletCoreTransactionLogController.deleteTransactionLog(id)
}
