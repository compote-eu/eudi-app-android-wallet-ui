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

package eu.europa.ec.uxspike.android

import android.content.Context
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DebugFlowHook
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.uilogic.component.PlatformBackHandler
import eu.europa.ec.uilogic.component.PlatformScreenActions
import eu.europa.ec.uilogic.component.rememberPlatformScreenActions
import eu.europa.ec.ux.contract.pres.PresCrossDeviceExit
import eu.europa.ec.ux.contract.pres.PresCrossDeviceSpec
import eu.europa.ec.ux.runtime.engine.FlowEngine
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.uxspike.UxFlowEntry
import eu.europa.ec.uxspike.UxFlowRoute
import eu.europa.ec.uxspike.UxSpikeLog
import java.io.File

/**
 * The Android binding of [DebugFlowHook] for the spike flow. The switch is the file `files/ux-spike.on`, written with
 * `adb shell run-as $PKG sh -c 'echo happy > files/ux-spike.on'`; with no file, the switch is off.
 */
internal class AndroidUxSpikeHook(private val context: Context) : DebugFlowHook {

    override fun EntryProviderScope<NavKey>.entries(navigator: AppNavigator) {
        entry<UxFlowRoute> { route ->
            val actions = rememberPlatformScreenActions()
            UxFlowEntry(
                route = route,
                script = script() ?: DEFAULT_SCRIPT,
                platform = FlowPlatform.ANDROID,
                // The one back-handler adapter of this hook: the runtime decides what a press does (bundle policy).
                backHandler = { enabled, onBack -> PlatformBackHandler(enabled, onBack) },
                onExit = { exit -> exitTo(exit, navigator, actions) },
            )
        }
    }

    /** The deferred exits (SPIKE-PLAN §1.5): home first, then the exit's own target where the app has one. */
    private fun exitTo(exit: PresCrossDeviceExit?, navigator: AppNavigator, actions: PlatformScreenActions) {
        UxSpikeLog.log("exit $exit")
        if (!navigator.popUpTo(DashboardRoute::class)) navigator.pop()
        when (exit) {
            PresCrossDeviceExit.AddDocument ->
                navigator.navigate(AddDocumentRoute(IssuanceUiConfig(IssuanceFlowType.ExtraDocument(formatType = null))))
            // PlatformScreenActions has no security-settings action (and iOS no such link): app settings is the closest.
            PresCrossDeviceExit.DeviceSettings -> actions.openAppSettings()
            is PresCrossDeviceExit.ToHelp ->
                UxSpikeLog.log("GAP to-help: the app has no help route (FLW-app-info#help-from-error); home, error_class=${exit.errorClass}")
            else -> Unit
        }
    }

    override fun redirect(route: NavKey): NavKey? {
        if (script() == null) return null
        return when {
            route is PresentationRequestRoute && route.config.mode is PresentationMode.OpenId4Vp ->
                UxFlowRoute(PresCrossDeviceSpec.FLOW_ID, entry = "deeplink", launch = (route.config.mode as PresentationMode.OpenId4Vp).uri)

            route is QrScanRoute && route.config.qrScanFlow is QrScanFlow.Presentation ->
                UxFlowRoute(PresCrossDeviceSpec.FLOW_ID, entry = FlowEngine.DEFAULT_ENTRY)

            else -> null
        }
    }

    private fun script(): String? =
        File(context.filesDir, SWITCH_FILE).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val DEFAULT_SCRIPT = "happy"
        const val SWITCH_FILE = "ux-spike.on"
    }
}
