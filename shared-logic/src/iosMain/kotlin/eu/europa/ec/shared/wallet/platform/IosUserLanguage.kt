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

package eu.europa.ec.shared.wallet.platform

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.preferredLanguages

/**
 * The language the user reads, as a bare code (`fr`, `sk`): what every per-language text this wallet picks
 * is chosen by — issuer and verifier display names, a verifier's purpose, the Trust Mark.
 *
 * ⛔ Not `NSLocale.currentLocale.languageCode`. That is the language this *app* is localised into, and the
 * bundle declares English only, so it answers `en` for a French or Slovak user: with the simulator in
 * French, About showed the English Trust Mark text (watched 2026-10-05). `NSLocale.preferredLanguages` is
 * the user's own order — what Compose's string resources follow and the official iOS wallet reads, and
 * what Android's `Locale.getDefault()` is there.
 *
 * @param preferredLanguages the user's languages, most preferred first, as BCP 47 tags; injectable for tests.
 */
fun iosUserLanguage(
    preferredLanguages: List<String> = NSLocale.preferredLanguages.filterIsInstance<String>(),
): String =
    preferredLanguages.firstNotNullOfOrNull { it.primaryLanguageSubtag() }
        ?: NSLocale.currentLocale.languageCode

/** `fr` from `fr-FR`, `zh` from `zh-Hans-CN`; null when the tag does not start with a language. */
private fun String.primaryLanguageSubtag(): String? =
    substringBefore('-').substringBefore('_').lowercase().takeIf { subtag ->
        subtag.length in 2..8 && subtag.all { it in 'a'..'z' }
    }
