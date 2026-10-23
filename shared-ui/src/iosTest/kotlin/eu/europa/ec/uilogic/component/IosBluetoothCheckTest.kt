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

// What Home decides from what iOS says about Bluetooth before the QR, as the official iOS wallet decides it.
// CoreBluetooth itself cannot run here (the simulator has no radio), so these are the decisions alone.
package eu.europa.ec.uilogic.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosBluetoothCheckTest {

    @Test
    fun bluetooth_known_to_be_off_stops_home_before_the_qr() {
        assertFalse(isBluetoothUsable(IosBluetoothAuthorization.ALLOWED, isOff = true))
    }

    @Test
    fun bluetooth_known_to_be_on_goes_on_to_the_qr() {
        assertTrue(isBluetoothUsable(IosBluetoothAuthorization.ALLOWED, isOff = false))
    }

    /** Not known yet: the QR screen says so if advertising then fails. */
    @Test
    fun a_power_state_not_yet_known_goes_on_to_the_qr() {
        assertTrue(isBluetoothUsable(IosBluetoothAuthorization.ALLOWED, isOff = null))
        assertTrue(isBluetoothUsable(IosBluetoothAuthorization.NOT_ASKED, isOff = null))
    }

    /** Refused is the permission step's to say, with the sheet that leads to this app's settings. */
    @Test
    fun a_refusal_is_left_to_the_permission_step() {
        assertTrue(isBluetoothUsable(IosBluetoothAuthorization.REFUSED, isOff = null))
        assertEquals(
            ProximityPermissionsOutcome.NeedsRationale,
            proximityOutcomeFor(IosBluetoothAuthorization.REFUSED),
        )
    }

    @Test
    fun bluetooth_allowed_goes_on_to_the_qr() {
        assertEquals(ProximityPermissionsOutcome.Granted, proximityOutcomeFor(IosBluetoothAuthorization.ALLOWED))
    }
}
