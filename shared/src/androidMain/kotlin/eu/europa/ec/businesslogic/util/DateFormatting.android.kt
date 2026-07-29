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

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

// Reproduces the previous Instant.formatInstant behaviour exactly: same pattern,
// Locale.forLanguageTag (== LocaleUtils.getLocaleFromSelectedLanguage), system default zone.
internal actual fun formatInstantPlatform(
    instant: Instant,
    pattern: String,
    languageCode: String,
): String = DateTimeFormatter
    .ofPattern(pattern, Locale.forLanguageTag(languageCode))
    .withZone(ZoneId.systemDefault())
    .format(instant.toJavaInstant())
