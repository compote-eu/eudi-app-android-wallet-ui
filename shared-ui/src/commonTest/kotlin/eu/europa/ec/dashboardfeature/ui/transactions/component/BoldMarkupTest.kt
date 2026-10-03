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

// The `<b>…</b>` reader that stands in for upstream's `AnnotatedString.fromHtml` in the privacy action
// screens, on both platforms.
package eu.europa.ec.dashboardfeature.ui.transactions.component

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoldMarkupTest {

    private fun AnnotatedString.boldRanges(): List<IntRange> =
        spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { it.start until it.end }

    @Test
    fun a_leading_bold_sentence_is_bold_and_its_tags_are_gone() {
        // The shape of every responsibility string in the catalogue.
        val text = "<b>The wallet doesn't send this request.</b> It is up to you.".boldMarkupToAnnotatedString()

        assertEquals("The wallet doesn't send this request. It is up to you.", text.text)
        assertEquals(listOf(0 until 37), text.boldRanges())
    }

    @Test
    fun text_without_markup_is_unchanged() {
        val text = "Any follow-up comes from the authority.".boldMarkupToAnnotatedString()

        assertEquals("Any follow-up comes from the authority.", text.text)
        assertTrue(text.spanStyles.isEmpty())
    }

    @Test
    fun several_spans_and_upper_case_tags_are_read() {
        val text = "a <B>b</B> c <b>d</b>".boldMarkupToAnnotatedString()

        assertEquals("a b c d", text.text)
        assertEquals(listOf(2 until 3, 6 until 7), text.boldRanges())
    }

    @Test
    fun an_unclosed_tag_bolds_to_the_end() {
        val text = "plain <b>bold".boldMarkupToAnnotatedString()

        assertEquals("plain bold", text.text)
        assertEquals(listOf(6 until 10), text.boldRanges())
    }

    @Test
    fun other_markup_is_left_as_text() {
        val text = "<i>not handled</i>".boldMarkupToAnnotatedString()

        assertEquals("<i>not handled</i>", text.text)
        assertTrue(text.spanStyles.isEmpty())
    }
}
