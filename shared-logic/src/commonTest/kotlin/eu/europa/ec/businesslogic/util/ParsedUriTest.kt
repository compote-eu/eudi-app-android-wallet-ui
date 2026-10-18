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

// The verdicts upstream's link validators depend on, run on BOTH platforms. Every expected value here
// was read off `java.net.URI` on JDK 21; the JVM-only `TestParsedUriAgainstJavaNetUri` compares the
// two over a large corpus, and this pins the same answers where iOS can see them.
package eu.europa.ec.businesslogic.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsedUriTest {

    private fun parse(input: String): ParsedUri =
        assertNotNull(ParsedUri.parseOrNull(input), "expected \"$input\" to parse")

    @Test
    fun what_java_refuses_is_refused() {
        listOf(
            "https://example.com/bad url.svg",
            "https://ex.com/%zz",
            "https://",
            "mailto:",
            "https://[1::2::3]/",
            "https://example.com\n",
        ).forEach { input -> assertNull(ParsedUri.parseOrNull(input), "\"$input\" must not parse") }
    }

    @Test
    fun a_full_server_authority_is_split_into_its_parts() {
        val uri = parse("https://user:pw@example.com:8080/p?q#f")

        assertEquals("https", uri.scheme)
        assertEquals("user:pw@example.com:8080", uri.rawAuthority)
        assertEquals("user:pw", uri.rawUserInfo)
        assertEquals("example.com", uri.host)
        assertEquals(8080, uri.port)
        assertEquals("/p", uri.rawPath)
        assertEquals("q", uri.rawQuery)
        assertEquals("f", uri.rawFragment)
        assertEquals("//user:pw@example.com:8080/p?q", uri.rawSchemeSpecificPart)
        assertFalse(uri.isOpaque)
    }

    @Test
    fun ports_parse_without_a_range_check_until_they_overflow() {
        assertEquals(99999, parse("https://ex.com:99999/").port)
        assertEquals(0, parse("https://ex.com:0/").port)

        // Too long for an Int: no longer a server authority, so no host — but still a URI.
        val overflow = parse("https://example.com:99999999999/")
        assertNull(overflow.host)
        assertEquals(-1, overflow.port)
        assertEquals("example.com:99999999999", overflow.rawAuthority)
    }

    @Test
    fun a_name_that_is_no_hostname_leaves_a_registry_authority_and_no_host() {
        val uri = parse("https://ex_ample.com/")

        assertNull(uri.host)
        assertEquals("ex_ample.com", uri.rawAuthority)
    }

    @Test
    fun an_ipv6_literal_keeps_its_brackets_and_scope() {
        val uri = parse("https://[fe80::1%en0]:8080/x")

        assertEquals("[fe80::1%en0]", uri.host)
        assertEquals(8080, uri.port)
    }

    @Test
    fun opaque_uris_decode_their_scheme_specific_part() {
        val tagged = parse("mailto:first.last+tag@Example.COM")
        assertTrue(tagged.isOpaque)
        assertNull(tagged.rawPath)
        assertEquals("first.last+tag@Example.COM", tagged.schemeSpecificPart)

        assertEquals("a@b@c.org", parse("mailto:a%40b@c.org").schemeSpecificPart)
        assertTrue(parse("javascript:alert(1)").isOpaque)
    }

    @Test
    fun the_empty_string_and_a_relative_path_are_uris_without_a_host() {
        val empty = parse("")
        assertEquals("", empty.rawPath)
        assertNull(empty.host)

        val relative = parse("../assets/logo.svg")
        assertNull(relative.scheme)
        assertEquals("../assets/logo.svg", relative.rawPath)
    }

    @Test
    fun only_non_ascii_characters_are_escaped_for_ascii() {
        val uri = parse("https://exämple.com/ä?q=€")

        assertNull(uri.host, "a non-ASCII name is no hostname to java.net.URI")
        assertEquals("https://ex%C3%A4mple.com/%C3%A4?q=%E2%82%AC", uri.toASCIIString())
        assertEquals("https://exämple.com/ä?q=€", uri.toString())
    }

    @Test
    fun references_resolve_as_java_resolves_them() {
        val base = parse("http://a/b/c/d;p?q")

        assertEquals(
            "https://issuer.example/assets/logo.svg",
            parse("https://issuer.example/trustmark/resource.json").resolve("../assets/logo.svg"),
        )
        assertEquals("http://a/g", base.resolve("../../g"))
        // RFC 2396, not 3986: an empty reference drops the last segment and the query.
        assertEquals("http://a/b/c/", base.resolve(""))
        assertEquals("http://a/b/c/d;p?q#s", base.resolve("#s"))
        assertEquals("http://a/b/c/a:b", base.resolve("x/../a:b"))
        assertEquals("http://g", base.resolve("//g"))
        // Going above the root keeps the "..", as Java does.
        assertEquals("http://a/../g", base.resolve("../../../g"))
        assertEquals("g", parse("mailto:x@y").resolve("g"))
        assertNull(base.resolve("bad ref"))
    }

    @Test
    fun form_encoding_matches_url_encoder() {
        assertEquals("Example+Relying+Party", formUrlEncode("Example Relying Party"))
        assertEquals("a%2Bb%40c", formUrlEncode("a+b@c"))
        assertEquals("%C3%A4+%E2%82%AC", formUrlEncode("ä €"))
        assertEquals("*-._%7E%21", formUrlEncode("*-._~!"))
    }
}
