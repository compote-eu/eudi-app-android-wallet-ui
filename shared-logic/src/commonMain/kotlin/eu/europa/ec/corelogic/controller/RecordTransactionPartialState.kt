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

// Declared beside `WalletCoreTransactionRecordingController` upstream; here in shared code, same package,
// because the shared transaction-details interactor and the screens it serves read it on both platforms.
package eu.europa.ec.corelogic.controller

/** Whether a data-deletion request or a transaction report was saved in the wallet's transaction history. */
sealed interface RecordTransactionPartialState {
    data object Success : RecordTransactionPartialState
    data class Failure(val errorMessage: String) : RecordTransactionPartialState
}
