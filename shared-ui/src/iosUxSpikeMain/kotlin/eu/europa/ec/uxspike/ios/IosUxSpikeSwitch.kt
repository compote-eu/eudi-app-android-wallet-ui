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

import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults

/**
 * Reads the debug switch. `--ux-spike <script|off>` on the launch arguments is parsed here, in Kotlin, and persisted
 * to the app-group defaults, so the value survives a cold start through a deep link and is visible to the extension.
 * Without the argument the persisted value decides; with neither, the switch is off.
 */
internal object IosUxSpikeSwitch {
    private const val ARGUMENT = "--ux-spike"
    private const val KEY = "ux-spike"
    private const val GROUP_INFO_KEY = "EUDIAppGroupIdentifier"

    private val defaults: NSUserDefaults by lazy {
        val group = (NSBundle.mainBundle.objectForInfoDictionaryKey(GROUP_INFO_KEY) as? String)
            ?.takeIf { it.isNotBlank() && !it.startsWith("group.\${") }
        group?.let { NSUserDefaults(suiteName = it) } ?: NSUserDefaults.standardUserDefaults
    }

    /** The script name when the switch is on, or null when it is off. */
    fun script(): String? {
        val args = NSProcessInfo.processInfo.arguments.filterIsInstance<String>()
        val index = args.indexOf(ARGUMENT)
        if (index >= 0 && index + 1 < args.size) {
            val value = args[index + 1]
            println("UX-SPIKE: argument $ARGUMENT=$value (persisting)")
            if (value == "off") defaults.removeObjectForKey(KEY) else defaults.setObject(value, forKey = KEY)
        }
        return defaults.stringForKey(KEY)?.takeIf { it.isNotBlank() && it != "off" }
    }
}
