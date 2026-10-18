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

/**
 * Reports [ProximityPermissionsOutcome.Granted] without asking anything.
 *
 * There is nothing to ask. iOS has no runtime Bluetooth grant an app can request up front: the system
 * raises its own prompt the first time CoreBluetooth is used, needing only the
 * `NSBluetoothAlwaysUsageDescription` string in the Info.plist. So the honest answer here is "go ahead" —
 * and if the user then refuses at the system prompt, advertising fails and the QR screen says so, which
 * is the same place a refusal surfaces on Android.
 */
@Composable
actual fun EnsureProximityPermissions(
    isBleCentralClientModeEnabled: Boolean,
    onOutcome: (ProximityPermissionsOutcome) -> Unit,
) {
    onOutcome(ProximityPermissionsOutcome.Granted)
}
