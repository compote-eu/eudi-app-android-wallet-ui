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

import eu.europa.ec.dashboardfeature.ui.transactions.model.TransactionStatusUi
import eu.europa.ec.dashboardfeature.ui.transactions.model.TransactionTypeUi
import kotlinx.datetime.LocalDateTime

/**
 * The one thing the transactions feature cannot do in shared code: read the wallet's transaction log.
 *
 * Deliberately a single member. `TransactionsInteractorImpl` is 555 lines but made exactly **one**
 * wallet-core call, so — as with `DocumentsPlatformBridge` — the mapping, filtering and grouping are
 * shared and only the read is not.
 */
interface TransactionsPlatformBridge {

    /** The wallet's transaction log, newest-first order not guaranteed; the caller sorts. */
    suspend fun getTransactionLogs(): List<PlatformTransactionLog>

    /** BCP-47 tag, used to localize the document names inside a log entry. */
    fun localeTag(): String
}

/**
 * One transaction log entry, reduced to what the interactor actually reads.
 *
 * `TransactionLogDataDomain` could not be shared — it is built on wallet-core's `TransactionLog` and
 * `PresentedDocument` types plus `java.time` — but measuring showed the interactor touches only these
 * seven things, so this is the whole contract rather than a lossy summary.
 *
 * Two deliberate choices:
 *  - **[type] is the enum, not its label.** The Android original resolved the label through
 *    `ResourceProvider` while mapping; carrying the enum instead lets the *shared* interactor resolve it
 *    from the string catalog, which keeps one more decision out of the platforms.
 *  - **[createdAt] is kotlinx.** The log hands Android `java.time.LocalDateTime`; converting at this
 *    boundary is what finally let the interactor drop java.time entirely.
 */
data class PlatformTransactionLog(
    val id: String,
    val name: String,
    val status: TransactionStatusUi,
    val type: TransactionTypeUi,
    val createdAt: LocalDateTime,
    /** Null for issuance and signing, which have no counterparty. */
    val relyingPartyName: String?,
    /** Already localized by the platform, since naming a document needs its issuer metadata. */
    val documentNames: List<String>,
)
