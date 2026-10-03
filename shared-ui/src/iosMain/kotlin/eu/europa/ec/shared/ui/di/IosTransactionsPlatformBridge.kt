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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.dashboardfeature.interactor.TransactionsPlatformBridge
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine

/**
 * iOS's [TransactionsPlatformBridge], reading multipaz's event log.
 *
 * The counterpart of `AndroidTransactionsPlatformBridge`: wallet-core keeps its own log on Android,
 * while here multipaz writes one as a side effect of presenting and issuing, and
 * `IosTransactionLogDomain.kt` in :shared-logic maps those events into the shared domain.
 */
internal class IosTransactionsPlatformBridge(
    private val engine: IosWalletEngine,
) : TransactionsPlatformBridge {

    override suspend fun getTransactionLogs(): List<TransactionLogDomain> = engine.getTransactionLogs()

    override suspend fun getTransactionLog(id: String): TransactionLogDomain? = engine.getTransactionLog(id)

    override suspend fun deleteTransactionLog(id: String) = engine.deleteTransactionLog(id)
}
