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
     * iOS-only, Option 4 (`wiki/IOS_NFC_PLAN.md` §9): cold-tap NFC engagement just completed and the
     * wallet is waiting for the reader's BLE connection — mirrors
     * [eu.europa.ec.shared.wallet.multipaz.IosProximityState.Connecting]. Not [Connected]: no request
     * has arrived yet, so there is nothing for the request screen to show. Android never emits this —
     * it has no cold-tap engagement — so it is a no-op there, same as [NfcNotice] already is.
     */
    data object Connecting : ProximityQRPartialState()

    /**
     * A one-shot, non-fatal notice about NFC data retrieval specifically — the switch was on, but
     * the platform could not actually start it (unsupported device, entitlement not yet granted,
     * a transient failure, ...). Distinct from [Error]: that one means the whole exchange failed,
     * this one means only NFC did — the reader can still connect over BLE. Only ever emitted where
     * [ProximityQRInteractor.isNfcDataRetrievalAvailable] is `true` in the first place; see
     * `wiki/IOS_NFC_PLAN.md`'s Findings A/B/D/E notes.
     */
    data class NfcNotice(val message: String) : ProximityQRPartialState()

    /**
     * iOS-only, real-device finding (`wiki/IOS_NFC_PLAN.md` §9): `CardSession` ended on its own — the
     * user cancelled the system NFC sheet, or it timed out — mirrors
     * [eu.europa.ec.shared.wallet.multipaz.IosProximityPresenter.nfcEngagementDisabledUnexpectedly].
     * The platform side already turned `nfcEngagementEnabled` off and re-armed nothing; this only tells
     * the screen to follow suit so the "Share over NFC" switch doesn't keep showing on while NFC isn't
     * actually listening. Android never emits this — same no-op reasoning as [Connecting]/[NfcNotice].
     */
    data object NfcEngagementDisabledUnexpectedly : ProximityQRPartialState()
}

interface ProximityQRInteractor : ScopedPresentationInteractor {
    fun startQrEngagement(): Flow<ProximityQRPartialState>
    fun toggleNfcEngagement(
        componentActivity: PlatformActivity,
        toggle: Boolean
    )

    /**
     * Whether ISO 18013-5 Annex C cold-tap NFC engagement — tapping the phone against a reader's
     * device as an alternative to scanning the QR, not a transport choice within it — is offered on
     * this platform. Deliberately a *different* capability from [toggleNfcEngagement] above, not a
     * rename of it: that one is Android's narrower NFC engagement/handover (a tap that only hands
     * over BLE connection parameters; see `wiki/IOS_NFC_PLAN.md` §1). `false` on every platform that
     * doesn't have cold-tap engagement yet; see `wiki/IOS_NFC_PLAN.md` §9.
     */
    fun isNfcDataRetrievalAvailable(): Boolean

    /**
     * Arms or disarms cold-tap (Annex C) NFC engagement for as long as the proximity screen is open —
     * a no-op wherever [isNfcDataRetrievalAvailable] is `false`. Takes effect immediately, not only on
     * the next [startQrEngagement]: the current engagement is reconciled with the new value right
     * away; see `wiki/IOS_NFC_PLAN.md` §9.
     */
    fun toggleNfcDataRetrieval(enabled: Boolean)

    /**
     * Whether cold-tap NFC engagement is enabled right now — the switch's actual current position, not
     * just whether the platform offers the switch at all ([isNfcDataRetrievalAvailable]). `false`
     * on every platform that doesn't have the feature, same as [isNfcDataRetrievalAvailable].
     */
    fun isNfcDataRetrievalEnabled(): Boolean

    fun cancelTransfer()
    fun setConfig(config: RequestUriConfig)
}
