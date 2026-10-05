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

package eu.europa.ec.shared.wallet.multipaz

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which of a certificate's texts the user reads: the exact language tag, then the same language whatever
 * its region, then the first entry. ETSI TS 119 475's own example writes `en-US` and `de-DE`, and the
 * wallet asks in the user's bare language.
 */
class LocalizedTextForLocaleTest {

    private val texts = listOf(
        LocalizedText("en-US", "Membership"),
        LocalizedText("sk-SK", "Členstvo"),
        LocalizedText("sk", "Členstvo (bez oblasti)"),
    )

    @Test
    fun a_region_tagged_text_is_found_for_the_users_bare_language() {
        assertEquals("Členstvo", listOf(texts[0], texts[1]).forLocale("sk"))
    }

    @Test
    fun a_bare_text_is_found_for_a_region_tagged_user_language() {
        assertEquals("Členstvo (bez oblasti)", listOf(texts[0], texts[2]).forLocale("sk-SK"))
    }

    @Test
    fun the_exact_tag_wins_over_another_region_of_the_same_language() {
        assertEquals("Členstvo (bez oblasti)", texts.forLocale("sk"))
        assertEquals("Členstvo", texts.forLocale("sk-SK"))
    }

    @Test
    fun a_language_the_texts_do_not_have_falls_back_to_the_first() {
        assertEquals("Membership", texts.forLocale("hu"))
    }
}
