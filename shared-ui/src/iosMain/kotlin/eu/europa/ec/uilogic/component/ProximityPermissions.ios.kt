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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Asks for Bluetooth the way the official iOS wallet does, when the user picks "in person": the system
 * prompt if it was never answered, then [ProximityPermissionsOutcome.NeedsRationale] when Bluetooth is
 * refused, which shows Home's Bluetooth sheet and sends the user to this app's settings.
 *
 * iOS has no request an app makes itself: the prompt comes with the first Bluetooth manager, and needs
 * only `NSBluetoothAlwaysUsageDescription` in the Info.plist. No `Pending` while it is up: Home composes
 * this only while it waits for an answer, and a `Pending` would take it out of composition before one came.
 */
@Composable
actual fun EnsureProximityPermissions(
    isBleCentralClientModeEnabled: Boolean,
    onOutcome: (ProximityPermissionsOutcome) -> Unit,
) {
    LaunchedEffect(Unit) {
        onOutcome(proximityOutcomeFor(IosBluetooth.ask()))
    }
}
