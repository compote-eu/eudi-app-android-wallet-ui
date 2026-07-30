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

package eu.europa.ec.businesslogic.util

import kotlin.time.Instant
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale

// NSDateFormatter consumes the same UTS#35 pattern as java.time and produces localized
// output (month names, AM/PM) for the given language tag. Its default time zone is the
// device's current zone, matching the Android side's ZoneId.systemDefault().
internal actual fun formatInstantPlatform(
    instant: Instant,
    pattern: String,
    languageCode: String,
): String {
    val formatter = NSDateFormatter().apply {
        dateFormat = pattern
        locale = NSLocale(localeIdentifier = languageCode)
    }
    // NSDate is anchored to 2001-01-01; 978_307_200 s is the offset from the 1970 Unix epoch.
    val secondsSince1970 = instant.toEpochMilliseconds() / 1000.0
    val date = NSDate(timeIntervalSinceReferenceDate = secondsSince1970 - 978_307_200.0)
    return formatter.stringFromDate(date)
}
