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

// Phase 3b: the interactor *contract* moves to commonMain so `HomeViewModel` can live there,
// following the `SplashInteractor` pattern — the Android `HomeInteractorImpl` (BluetoothManager via
// ResourceProvider's Context, WalletCoreConfig) stays in :dashboard-feature along with its Koin
// provider, and iOS will supply its own. Every signature here is already platform-neutral: the BLE
// queries answer Booleans, and the PID lookup goes through the app-owned `WalletEngine` seam, so this
// interactor has no wallet-core types in it. Package unchanged.
package eu.europa.ec.dashboardfeature.interactor

import kotlinx.coroutines.flow.Flow

sealed class HomeInteractorGetUserNameViaMainPidDocumentPartialState {
    data class Success(
        val userFirstName: String,
    ) : HomeInteractorGetUserNameViaMainPidDocumentPartialState()

    data class Failure(
        val error: String
    ) : HomeInteractorGetUserNameViaMainPidDocumentPartialState()
}

interface HomeInteractor {
    fun isBleAvailable(): Boolean

    /**
     * Whether this platform can sign documents (RQES) at all.
     *
     * False omits the sign card from Home entirely, rather than leaving a card that navigates to a
     * route the platform has no entry for. The same "omit, don't show inert" rule the settings
     * screen uses for registration checks.
     */
    fun canSignDocuments(): Boolean
    fun isBleCentralClientModeEnabled(): Boolean
    fun getUserNameViaMainPidDocument(): Flow<HomeInteractorGetUserNameViaMainPidDocumentPartialState>
}
