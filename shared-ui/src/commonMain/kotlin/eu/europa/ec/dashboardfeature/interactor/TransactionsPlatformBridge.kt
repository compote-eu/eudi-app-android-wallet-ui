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

import eu.europa.ec.corelogic.model.TransactionLogDomain

/**
 * The one thing the transactions feature cannot do in shared code: read the wallet's transaction log.
 *
 * Each platform hands over the shared [TransactionLogDomain] — Android from wallet-core's log, iOS from
 * multipaz's events — so the mapping, filtering and grouping above it are shared.
 */
interface TransactionsPlatformBridge {

    /** The wallet's transaction log; order not guaranteed, the caller sorts. */
    suspend fun getTransactionLogs(): List<TransactionLogDomain>

    /** One entry, or null when the log has none with this id. */
    suspend fun getTransactionLog(id: String): TransactionLogDomain?

    /** Removes one entry from the log; an unknown id is not an error. */
    suspend fun deleteTransactionLog(id: String)
}
