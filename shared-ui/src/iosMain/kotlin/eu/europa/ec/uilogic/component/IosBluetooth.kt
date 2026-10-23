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

package eu.europa.ec.uilogic.component

import kotlinx.coroutines.CompletableDeferred
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerOptionShowPowerAlertKey
import platform.darwin.NSObject

/** Whether this app may use Bluetooth, as iOS answers it. */
internal enum class IosBluetoothAuthorization { ALLOWED, REFUSED, NOT_ASKED }

/**
 * What iOS tells the app about Bluetooth before a proximity presentation, so Home can show upstream's
 * Bluetooth sheet first, as the official iOS wallet does: its `ReachabilityController` starts a Bluetooth
 * manager when the user picks "in person", shows its modal when Bluetooth is off or refused, and only then
 * opens the QR.
 *
 * Reading the authorization asks nothing of the user. Watching the power state takes a manager, and creating
 * one before the user has answered is what raises the system prompt, so that happens only when the user
 * asks to share in person ([ask]) or once Bluetooth is already allowed ([watchIfAllowed]).
 */
internal object IosBluetooth {

    /** Kept for as long as the app runs: CoreBluetooth holds its delegate weakly, and this answers it. */
    private var manager: CBPeripheralManager? = null

    /** Kept while iOS shows its "turn on Bluetooth" alert; see [offerToTurnOn]. */
    private var alerting: CBCentralManager? = null

    private var answered: CompletableDeferred<Unit>? = null

    private val delegate = object : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            if (peripheral.state != CBManagerStateUnknown && peripheral.state != CBManagerStateResetting) {
                answered?.complete(Unit)
            }
        }
    }

    fun authorization(): IosBluetoothAuthorization = when (CBManager.authorization) {
        CBManagerAuthorizationAllowedAlways -> IosBluetoothAuthorization.ALLOWED
        CBManagerAuthorizationDenied, CBManagerAuthorizationRestricted -> IosBluetoothAuthorization.REFUSED
        else -> IosBluetoothAuthorization.NOT_ASKED
    }

    /** Starts watching the power state when that asks nothing — as Home appears, so it is known by a tap. */
    fun watchIfAllowed() {
        if (authorization() == IosBluetoothAuthorization.ALLOWED) ensureManager()
    }

    /** True when Bluetooth is switched off, false when on, null while not known. */
    fun isOff(): Boolean? = when (manager?.state) {
        CBManagerStatePoweredOff -> true
        CBManagerStatePoweredOn -> false
        else -> null
    }

    /** Raises the system's Bluetooth prompt if it was never answered, and returns the answer once given. */
    suspend fun ask(): IosBluetoothAuthorization {
        if (authorization() != IosBluetoothAuthorization.NOT_ASKED) return authorization()
        val wait = CompletableDeferred<Unit>().also { answered = it }
        ensureManager()
        wait.await()
        answered = null
        return authorization()
    }

    /**
     * iOS's own alert for Bluetooth being off, whose Settings button opens the Bluetooth pane. There is no
     * public URL for that pane; the official wallet opens `App-Prefs:root=General`, which is not public API.
     */
    fun offerToTurnOn() {
        alerting = CBCentralManager(
            delegate = null,
            queue = null,
            options = mapOf<Any?, Any>(CBCentralManagerOptionShowPowerAlertKey to true),
        )
    }

    private fun ensureManager() {
        if (manager != null) return
        manager = CBPeripheralManager(
            delegate = delegate,
            queue = null,
            // Home shows its own sheet for this; the system's alert on top of it would say the same twice.
            options = mapOf<Any?, Any>(CBPeripheralManagerOptionShowPowerAlertKey to false),
        )
    }
}

/**
 * Whether Home may go on to the QR: not when Bluetooth is allowed and known to be off. Anything not yet known
 * goes on, and the QR screen says so if advertising then fails.
 */
internal fun isBluetoothUsable(authorization: IosBluetoothAuthorization, isOff: Boolean?): Boolean =
    !(authorization == IosBluetoothAuthorization.ALLOWED && isOff == true)

/** The permission seam's answer: refused means Home's sheet, which sends the user to this app's settings. */
internal fun proximityOutcomeFor(authorization: IosBluetoothAuthorization): ProximityPermissionsOutcome =
    if (authorization == IosBluetoothAuthorization.REFUSED) {
        ProximityPermissionsOutcome.NeedsRationale
    } else {
        ProximityPermissionsOutcome.Granted
    }
