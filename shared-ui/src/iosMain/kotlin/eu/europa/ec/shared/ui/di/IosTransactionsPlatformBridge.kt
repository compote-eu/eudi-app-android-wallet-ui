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

import eu.europa.ec.dashboardfeature.interactor.PlatformTransactionLog
import eu.europa.ec.dashboardfeature.interactor.TransactionsPlatformBridge
import eu.europa.ec.dashboardfeature.ui.transactions.model.TransactionStatusUi
import eu.europa.ec.dashboardfeature.ui.transactions.model.TransactionTypeUi
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.document_success_relying_party_default_name
import eu.europa.ec.shared.wallet.multipaz.IosTransaction
import eu.europa.ec.shared.wallet.multipaz.IosTransactionKind
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * iOS's [TransactionsPlatformBridge], reading multipaz's event log.
 *
 * The counterpart of `AndroidTransactionsPlatformBridge` and the same shape: one read, then a mapping
 * into the neutral [PlatformTransactionLog]. What differs is where the log comes from — wallet-core
 * keeps its own on Android, while here multipaz writes one as a side effect of presenting and issuing,
 * so `MultipazWalletStore` supplies the logger and this reads it back.
 *
 * **Every entry is `Completed`.** multipaz records an event only once the work has succeeded — after a
 * response has gone out, after credentials are certified — so a failed or cancelled exchange leaves no
 * trace. That is not a gap in this mapping: it matches Android, where a cancelled presentation also logs
 * nothing.
 */
internal class IosTransactionsPlatformBridge(
    private val engine: IosWalletEngine,
    private val strings: StringCatalog,
) : TransactionsPlatformBridge {

    override fun localeTag(): String = NSLocale.currentLocale.languageCode

    override suspend fun getTransactionLogs(): List<PlatformTransactionLog> =
        engine.getTransactions().map { it.toPlatformLog() }

    private fun IosTransaction.toPlatformLog() = PlatformTransactionLog(
        id = id,
        // The row's title, and the same rule Android follows: a presentation is named after the party
        // that asked. An issuance has nobody to name, so it takes the document's name instead — which
        // Android has no case for, since wallet-core logs presentations only.
        name = relyingPartyName
            ?: documentNames.firstOrNull()
            ?: strings[Res.string.document_success_relying_party_default_name],
        status = TransactionStatusUi.Completed,
        type = when (kind) {
            IosTransactionKind.Presentation -> TransactionTypeUi.PRESENTATION
            IosTransactionKind.Issuance -> TransactionTypeUi.ISSUANCE
        },
        createdAt = createdAt,
        relyingPartyName = relyingPartyName,
        documentNames = documentNames,
    )
}
