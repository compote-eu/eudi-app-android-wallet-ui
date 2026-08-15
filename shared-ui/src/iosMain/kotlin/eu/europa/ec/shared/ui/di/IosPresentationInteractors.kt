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

// The three remote-presentation interactors on iOS. Each is the thin half of its Android counterpart:
// the Android implementations drive a `WalletCorePresentationController` resolved from a Koin
// presentation scope, and these drive the one `IosRemotePresentationCoordinator` instead. Everything
// with a decision in it lives there, so these files stay a mapping of one contract onto one call —
// exactly as `IosProximityInteractors` does for the four proximity ones.
package eu.europa.ec.shared.ui.di

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.interactor.ScopedPresentationInteractor
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingObserveResponsePartialState
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingSendRequestedDocumentPartialState
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractorPartialState
import eu.europa.ec.presentationfeature.interactor.PresentationSuccessInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationSuccessInteractorGetUiItemsPartialState
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.uilogic.navigation.helper.IntentAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The scope id every presentation interactor carries.
 *
 * On Android it selects a Koin scope holding that exchange's controller. iOS has one presenter, so the
 * id is remembered and handed back unchanged — the shared view-models put it in their routes, and a
 * screen that navigated with one id must find the same one on the way back.
 */
internal abstract class IosScopedRemotePresentationInteractor : ScopedPresentationInteractor {
    private var scopeId: String = ""

    override val presentationScopeId: String get() = scopeId

    override fun setScopeId(scopeId: String) {
        this.scopeId = scopeId
    }
}

internal class IosPresentationRequestInteractor(
    private val coordinator: IosRemotePresentationCoordinator,
) : IosScopedRemotePresentationInteractor(), PresentationRequestInteractor {

    /**
     * Where the exchange begins: the config carries the verifier's link, so this is what turns a deep
     * link into a request on the wire.
     *
     * [intentAction] is ignored, and has nothing to ignore — it is the Digital Credentials API hand-off,
     * which exists only on Android; on iOS `PlatformIntent` is uninhabited, so the parameter is always
     * null here.
     */
    override fun setConfig(config: RequestUriConfig, intentAction: IntentAction?) {
        setScopeId(config.presentationScopeId)
        coordinator.start(config)
    }

    override fun getRequestDocuments(): Flow<PresentationRequestInteractorPartialState> =
        coordinator.requestEvents()

    override fun updateRequestedDocuments(selectedCombination: RequestCombinationUi?) =
        coordinator.updateSelection(selectedCombination)

    override fun stopPresentation() = coordinator.cancel()
}

internal class IosPresentationLoadingInteractor(
    private val coordinator: IosRemotePresentationCoordinator,
) : IosScopedRemotePresentationInteractor(), PresentationLoadingInteractor {

    override fun observeResponse(): Flow<PresentationLoadingObserveResponsePartialState> =
        coordinator.sendEvents()

    override suspend fun sendRequestedDocuments(): PresentationLoadingSendRequestedDocumentPartialState =
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

internal class IosPresentationSuccessInteractor(
    private val coordinator: IosRemotePresentationCoordinator,
) : IosScopedRemotePresentationInteractor(), PresentationSuccessInteractor {

    /** Where the flow returns when it completes — see the coordinator, which encodes it. */
    override val initiatorRoute: String get() = coordinator.initiatorRoute

    /** Where the verifier asked the user to be sent afterwards; usually nowhere, and then null. */
    override val redirectUri: String? get() = coordinator.redirectUri

    /**
     * Always null, and not a gap. On Android this is the Digital Credentials API's result intent, handed
     * back to the browser that invoked the wallet; iOS has no such caller and `PlatformIntent` has no
     * iOS instance to return.
     */
    override fun getPendingIntent(): PlatformIntent? = null

    override fun getUiItems(): Flow<PresentationSuccessInteractorGetUiItemsPartialState> = flow {
        emit(coordinator.successItems())
    }

    override fun stopPresentation() = coordinator.cancel()
}
