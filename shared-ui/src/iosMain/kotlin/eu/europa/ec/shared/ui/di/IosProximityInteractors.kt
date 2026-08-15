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

// The four proximity interactors on iOS. Each is the thin half of its Android counterpart: the Android
// implementations drive a `WalletCorePresentationController` resolved from a Koin presentation scope,
// and these drive the one `IosProximityCoordinator` instead. Everything with a decision in it lives
// there, so these files stay a mapping of one contract onto one call.
package eu.europa.ec.shared.ui.di

import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.interactor.ScopedPresentationInteractor
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingObserveResponsePartialState
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingSendRequestedDocumentPartialState
import eu.europa.ec.proximityfeature.interactor.ProximityQRInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityQRPartialState
import eu.europa.ec.proximityfeature.interactor.ProximityRequestInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityRequestInteractorPartialState
import eu.europa.ec.proximityfeature.interactor.ProximitySuccessInteractor
import eu.europa.ec.proximityfeature.interactor.ProximitySuccessInteractorGetUiItemsPartialState
import eu.europa.ec.shared.platform.PlatformActivity
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The scope id every presentation interactor carries.
 *
 * On Android it selects a Koin scope holding that exchange's controller. iOS has one presenter for the
 * one radio, so the id is remembered and handed back unchanged — the shared view-models put it in their
 * routes, and a screen that navigated with one id must find the same one on the way back.
 */
internal abstract class IosScopedProximityInteractor : ScopedPresentationInteractor {
    private var scopeId: String = ""

    override val presentationScopeId: String get() = scopeId

    override fun setScopeId(scopeId: String) {
        this.scopeId = scopeId
    }
}

internal class IosProximityQRInteractor(
    private val coordinator: IosProximityCoordinator,
) : IosScopedProximityInteractor(), ProximityQRInteractor {

    override fun setConfig(config: RequestUriConfig) = setScopeId(config.presentationScopeId)

    override fun startQrEngagement(): Flow<ProximityQRPartialState> = coordinator.qrEvents()

    /**
     * Nothing to toggle. iOS gives no app NFC card emulation, so a phone cannot be the mdoc side of an
     * NFC engagement; the QR screen already hides the switch, and [PlatformActivity] has no iOS instance
     * to call this with anyway.
     */
    override fun toggleNfcEngagement(componentActivity: PlatformActivity, toggle: Boolean) = Unit

    override fun cancelTransfer() = coordinator.cancel()
}

internal class IosProximityRequestInteractor(
    private val coordinator: IosProximityCoordinator,
) : IosScopedProximityInteractor(), ProximityRequestInteractor {

    override fun getRequestDocuments(): Flow<ProximityRequestInteractorPartialState> =
        coordinator.requestEvents()

    override fun updateRequestedDocuments(selectedCombination: RequestCombinationUi?) =
        coordinator.updateSelection(selectedCombination)

    override fun stopPresentation() = coordinator.cancel()
}

internal class IosProximityLoadingInteractor(
    private val coordinator: IosProximityCoordinator,
) : IosScopedProximityInteractor(), ProximityLoadingInteractor {

    override fun observeResponse(): Flow<ProximityLoadingObserveResponsePartialState> =
        coordinator.sendEvents()

    override suspend fun sendRequestedDocuments(): ProximityLoadingSendRequestedDocumentPartialState =
        coordinator.send()

    /**
     * Unreachable, and reports failure rather than pretending. The shared view-model only calls this
     * after `observeResponse` asks for authentication, which iOS never does — multipaz's
     * `SecureEnclaveSecureArea` raises its own prompt when it signs the response.
     */
    override fun handleUserAuthentication(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) = resultHandler.onAuthenticationFailure()
}

internal class IosProximitySuccessInteractor(
    private val coordinator: IosProximityCoordinator,
) : IosScopedProximityInteractor(), ProximitySuccessInteractor {

    override fun getUiItems(): Flow<ProximitySuccessInteractorGetUiItemsPartialState> = flow {
        emit(coordinator.successItems())
    }

    override fun stopPresentation() = coordinator.cancel()
}
