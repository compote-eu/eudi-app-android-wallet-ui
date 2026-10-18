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

import org.junit.Test
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * `ParsedUri` exists so shared code reaches the verdicts `java.net.URI` reaches, which upstream's
 * tests pin. This holds it to the real class over a seeded corpus — structured URIs built from awkward
 * parts, and raw strings — so a divergence shows up as a named input rather than as a difference
 * between the Android and iOS screens.
 */
class TestParsedUriAgainstJavaNetUri {

    @Test
    fun `Given a corpus of URIs, When parsed, Then every component matches java net URI`() {
        val corpus = corpus()
        val mismatches = corpus.mapNotNull { input -> compareParse(input) }

        assertTrue(mismatches.isEmpty(), report(mismatches))
        // Agreement means nothing if one outcome never occurs: both sides refusing everything agrees.
        val parsed = corpus.filter { input -> runCatching { URI(input) }.isSuccess }
        val withServerHost = parsed.count { input -> URI(input).host != null }
        val withRegistryAuthority = parsed.count { input -> URI(input).let { it.host == null && it.rawAuthority != null } }
        println("corpus=${corpus.size} parsed=${parsed.size} serverHost=$withServerHost registry=$withRegistryAuthority")
        assertTrue(parsed.size >= MIN_EACH_OUTCOME, "only ${parsed.size} inputs parse")
        assertTrue(corpus.size - parsed.size >= MIN_EACH_OUTCOME, "only ${corpus.size - parsed.size} inputs fail")
        assertTrue(withServerHost >= MIN_EACH_OUTCOME, "only $withServerHost inputs have a server host")
        assertTrue(withRegistryAuthority >= MIN_EACH_OUTCOME, "only $withRegistryAuthority have a registry authority")
    }

    @Test
    fun `Given bases and references, When resolved, Then the result matches java net URI`() {
        val random = Random(SEED)
        val references = RFC_REFERENCES + List(RANDOM_REFERENCES) { structured(random) }
        val mismatches = BASES.flatMap { base ->
            references.mapNotNull { reference ->
                val expected = runCatching { URI(base).resolve(reference).toString() }.getOrNull()
                val actual = ParsedUri.parseOrNull(base)?.resolve(reference)
                if (expected == actual) {
                    null
                } else {
                    "resolve(${base.quoted()}, ${reference.quoted()}): java=$expected ours=$actual"
                }
            }
        }

        assertTrue(mismatches.isEmpty(), report(mismatches))
    }

    @Test
    fun `Given random text, When form-encoded, Then the result matches URLEncoder`() {
        val random = Random(SEED)
        val inputs = List(RANDOM_TEXTS) { randomText(random, maxLength = 24) }.filterNot { it.hasLoneSurrogate() }
        val mismatches = inputs.mapNotNull { input ->
            val expected = URLEncoder.encode(input, Charsets.UTF_8.name())
            val actual = formUrlEncode(input)
            if (expected == actual) null else "encode(${input.quoted()}): java=$expected ours=$actual"
        }

        assertTrue(mismatches.isEmpty(), report(mismatches))
    }

