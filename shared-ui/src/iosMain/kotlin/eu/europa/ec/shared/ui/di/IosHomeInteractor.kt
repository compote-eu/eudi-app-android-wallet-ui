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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.businesslogic.extension.ioDispatcher
import eu.europa.ec.dashboardfeature.interactor.HomeInteractor
import eu.europa.ec.dashboardfeature.interactor.HomeInteractorGetUserNameViaMainPidDocumentPartialState
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringResolver
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.uilogic.component.IosBluetooth
import eu.europa.ec.uilogic.component.isBluetoothUsable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * iOS's [HomeInteractor], the counterpart of `:dashboard-feature`'s Android one.
 *
 * The interesting half — the PID first name — is genuinely shared: it reads the same
 * `WalletEngine.getMainPidDocument()` seam, which on iOS is the Kotlin-over-multipaz engine, so the
 * name rendered on Home comes from a real stored mdoc credential.
 *
 * **Bluetooth is checked the way the official iOS wallet checks it** ([IosBluetooth]): off ⇒ Home's
 * Bluetooth sheet before the QR, as on Android. iOS can say that only once the app may use Bluetooth, since
 * watching the power state takes a manager and creating one earlier raises the system prompt; so the watch
 * starts here only when Bluetooth is already allowed, and the prompt itself comes when the user picks "in
 * person" (`EnsureProximityPermissions`). Anything not yet known goes on to the QR, which says so if
 * advertising fails.
 */
internal class IosHomeInteractor(
    private val walletEngine: WalletEngine,
    private val stringResolver: StringResolver,
) : HomeInteractor {

    init {
        IosBluetooth.watchIfAllowed()
    }

    /** False only when Bluetooth is allowed and known to be off; see the class KDoc. */
    override fun isBleAvailable(): Boolean = isBluetoothUsable(IosBluetooth.authorization(), IosBluetooth.isOff())

    /**
     * True since the RQES bridge landed.
     *
     * The wallet picks a PDF and hands it to the Swift `EudiRQESUi`, which owns the flow from there.
     * One limit worth stating: the iOS library has no remote-URI entry point and no
     * `documentRetrievalConfig`, so signing a document *fetched from a QR or deep link* remains
     * Android-only — what this answers for is signing a document the user chooses from Files.
     *
     * If `iOSApp.swift` ever stops registering a signer, the flow reports itself unavailable on the
     * sign screen rather than crashing; see `IosDocumentSigning`.
     */
    override fun canSignDocuments(): Boolean = true

    /**
     * False, and correctly so rather than pending.
     *
     * This wallet advertises in **peripheral-server mode** on iOS — see
     * `IosProximityPresenter.bleConnectionMethod`, where `supportsCentralClientMode = false`, because
     * being the peripheral is the side an iOS app can be. Android reads the corresponding wallet-core
     * setting; here the answer is a property of what the presenter actually does.
     */
    override fun isBleCentralClientModeEnabled(): Boolean = false

    override fun getUserNameViaMainPidDocument(): Flow<HomeInteractorGetUserNameViaMainPidDocumentPartialState> =
        // Explicit type parameter: the builder emits only Success, so inference would narrow the flow
        // and the catch below could not emit a Failure into it.
        flow<HomeInteractorGetUserNameViaMainPidDocumentPartialState> {
            val mainPid = walletEngine.getMainPidDocument()
            emit(
                HomeInteractorGetUserNameViaMainPidDocumentPartialState.Success(
                    userFirstName = mainPid?.claims?.get(GIVEN_NAME).orEmpty()
                )
            )
        }
            // `safeAsync` would be the idiom here, but its handler is not suspending and resolving a
            // string resource is. This is exactly what safeAsync expands to, with a suspending catch.
            .flowOn(ioDispatcher)
            .catch { throwable ->
                emit(
                    HomeInteractorGetUserNameViaMainPidDocumentPartialState.Failure(
                        error = throwable.message
                            ?: stringResolver.resolve(Res.string.generic_error_message)
                    )
                )
            }

    private companion object {
        /**
         * The mdoc/SD-JWT claim holding the holder's first name. Duplicated from
         * `:common-feature`'s `DocumentJsonKeys.FIRST_NAME`, which this module cannot import — that is
         * an Android feature module, and the dependency runs the other way.
         */
        const val GIVEN_NAME = "given_name"
    }
}
