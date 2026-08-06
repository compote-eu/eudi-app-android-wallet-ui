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

// The two transaction-log enums, which `TransactionUi` and the transactions view-model need from
// commonMain. Their mappings do NOT come along: one reads wallet-core's `TransactionLog.Status`, one
// reads `TransactionLogDataDomain`, and `toUiText` needs a ResourceProvider — all three stay in
// :dashboard-feature. Package unchanged.
package eu.europa.ec.dashboardfeature.ui.transactions.model

enum class TransactionStatusUi {
    Completed, Failed
}

enum class TransactionTypeUi {
    PRESENTATION,
    ISSUANCE,
    SIGNING;
}