    private fun compareParse(input: String): String? {
        val java = runCatching { URI(input) }.getOrNull()
        val ours = ParsedUri.parseOrNull(input)
        if (java == null || ours == null) {
            return if ((java == null) == (ours == null)) {
                null
            } else {
                "${input.quoted()}: java parses=${java != null} ours parses=${ours != null}"
            }
        }
        val fields = listOf(
            Triple("scheme", java.scheme, ours.scheme),
            Triple("rawSsp", java.rawSchemeSpecificPart, ours.rawSchemeSpecificPart),
            Triple("ssp", java.schemeSpecificPart, ours.schemeSpecificPart),
            Triple("rawAuthority", java.rawAuthority, ours.rawAuthority),
            Triple("rawUserInfo", java.rawUserInfo, ours.rawUserInfo),
            Triple("userInfo", java.userInfo, ours.userInfo),
            Triple("host", java.host, ours.host),
            Triple("port", java.port, ours.port),
            Triple("rawPath", java.rawPath, ours.rawPath),
            Triple("rawQuery", java.rawQuery, ours.rawQuery),
            Triple("rawFragment", java.rawFragment, ours.rawFragment),
            Triple("isOpaque", java.isOpaque, ours.isOpaque),
            Triple("isAbsolute", java.isAbsolute, ours.isAbsolute),
            Triple("toString", java.toString(), ours.toString()),
        ) + if (Normalizer.isNormalized(input, Normalizer.Form.NFC) && !input.hasLoneSurrogate()) {
            listOf(Triple("toASCIIString", java.toASCIIString(), ours.toASCIIString()))
        } else {
            emptyList()
        }
        val differing = fields.filter { (_, expected, actual) -> expected != actual }
        if (differing.isEmpty()) return null
        return "${input.quoted()}: " + differing.joinToString { (name, expected, actual) ->
            "$name java=$expected ours=$actual"
        }
    }

    private fun corpus(): List<String> {
        val random = Random(SEED)
        return FIXED + List(STRUCTURED_COUNT) { structured(random) } +
                List(RANDOM_COUNT) { randomText(random, maxLength = 20) }
    }

    private fun structured(random: Random): String = buildString {
        if (random.nextInt(5) > 0) append(SCHEMES.random(random)).append(':')
        when (random.nextInt(4)) {
            0 -> append(OPAQUE_PARTS.random(random))
            else -> {
                if (random.nextInt(4) > 0) {
                    append("//")
                    if (random.nextInt(3) == 0) append(USER_INFOS.random(random)).append('@')
                    append(HOSTS.random(random))
                    if (random.nextInt(3) == 0) append(PORTS.random(random))
                }
                append(PATHS.random(random))
                if (random.nextInt(3) == 0) append('?').append(piece(random))
            }
        }
        if (random.nextInt(4) == 0) append('#').append(piece(random))
    }

    private fun piece(random: Random): String =
        if (random.nextBoolean()) PIECES.random(random) else randomText(random, maxLength = 8)

    private fun randomText(random: Random, maxLength: Int): String =
        String(CharArray(random.nextInt(maxLength + 1)) { ALPHABET.random(random) })

    private fun String.hasLoneSurrogate(): Boolean = indices.any { index ->
        val char = this[index]
        (char.isHighSurrogate() && (index + 1 >= length || !this[index + 1].isLowSurrogate())) ||
                (char.isLowSurrogate() && (index == 0 || !this[index - 1].isHighSurrogate()))
    }

    private fun String.quoted(): String = "\"" + map { char ->
        if (char.code < 0x20 || char.code in 0x7F..0xA0) "\\u%04x".format(char.code) else char.toString()
    }.joinToString("") + "\""

    private fun report(mismatches: List<String>): String =
        "${mismatches.size} mismatch(es):\n" + mismatches.take(REPORTED).joinToString("\n")

