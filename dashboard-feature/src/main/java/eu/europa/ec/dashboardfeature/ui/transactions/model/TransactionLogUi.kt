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

package eu.europa.ec.dashboardfeature.ui.transactions.model

import eu.europa.ec.corelogic.model.TransactionLogDataDomain
import eu.europa.ec.eudi.wallet.transactionLogging.TransactionLog
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.transactions_filter_item_status_completed
import eu.europa.ec.shared.resources.transactions_filter_item_status_failed

/**
 * The status's display label. Lifted out of `TransactionStatusUi`'s companion when the enum moved to
 * commonMain — it needs a ResourceProvider, which is Android-side. Same package, so the only change at
 * the call site is dropping `.Companion` from the import.
 */
fun TransactionStatusUi.toUiText(resourceProvider: ResourceProvider): String {
    return when (this) {
        TransactionStatusUi.Completed -> resourceProvider.getString(Res.string.transactions_filter_item_status_completed)
        TransactionStatusUi.Failed -> resourceProvider.getString(Res.string.transactions_filter_item_status_failed)
    }
}

fun TransactionLog.Status.toTransactionStatusUi(): TransactionStatusUi {
    return when (this) {
        TransactionLog.Status.Incomplete, TransactionLog.Status.Error -> TransactionStatusUi.Failed
        TransactionLog.Status.Completed -> TransactionStatusUi.Completed
    }
}

fun TransactionLogDataDomain.toTransactionTypeUi(): TransactionTypeUi {
    return when (this) {
        is TransactionLogDataDomain.IssuanceLog -> TransactionTypeUi.ISSUANCE
        is TransactionLogDataDomain.PresentationLog -> TransactionTypeUi.PRESENTATION
        is TransactionLogDataDomain.SigningLog -> TransactionTypeUi.SIGNING
    }
}