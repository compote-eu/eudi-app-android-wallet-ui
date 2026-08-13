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

import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.transactions_filter_item_status_completed
import eu.europa.ec.shared.resources.transactions_filter_item_status_failed
import eu.europa.ec.shared.resources.transactions_screen_filters_filter_by_transaction_type_issuance
import eu.europa.ec.shared.resources.transactions_screen_filters_filter_by_transaction_type_presentation
import eu.europa.ec.shared.resources.transactions_screen_filters_filter_by_transaction_type_signing

/**
 * The status's display label, resolved from the shared string catalog.
 *
 * The `ResourceProvider` overload of this stays in `:dashboard-feature` for the Android screens that
 * still use it; this is the form the now-shared transactions interactor needs.
 */
fun TransactionStatusUi.toUiText(strings: StringCatalog): String = when (this) {
    TransactionStatusUi.Completed -> strings[Res.string.transactions_filter_item_status_completed]
    TransactionStatusUi.Failed -> strings[Res.string.transactions_filter_item_status_failed]
}

/** The transaction type's display label, likewise from the catalog. */
fun TransactionTypeUi.toUiText(strings: StringCatalog): String = when (this) {
    TransactionTypeUi.ISSUANCE ->
        strings[Res.string.transactions_screen_filters_filter_by_transaction_type_issuance]

    TransactionTypeUi.PRESENTATION ->
        strings[Res.string.transactions_screen_filters_filter_by_transaction_type_presentation]

    TransactionTypeUi.SIGNING ->
        strings[Res.string.transactions_screen_filters_filter_by_transaction_type_signing]
}
