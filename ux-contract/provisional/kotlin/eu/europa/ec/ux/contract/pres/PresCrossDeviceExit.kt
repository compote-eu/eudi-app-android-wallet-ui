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

// PROVISIONAL (M1b): replaced by ux-spec/ at M6; do not extend. Hand copy of spike-contract-boundary.md §2.
package eu.europa.ec.ux.contract.pres

/** Structured exits of the final states (bundle states.<final>.exit). */
sealed interface PresCrossDeviceExit {
    /** home, presented: "Wallet home; the verifier website continues the user's session" */
    data object Done : PresCrossDeviceExit

    /** home, not-presented */
    data object Closed : PresCrossDeviceExit

    /** flow FLW-dashboard#add-document */
    data object AddDocument : PresCrossDeviceExit

    /** platform security-settings */
    data object DeviceSettings : PresCrossDeviceExit

    /** flow FLW-app-info#help-from-error, carries /flow/error_class */
    data class ToHelp(val errorClass: String?) : PresCrossDeviceExit

    companion object {
        fun of(finalState: String, errorClass: String?): PresCrossDeviceExit? = when (finalState) {
            "done" -> Done
            "closed" -> Closed
            "add-document" -> AddDocument
            "device-settings" -> DeviceSettings
            "to-help" -> ToHelp(errorClass)
            else -> null
        }
    }
}