    private companion object {
        const val SEED = 20261003
        const val STRUCTURED_COUNT = 20_000
        const val RANDOM_COUNT = 20_000
        const val RANDOM_REFERENCES = 500
        const val RANDOM_TEXTS = 5_000
        const val REPORTED = 25
        const val MIN_EACH_OUTCOME = 500

        // Every ASCII printable, the usual whitespace and controls, and non-ASCII from each class the
        // parser treats differently: visible, separators (NBSP, U+2028), C1 controls, a surrogate pair.
        val ALPHABET: List<Char> = (0x20..0x7E).map { it.toChar() } +
                listOf('\t', '\n', '\r', '\u0000', '\u0080', '\u0085', ' ', ' ', 'é', 'ß', '中', 'Ω') +
                listOf('%', '%', '/', '/', ':', '@', '[', ']') +
                "😀".toList()

        val SCHEMES = listOf(
            "http", "https", "HTTPS", "mailto", "tel", "file", "a+b.c-d", "1http", "ht tp", "h_t", "é",
        )
        val OPAQUE_PARTS = listOf(
            "user@example.com", "a%40b@c.org", "first.last+tag@Example.COM", "/not-opaque", "%zz@x.y",
            "üser@example.com", "a b@c.d", "+421 900 123 456", "x?y=z", "", "%2", "a#b",
        )
        val USER_INFOS = listOf("user", "user:pass", "us%20er", "a@b", "ü", "", "u;v:w&x=y+z$,")
        val HOSTS = listOf(
            "example.com", "Example.COM", "ex_ample.com", "1.2.3.4", "256.1.1.1", "01.02.03.004",
            "1.2.3", "1.2.3.4.5", "[::1]", "[fe80::1%en0]", "[fe80::1%25en0]", "[fe80::1%]", "[1:2:3:4:5:6:7:8]",
            "[1:2:3:4:5:6:7:8:9]", "[::ffff:1.2.3.4]", "[1::2::3]", "[12345::]", "[::]", "[:1]",
            "a-.com", "-a.com", "a.1com", "a.b.", "localhost", "exämple.com", "例え.jp", "host%41", "ho%zzst",
            "", "a..b", "xn--bcher-kva.example", "a.b-c.d", "123", "a.123",
        )
        val PORTS = listOf(":", ":0", ":80", ":0080", ":65535", ":65536", ":99999", ":99999999999", ":8a", ":-1")
        val PATHS = listOf(
            "", "/", "/a/b", "/a//b", "/a/./b/../c", "/%7Euser", "/a b", "/a%zz", "/ä", "/a;b=c", "/a?",
            "/[x]", "/@", "relative/path", "../up", "./here", "a:b/c",
        )
        val PIECES = listOf("a=b&c=d", "%41%42", "[1]", "x y", "ä", "%e2%82%ac", "%E2%82", "q?r#s", "")

        val FIXED = listOf(
            "", "#", "?", "//", "///", "http:", "http://", "https://", "mailto:", "mailto:a@b#c",
            "mailto:/a@b", "https://example.com", "https://user:pw@example.com:8080/p?q#f",
            "https://example.com/bad url.svg", "https://ex.com/%zz", "https://ex.com:99999/",
            "https://ex.com:0/", "https://ex.com:/", "file:///etc/hosts", "javascript:alert(1)",
            "intent://x#Intent;end", "HTTPS://EXAMPLE.COM/A#B", "../assets/logo.svg", "tel:+421900123456",
            "https://example.com\n", "https://[fe80::1%en0]:8080/x",
        )

        val BASES = listOf(
            "http://a/b/c/d;p?q", "http://a/b/c/d;p?q#f", "http://a/b/c/", "http://a", "http://a/",
            "https://issuer.example/trustmark/resource.json", "https://u@[::1]:8080/x/y", "mailto:x@y",
            "a/b/c", "/a/b", "", "file:///x/y", "http://a/b/../c/./d",
        )
        val RFC_REFERENCES = listOf(
            "g:h", "g", "./g", "g/", "/g", "//g", "?y", "g?y", "#s", "g#s", "g?y#s", ";x", "g;x", "g;x?y#s",
            "", ".", "./", "..", "../", "../g", "../..", "../../", "../../g", "../../../g", "../../../../g",
            "/./g", "/../g", "g.", ".g", "g..", "..g", "./../g", "./g/.", "g/./h", "g/../h", "g;x=1/./y",
            "g;x=1/../y", "g?y/./x", "g?y/../x", "g#s/./x", "g#s/../x", "http:g", "a:b", "x/../a:b",
            "../assets/logo.svg", "logo.svg", "#f", "//other/x", "bad ref", "%zz",
        )
    }
}
