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

// Phase 3b: the interactor *contract* moves to commonMain so `ProximityQRViewModel` can live there,
// per the `SplashInteractor` pattern — `ProximityQRInteractorImpl` stays in :proximity-feature with its
// Koin provider, since it drives wallet-core's WalletCorePresentationController. Package unchanged.
//
// `toggleNfcEngagement` takes a `PlatformActivity`, the new opaque platform handle, instead of naming
// `ComponentActivity` directly. On Android that is an actual typealias for ComponentActivity, so the
// implementation and the screen are unchanged; it is what lets this contract — and therefore the
// view-model — compile for iOS, which has no Activity and its own proximity story.
package eu.europa.ec.proximityfeature.interactor

import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.interactor.ScopedPresentationInteractor
import eu.europa.ec.shared.platform.PlatformActivity
import kotlinx.coroutines.flow.Flow

sealed class ProximityQRPartialState {
    data class QrReady(val qrCode: String) : ProximityQRPartialState()
    data class Error(val error: String) : ProximityQRPartialState()
    data object Connected : ProximityQRPartialState()
    data object Disconnected : ProximityQRPartialState()
}

interface ProximityQRInteractor : ScopedPresentationInteractor {
    fun startQrEngagement(): Flow<ProximityQRPartialState>
    fun toggleNfcEngagement(
        componentActivity: PlatformActivity,
        toggle: Boolean
    )

    fun cancelTransfer()
    fun setConfig(config: RequestUriConfig)
}
