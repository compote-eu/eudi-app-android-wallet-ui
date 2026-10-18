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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The language per-language texts are picked in: the user's first preferred language, reduced to the bare
 * code every caller matches on — never the app bundle's own localisation, which is English only.
 */
class IosUserLanguageTest {

    @Test
    fun the_users_first_preferred_language_is_used_as_a_bare_code() {
        assertEquals("fr", iosUserLanguage(listOf("fr-FR", "en-US")))
        assertEquals("sk", iosUserLanguage(listOf("sk")))
        assertEquals("hu", iosUserLanguage(listOf("hu-HU", "sk-SK")))
    }

    @Test
    fun a_script_and_region_are_dropped_with_the_rest_of_the_tag() {
        assertEquals("zh", iosUserLanguage(listOf("zh-Hans-CN")))
        assertEquals("pt", iosUserLanguage(listOf("pt_BR")))
    }

    @Test
    fun a_tag_that_starts_with_no_language_is_skipped_for_the_next_one() {
        assertEquals("de", iosUserLanguage(listOf("123", "de-AT")))
    }

    @Test
    fun with_no_preference_at_all_a_language_is_still_answered() {
        // The old source, so nothing is worse than before in the case that cannot really happen.
        assertTrue(iosUserLanguage(emptyList()).isNotBlank())
    }
}
