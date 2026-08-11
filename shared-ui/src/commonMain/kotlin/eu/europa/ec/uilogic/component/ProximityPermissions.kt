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

/** What asking for the proximity-presentation permissions concluded. */
enum class ProximityPermissionsOutcome {

    /** Everything needed is granted; the proximity flow can start. */
    Granted,

    /** The user has refused before, so an explanation should be shown before asking again. */
    NeedsRationale,

    /** A request is in flight, or the state is not yet knowable. */
    Pending,
}

/**
 * Ensures the permissions ISO 18013-5 proximity presentation needs, reporting the conclusion through
 * [onOutcome]. Composed only while the screen actually wants to start a proximity flow.
 *
 * Per-platform because the permission *model* differs, not merely the API. Android must request
 * BLUETOOTH_ADVERTISE/SCAN/CONNECT at runtime (and, below Android 13 in central-client mode, location
 * as well) and can ask whether to show a rationale. iOS has no equivalent up-front request — the system
 * asks for Bluetooth on first CoreBluetooth use — so there is nothing for this to do there.
 *
 * @param isBleCentralClientModeEnabled affects which permissions Android needs; ignored elsewhere.
 */
@Composable
expect fun EnsureProximityPermissions(
    isBleCentralClientModeEnabled: Boolean,
    onOutcome: (ProximityPermissionsOutcome) -> Unit,
)
