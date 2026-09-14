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

    /**
     * A one-shot, non-fatal notice about NFC data retrieval specifically — the switch was on, but
     * the platform could not actually start it (unsupported device, entitlement not yet granted,
     * a transient failure, ...). Distinct from [Error]: that one means the whole exchange failed,
     * this one means only NFC did — the reader can still connect over BLE. Only ever emitted where
     * [ProximityQRInteractor.isNfcDataRetrievalAvailable] is `true` in the first place; see
     * `wiki/IOS_NFC_PLAN.md`'s Findings A/B/D/E notes.
     */
    data class NfcNotice(val message: String) : ProximityQRPartialState()
}

interface ProximityQRInteractor : ScopedPresentationInteractor {
    fun startQrEngagement(): Flow<ProximityQRPartialState>
    fun toggleNfcEngagement(
        componentActivity: PlatformActivity,
        toggle: Boolean
    )

    /**
     * Whether ISO 18013-5 Annex 8 NFC data retrieval — the whole mdoc session over NFC, a sibling
     * transport to BLE — is offered on this platform. Deliberately a *different* capability from
     * [toggleNfcEngagement] above, not a rename of it: that one is Android's narrower NFC
     * engagement/handover (a tap that only hands over BLE connection parameters; see
     * `wiki/IOS_NFC_PLAN.md` §1). `false` on every platform that doesn't have the full transport yet.
     */
    fun isNfcDataRetrievalAvailable(): Boolean

    /**
     * Turns NFC data retrieval on or off for the *next* [startQrEngagement] — a no-op wherever
     * [isNfcDataRetrievalAvailable] is `false`.
     */
    fun toggleNfcDataRetrieval(enabled: Boolean)

    /**
     * Whether NFC data retrieval is enabled right now — the switch's actual current position, not
     * just whether the platform offers the switch at all ([isNfcDataRetrievalAvailable]). `false`
     * on every platform that doesn't have the feature, same as [isNfcDataRetrievalAvailable].
     */
    fun isNfcDataRetrievalEnabled(): Boolean

    fun cancelTransfer()
    fun setConfig(config: RequestUriConfig)
}
