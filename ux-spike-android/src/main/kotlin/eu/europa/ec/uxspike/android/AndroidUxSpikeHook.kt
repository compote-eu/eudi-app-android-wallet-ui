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
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.DebugFlowHook
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.uxspike.UxFlowRoute
import eu.europa.ec.uxspike.UxFlowStub
import java.io.File

/**
 * The Android binding of [DebugFlowHook] for the spike flow. The switch is the file `files/ux-spike.on`, written with
 * `adb shell run-as $PKG sh -c 'echo happy > files/ux-spike.on'`; with no file, the switch is off.
 */
internal class AndroidUxSpikeHook(private val context: Context) : DebugFlowHook {

    override fun EntryProviderScope<NavKey>.entries(navigator: AppNavigator) {
        entry<UxFlowRoute> { route -> UxFlowStub(route) }
    }

    override fun redirect(route: NavKey): NavKey? {
        if (script() == null) return null
        return when {
            route is PresentationRequestRoute && route.config.mode is PresentationMode.OpenId4Vp ->
                UxFlowRoute(FLOW_ID, entry = "deeplink", launch = (route.config.mode as PresentationMode.OpenId4Vp).uri)

            route is QrScanRoute && route.config.qrScanFlow is QrScanFlow.Presentation ->
                UxFlowRoute(FLOW_ID, entry = "scan")

            else -> null
        }
    }

    private fun script(): String? =
        File(context.filesDir, SWITCH_FILE).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val FLOW_ID = "FLW-pres-cross-device"
        const val SWITCH_FILE = "ux-spike.on"
    }
}
