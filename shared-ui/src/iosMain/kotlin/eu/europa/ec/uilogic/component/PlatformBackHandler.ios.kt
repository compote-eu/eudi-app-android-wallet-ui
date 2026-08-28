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
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * iOS's back gesture, over `androidx.navigationevent` rather than Compose's deprecated `BackHandler`.
 *
 * [NavigationBackHandler] is the back-only convenience wrapper — it delegates to
 * `NavigationEventHandler` with forward disabled — so the swap costs only the hoisted state that API
 * requires. `NavigationEventInfo.None` because nothing here animates against the gesture's progress;
 * a screen that wanted a predictive-back animation would hoist real info instead.
 *
 * 🪤 **It needs a `LocalNavigationEventDispatcherOwner` in composition and throws if there is none.**
 * That holds because the only caller is `ContentScreen`, which every screen renders inside, and every
 * screen is a `NavDisplay` entry — `NavDisplay` provides the owner. A future caller *outside* the
 * navigation host would crash on first composition rather than fail quietly, so keep it under the host.
 *
 * No `@OptIn` any more: `androidx.compose.ui.backhandler.BackHandler` was experimental as well as
 * deprecated, and this API is neither.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = state,
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
}
