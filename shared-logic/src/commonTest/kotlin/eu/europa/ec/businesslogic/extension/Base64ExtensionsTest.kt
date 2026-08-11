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

package eu.europa.ec.businesslogic.extension

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The point of this function is tolerating whatever an issuer sends, so the shapes it must tolerate are
 * pinned here. Runs on both Android (JVM) and iOS (Kotlin/Native) from the same source.
 */
class Base64ExtensionsTest {

    // "Hello, EUDI!" — chosen so the encoding contains no character that differs between the standard
    // and URL-safe alphabets, i.e. both decoders accept it.
    private val plain = "Hello, EUDI!"
    private val plainBase64 = "SGVsbG8sIEVVREkh"

    private fun List<ByteArray>.decodedStrings() = map { it.decodeToString() }

    @Test
    fun a_padded_standard_string_decodes() {
        val results = plainBase64.decodeBase64ToByteArrays()

        assertTrue(results.isNotEmpty())
        assertEquals(plain, results.first().decodeToString())
    }

    @Test
    fun an_unpadded_string_decodes_too() {
        // 4 bytes -> "AQIDBA==" padded; the unpadded form must work as well, which is what
        // PRESENT_OPTIONAL buys.
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertContentEquals(bytes, "AQIDBA".decodeBase64ToByteArrays().first())
        assertContentEquals(bytes, "AQIDBA==".decodeBase64ToByteArrays().first())
    }

    @Test
    fun a_data_uri_prefix_is_stripped() {
        val results = "data:image/png;base64,$plainBase64".decodeBase64ToByteArrays()

        assertTrue(results.isNotEmpty())
        assertEquals(plain, results.first().decodeToString())
    }

    @Test
    fun whitespace_and_line_breaks_are_ignored() {
        val wrapped = "SGVsbG8s\n IEVV\tREkh\r\n"

        assertEquals(plain, wrapped.decodeBase64ToByteArrays().first().decodeToString())
    }

    @Test
    fun a_url_safe_string_decodes_even_though_the_standard_alphabet_rejects_it() {
        // 0xFB 0xFF 0xBF encodes as "+/+/" in the standard alphabet and "-_-_" URL-safe. Only the
        // URL-safe decoder accepts the latter, so this proves both alphabets are tried.
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())

        val results = "-_-_".decodeBase64ToByteArrays()

        assertEquals(1, results.size)
        assertContentEquals(bytes, results.single())
    }

    @Test
    fun a_standard_alphabet_string_decodes_even_though_the_url_safe_alphabet_rejects_it() {
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())

        val results = "+/+/".decodeBase64ToByteArrays()

        assertEquals(1, results.size)
        assertContentEquals(bytes, results.single())
    }

    @Test
    fun an_alphabet_agnostic_string_yields_one_candidate_per_alphabet() {
        // This is why the function returns a list rather than a single result: the same input can
        // decode under both alphabets, and only the caller can tell which bytes are usable.
        val results = plainBase64.decodeBase64ToByteArrays()

        assertEquals(2, results.size)
        assertTrue(results.decodedStrings().all { it == plain })
    }

    @Test
    fun a_blank_or_prefix_only_string_yields_nothing() {
        assertTrue("".decodeBase64ToByteArrays().isEmpty())
        assertTrue("   \n ".decodeBase64ToByteArrays().isEmpty())
        assertTrue("data:image/png;base64,".decodeBase64ToByteArrays().isEmpty())
    }

    @Test
    fun undecodable_input_yields_an_empty_list_rather_than_throwing() {
        // '!' is in no Base64 alphabet, and a lone trailing character cannot form a group.
        assertTrue("!!!!".decodeBase64ToByteArrays().isEmpty())
        assertTrue("SGVsbG8sIEVVREkhA".decodeBase64ToByteArrays().isEmpty())
    }
}
