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

package eu.europa.ec.uxspike.ios

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DebugFlowHook
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.uilogic.component.PlatformBackHandler
import eu.europa.ec.ux.contract.pres.PresCrossDeviceSpec
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.uxspike.UxFlowEntry
import eu.europa.ec.uxspike.UxFlowRoute
import eu.europa.ec.uxspike.UxSpikeLog
import org.koin.core.annotation.Single

/**
 * The iOS binding of [DebugFlowHook] for the spike flow. It exists only in Debug builds (`iosUxSpikeMain` is a source
 * directory of the Debug configuration only), and the Koin component scan picks it up in the app and the extension.
 */
@Single(binds = [DebugFlowHook::class])
class IosUxSpikeHook : DebugFlowHook {

    init {
        // Parse and persist the launch argument as soon as the hook exists, so a later cold start sees it.
        IosUxSpikeSwitch.script()
    }

    override fun EntryProviderScope<NavKey>.entries(navigator: AppNavigator) {
        entry<UxFlowRoute> { route ->
            UxFlowEntry(
                route = route,
                script = IosUxSpikeSwitch.script() ?: DEFAULT_SCRIPT,
                platform = FlowPlatform.IOS,
                // The one back-handler adapter of this hook: the runtime decides what a press does (bundle policy).
                backHandler = { enabled, onBack -> PlatformBackHandler(enabled, onBack) },
                onExit = { exit ->
                    UxSpikeLog.log("exit to home (${exit?.kind} ${exit?.result})")
                    if (!navigator.popUpTo(DashboardRoute::class)) navigator.pop()
                },
            )
        }
    }

    override fun redirect(route: NavKey): NavKey? {
        val script = IosUxSpikeSwitch.script() ?: return null
        return when {
            route is PresentationRequestRoute && route.config.mode is PresentationMode.OpenId4Vp ->
                UxFlowRoute(PresCrossDeviceSpec.FLOW_ID, entry = "deeplink", launch = (route.config.mode as PresentationMode.OpenId4Vp).uri)

            route is QrScanRoute && route.config.qrScanFlow is QrScanFlow.Presentation ->
                UxFlowRoute(PresCrossDeviceSpec.FLOW_ID, entry = "scan")

            else -> null.also { println("UX-SPIKE: no redirect for $route (script=$script)") }
        }
    }

    private companion object {
        const val DEFAULT_SCRIPT = "happy"
    }
}
