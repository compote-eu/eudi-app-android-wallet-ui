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

// M1b, re-run at M6a on the real export (was provisional on the boundary fixture): P4 on iOS.
package eu.europa.ec.ux.runtime.host

import androidx.lifecycle.SavedStateHandle
import androidx.savedstate.read

/** The iOS saved state is a plain Kotlin map, so the round trip is the provider's output read back into a handle. */
class FlowViewModelSavedStateIosTest : FlowViewModelSavedStateScenario() {
    override fun processDeath(handle: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(handle.savedStateProvider().saveState().read { toMap() })
}
