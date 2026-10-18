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

// M1b, re-run at M6a on the real export (was provisional on the boundary fixture): P4 on Android.
package eu.europa.ec.ux.runtime.host

import androidx.lifecycle.SavedStateHandle
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android saved state is a `Bundle`, so this runs under Robolectric: the provider's `Bundle` is restored with
 * [SavedStateHandle.createHandle], the path the framework takes after process death.
 */
@RunWith(RobolectricTestRunner::class)
class FlowViewModelSavedStateAndroidTest : FlowViewModelSavedStateScenario() {
    override fun processDeath(handle: SavedStateHandle): SavedStateHandle =
        SavedStateHandle.createHandle(handle.savedStateProvider().saveState(), null)
}
