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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Resolved from `LocalContext` rather than provided by the host, which keeps the seam self-contained —
 * introducing it needed no Android host wiring at all.
 *
 * The three implementations are written out here rather than delegating to `:ui-logic`'s `Context`
 * extensions, because :ui-logic depends on :shared-ui and importing back would be a cycle. They are
 * the same intents; the only difference is that [finishApp] walks up to the host `Activity` instead of
 * casting to `EudiComponentActivity`, which is strictly more general and does not name a :ui-logic
 * type.
 */
@Composable
actual fun rememberPlatformScreenActions(): PlatformScreenActions {
    val context: Context = LocalContext.current
    return remember(context) {
        object : PlatformScreenActions {

            override fun finishApp() {
                context.findActivityOrNull()?.finish()
            }

            override fun openAppSettings() {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }

            override fun openBluetoothSettings() {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
}

private fun Context.findActivityOrNull(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
