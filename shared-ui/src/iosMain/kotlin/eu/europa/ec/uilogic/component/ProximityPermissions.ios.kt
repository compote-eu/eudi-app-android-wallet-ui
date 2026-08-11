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
 * Reports [ProximityPermissionsOutcome.Pending] and does nothing else.
 *
 * iOS has no up-front permission request to make here: the system asks for Bluetooth the first time
 * CoreBluetooth is used, and it needs only the `NSBluetoothAlwaysUsageDescription` Info.plist string
 * rather than a runtime grant. Reporting Pending rather than Granted is the conservative choice — the
 * proximity flow is not shared to iOS yet, so a Granted here would start something that cannot finish.
 */
@Composable
actual fun EnsureProximityPermissions(
    isBleCentralClientModeEnabled: Boolean,
    onOutcome: (ProximityPermissionsOutcome) -> Unit,
) {
    onOutcome(ProximityPermissionsOutcome.Pending)
}
