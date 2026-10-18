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

package eu.europa.ec.shared.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.koin.core.Koin
import org.koin.mp.KoinPlatform

/**
 * A seam for a debug-only flow, bound in Koin by a debug build and absent from a release build.
 *
 * It is deliberately neutral: it names no flow, and nothing in a release build binds it, so every call below is a
 * no-op there. [entries] adds destinations to the nav host; [redirect] may answer a different destination for
 * [route], or null to leave the route alone. [isEnabled] says whether the debug flow is switched on right now; a bound
 * hook with the switch off must leave the app as it was (brief §6.3).
 */
interface DebugFlowHook {
    fun EntryProviderScope<NavKey>.entries(navigator: AppNavigator)

    fun redirect(route: NavKey): NavKey?

    fun isEnabled(): Boolean
}

/** The bound hook, or null when no module provides one (every release build). */
internal fun debugFlowHookOrNull(koin: Koin? = KoinPlatform.getKoinOrNull()): DebugFlowHook? =
    koin?.getOrNull<DebugFlowHook>()
