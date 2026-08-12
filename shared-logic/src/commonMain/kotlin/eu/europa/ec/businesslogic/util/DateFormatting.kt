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

// Phase 1 (date migration, stage 2): locale-aware Instant formatting lives in the shared
// module. Localized month names / date styles can't be produced by kotlinx-datetime on
// Native, so the actual formatting is delegated per platform (java.time.DateTimeFormatter
// on Android, NSDateFormatter on iOS) behind `formatInstantPlatform`. The pattern is a
// UTS#35 skeleton understood by both. The Android path reproduces the previous behaviour
// exactly (same pattern, forLanguageTag locale, system default zone).
package eu.europa.ec.businesslogic.util

import kotlin.time.Instant

/** Previous DAY_MONTH_YEAR_SHORT_PATTERN — the default when no pattern is supplied. */
const val DEFAULT_DATE_PATTERN: String = "dd MMM yyyy"

/**
 * The long form the document-details screen uses. Moved here from `:business-logic`'s `DateUtils` —
 * same package, so no consumer changed — because the shared details path needs it on both platforms.
 */
const val DAY_MONTH_YEAR_FULL_PATTERN: String = "dd MMMM yyyy"

/** Previous LocaleUtils.PROJECT_DEFAULT_LOCALE. */
const val DEFAULT_LANGUAGE_TAG: String = "en-GB"

fun Instant.formatInstant(
    pattern: String = DEFAULT_DATE_PATTERN,
    languageCode: String = DEFAULT_LANGUAGE_TAG,
): String = formatInstantPlatform(this, pattern, languageCode)

internal expect fun formatInstantPlatform(
    instant: Instant,
    pattern: String,
    languageCode: String,
): String
