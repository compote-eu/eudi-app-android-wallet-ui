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

// Upstream bf514519's `TrustMarkResource.Text.getLocalizedText`, on the bare map so both platforms use it.
// Its own file name: a `TrustMarkExtensions.kt` here would share one JVM facade class with any file of that
// name in this package in another module.
package eu.europa.ec.corelogic.extension

/**
 * The Trust Mark text for the user's language, as upstream picks it: blank translations ignored, then the
 * first whose key names the same language as [userLanguageTag] (keys may use `_` and any case), else the
 * first translation left, else null — never the resource's own name.
 *
 * "Same language" is Android's `Locale.language` comparison, so a regional key matches any region and the
 * first matching entry wins, in the order the resource lists them.
 */
fun Map<String, String>.localizedTrustMarkText(userLanguageTag: String): String? {
    val translations = entries.filter { translation -> translation.value.isNotBlank() }
    val userLanguage = userLanguageTag.primaryLanguage()
    return translations.firstOrNull { translation ->
        userLanguage != null && translation.key.replace('_', '-').primaryLanguage() == userLanguage
    }?.value
        ?: translations.firstOrNull()?.value
}

/**
 * The primary language subtag, lower-cased — what `java.util.Locale.forLanguageTag(tag).language` gives for
 * a well-formed tag — or null when the tag does not start with one (BCP 47: two to eight letters).
 */
private fun String.primaryLanguage(): String? =
    substringBefore('-').lowercase().takeIf { subtag ->
        subtag.length in 2..8 && subtag.all { it in 'a'..'z' }
    }
