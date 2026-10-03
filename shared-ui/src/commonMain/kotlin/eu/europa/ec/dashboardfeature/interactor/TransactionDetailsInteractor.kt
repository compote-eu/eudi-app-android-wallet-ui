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

// The contract is shared, and so — since upstream 7a47e46a reads nothing but the shared transaction
// log domain — is the one implementation (TransactionDetailsInteractorImpl.kt). Package unchanged.
package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsUi
import kotlinx.coroutines.flow.Flow
sealed class TransactionDetailsInteractorPartialState {
    data class Success(
        val transactionDetailsUi: TransactionDetailsUi,
    ) : TransactionDetailsInteractorPartialState()

    data class Failure(
        val error: String
    ) : TransactionDetailsInteractorPartialState()
}

sealed class TransactionDetailsInteractorDeleteTransactionPartialState {
    data object Success : TransactionDetailsInteractorDeleteTransactionPartialState()
    data class Failure(
        val errorMessage: String
    ) : TransactionDetailsInteractorDeleteTransactionPartialState()
}

interface TransactionDetailsInteractor {
    fun getTransactionDetails(
        transactionId: String
    ): Flow<TransactionDetailsInteractorPartialState>

    fun deleteTransaction(transactionId: String): Flow<TransactionDetailsInteractorDeleteTransactionPartialState>

}
