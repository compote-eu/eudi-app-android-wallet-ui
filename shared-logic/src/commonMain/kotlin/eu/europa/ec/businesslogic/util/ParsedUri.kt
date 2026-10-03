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

/**
 * A URI read the way `java.net.URI` reads one, for code that is shared with iOS.
 *
 * Upstream validates contact links, document links and Trust Mark URLs with `java.net.URI`, and its
 * tests pin that class's exact verdicts — a space is refused, `%zz` is refused, port 99999 parses but
 * port 99999999999 does not, a host with an underscore is no host at all. A platform URL type would
 * answer differently (iOS 17's `NSURL` percent-encodes what Java refuses), so the rules are written
 * out here once: RFC 2396 plus the deviations Java documents (IPv6 literals, empty authorities, empty
 * paths). `TestParsedUriAgainstJavaNetUri` checks this against the real class on the JVM.
 *
 * Only what callers need is modelled. One known gap: [toASCIIString] does not NFC-normalise first, as
 * Java does, so a decomposed non-ASCII character is escaped as written.
 */
class ParsedUri internal constructor(
    val scheme: String?,
    val rawSchemeSpecificPart: String,
    /** Null when there is none, including an empty one such as in `file:///x`. */
    val rawAuthority: String?,
    /** Set only for a server-based authority, like [host] and [port]. */
    val rawUserInfo: String?,
    /** Null for a registry-based authority — one that does not parse as `[user@]host[:port]`. */
    val host: String?,
    /** -1 when absent. Not range-checked: `:99999` parses, as in Java. */
    val port: Int,
    /** Null exactly when the URI is opaque, such as `mailto:a@b`. */
    val rawPath: String?,
    val rawQuery: String?,
    val rawFragment: String?,
    private val string: String,
) {

    val isAbsolute: Boolean get() = scheme != null

    val isOpaque: Boolean get() = rawPath == null

    /** Percent escapes decoded as UTF-8, except inside an IPv6 literal's brackets. */
    val schemeSpecificPart: String get() = decode(rawSchemeSpecificPart)

    val userInfo: String? get() = rawUserInfo?.let(::decode)

    /** Non-ASCII characters percent-encoded as UTF-8; everything else exactly as parsed. */
    fun toASCIIString(): String = encodeNonAscii(string)

    /** The text this was parsed from, unchanged. */
    override fun toString(): String = string

    override fun equals(other: Any?): Boolean = other is ParsedUri && other.string == string

    override fun hashCode(): Int = string.hashCode()

    /**
     * Resolves [reference] against this URI by RFC 2396 §5.2, as `URI.resolve(String)` does, and
     * returns the result's text; null when [reference] is not a URI.
     */
    fun resolve(reference: String): String? {
        val child = parseOrNull(reference) ?: return null
        return resolve(child)
    }

    private fun resolve(child: ParsedUri): String {
        if (child.isOpaque || isOpaque) return child.string

        // 5.2 (2): a lone fragment refers to this document.
        if (child.scheme == null && child.rawAuthority == null && child.rawPath.isNullOrEmpty() &&
            child.rawFragment != null && child.rawQuery == null
        ) {
            if (rawFragment != null && child.rawFragment == rawFragment) return string
            return compose(
                scheme, rawAuthority, rawUserInfo, host, port, rawPath, rawQuery, child.rawFragment,
            )
        }

        // 5.2 (3): the child is absolute.
        if (child.scheme != null) return child.string

        // 5.2 (4)-(6).
        if (child.rawAuthority == null) {
            val childPath = child.rawPath.orEmpty()
            val path = if (childPath.startsWith('/')) {
                childPath
            } else {
                resolvePath(rawPath.orEmpty(), childPath, isAbsolute)
            }
            return compose(
                scheme, rawAuthority, rawUserInfo, host, port, path, child.rawQuery, child.rawFragment,
            )
        }
        return compose(
            scheme,
            child.rawAuthority,
            child.rawUserInfo,
            child.host,
            child.port,
            child.rawPath,
            child.rawQuery,
            child.rawFragment,
        )
    }

    companion object {

        /** Null when `java.net.URI(input)` would throw. */
        fun parseOrNull(input: String): ParsedUri? = try {
            Parser(input).parse()
        } catch (_: MalformedUri) {
            null
        }
    }
}

/**
 * Encodes [value] as `URLEncoder.encode(value, UTF_8)` does: letters, digits and `.-*_` stay, a space
 * becomes `+`, and every other character becomes its UTF-8 bytes as upper-case `%XX`.
 */
fun formUrlEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xFF
        val char = unsigned.toChar()
        when {
            unsigned < 0x80 && (char.isAsciiAlphanumeric() || char in ".-*_") -> append(char)
            char == ' ' -> append('+')
            else -> appendEscape(unsigned)
        }
    }
}

private class MalformedUri : Exception()

private fun malformed(): Nothing = throw MalformedUri()

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun StringBuilder.appendEscape(byte: Int) {
    append('%')
    append(HEX_DIGITS[byte shr 4])
    append(HEX_DIGITS[byte and 0x0F])
}

private fun Char.isAsciiAlpha(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun Char.isAsciiAlphanumeric(): Boolean = isAsciiAlpha() || isAsciiDigit()

private fun Char.isHexDigit(): Boolean = isAsciiDigit() || this in 'a'..'f' || this in 'A'..'F'

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    else -> char - 'A' + 10
}

/** Java's `Character.isSpaceChar`: the three Unicode separator categories. */
private fun Char.isSeparator(): Boolean = when (category) {
    CharCategory.SPACE_SEPARATOR,
    CharCategory.LINE_SEPARATOR,
    CharCategory.PARAGRAPH_SEPARATOR -> true

    else -> false
}

/**
 * The ASCII characters a component allows, beyond letters and digits where [alphanumeric] says so.
 * [escapes] admits `%XX` and, with it, visible non-ASCII characters — RFC 2396's *other* category.
 */
private class CharClass(
    private val extra: String,
    private val alphanumeric: Boolean = true,
    val escapes: Boolean = true,
) {
    fun matches(char: Char): Boolean = char.code in 1..127 &&
            ((alphanumeric && char.isAsciiAlphanumeric()) || char in extra)
}

private const val MARK = "-_.!~*'()"
private const val RESERVED = ";/?:@&=+$,[]"

private val URIC = CharClass(MARK + RESERVED)
private val PATH = CharClass("$MARK:@&=+$,;/")
private val USER_INFO = CharClass("$MARK;:&=+$,")
private val REG_NAME = CharClass("$MARK$,;:@&=+")
private val SERVER = CharClass("$MARK;:&=+$,.:@[]")
private val SERVER_PERCENT = CharClass("$MARK;:&=+$,.:@[]%")
private val SCHEME = CharClass("+-.", escapes = false)
private val SCOPE_ID = CharClass("_.", escapes = false)
private val DIGITS = CharClass("0123456789", alphanumeric = false, escapes = false)
private val DIGITS_AND_DOTS = CharClass("0123456789.", alphanumeric = false, escapes = false)
private val HEX = CharClass("0123456789abcdefABCDEF", alphanumeric = false, escapes = false)
private val ALPHANUMERIC = CharClass("", escapes = false)
private val ALPHANUMERIC_OR_DASH = CharClass("-", escapes = false)

private const val MAX_IPV6_BYTES = 16
private const val MAX_HEX_GROUP_LENGTH = 4
private const val MAX_BYTE_DIGITS = 3
private const val MAX_BYTE = 255

private class Parser(private val input: String) {

    private var scheme: String? = null
    private var authority: String? = null
    private var userInfo: String? = null
    private var host: String? = null
    private var port = -1
    private var path: String? = null
    private var query: String? = null
    private var fragment: String? = null
    private var ipv6ByteCount = 0

    fun parse(): ParsedUri {
        val n = input.length
        val colon = scanUntil(0, n, stopAt = ":", failOn = "/?#")
        var p: Int
        val sspStart: Int
        if (colon >= 0 && at(colon, n, ':')) {
            if (colon == 0 || !input[0].isAsciiAlpha()) malformed()
            checkChars(1, colon, SCHEME)
            scheme = input.substring(0, colon)
            p = colon + 1
            sspStart = p
            if (at(p, n, '/')) {
                p = parseHierarchical(p, n)
            } else {
                val q = scanTo(p, n, "#")
                if (q <= p) malformed()
                checkChars(p, q, URIC)
                p = q
            }
        } else {
            sspStart = 0
            p = parseHierarchical(0, n)
        }
        val ssp = input.substring(sspStart, p)
        if (at(p, n, '#')) {
            checkChars(p + 1, n, URIC)
            fragment = input.substring(p + 1, n)
            p = n
        }
        if (p < n) malformed()
        return ParsedUri(
            scheme = scheme,
            rawSchemeSpecificPart = ssp,
            rawAuthority = authority,
            rawUserInfo = userInfo,
            host = host,
            port = port,
            rawPath = path,
            rawQuery = query,
            rawFragment = fragment,
            string = input,
        )
    }

    // -- Scanning --------------------------------------------------------------------------------

    private fun at(p: Int, n: Int, char: Char): Boolean = p < n && input[p] == char

    private fun at(p: Int, n: Int, text: String): Boolean =
        p + text.length <= n && input.startsWith(text, startIndex = p)

    /** The index of the first character in [stops], or [end]. */
    private fun scanTo(start: Int, end: Int, stops: String): Int {
        var p = start
        while (p < end && input[p] !in stops) p++
        return p
    }

    /** As [scanTo], but -1 when a character in [failOn] comes first. */
    private fun scanUntil(start: Int, end: Int, stopAt: String, failOn: String): Int {
        var p = start
        while (p < end) {
            val char = input[p]
            if (char in failOn) return -1
            if (char in stopAt) break
            p++
        }
        return p
    }

    /** One step past [char] when it is at [p], else [p]. */
    private fun skip(p: Int, end: Int, char: Char): Int = if (at(p, end, char)) p + 1 else p

    /** The end of the run of characters [allowed] admits; a malformed escape is an error. */
    private fun scan(start: Int, end: Int, allowed: CharClass): Int {
        var p = start
        while (p < end) {
            val char = input[p]
            if (allowed.matches(char)) {
                p++
                continue
            }
            if (allowed.escapes) {
                val q = scanEscape(p, end, char)
                if (q > p) {
                    p = q
                    continue
                }
            }
            break
        }
        return p
    }

    private fun scanEscape(p: Int, end: Int, char: Char): Int {
        if (char == '%') {
            if (p + 3 <= end && input[p + 1].isHexDigit() && input[p + 2].isHexDigit()) return p + 3
            malformed()
        }
        // RFC 2396's "other": visible characters beyond US-ASCII. Java's bound is 128, exclusive.
        if (char.code > 128 && !char.isSeparator() && !char.isISOControl()) return p + 1
        return p
    }

    private fun checkChars(start: Int, end: Int, allowed: CharClass) {
        if (scan(start, end, allowed) < end) malformed()
    }

    // -- Components ------------------------------------------------------------------------------

    private fun parseHierarchical(start: Int, n: Int): Int {
        var p = start
        if (at(p, n, '/') && at(p + 1, n, '/')) {
            p += 2
            val q = scanTo(p, n, "/?#")
            when {
                q > p -> p = parseAuthority(p, q)
                q < n -> Unit // An empty authority before a path, query or fragment is allowed.
                else -> malformed()
            }
        }
        val q = scanTo(p, n, "?#")
        checkChars(p, q, PATH)
        path = input.substring(p, q)
        p = q
        if (at(p, n, '?')) {
            p++
            val end = scanTo(p, n, "#")
            checkChars(p, end, URIC)
            query = input.substring(p, end)
            p = end
        }
        return p
    }

    /**
     * Server-based (`[user@]host[:port]`) when it parses as one, registry-based otherwise. Java tests
     * the server alphabet with a literal `%` allowed whenever a `]` is anywhere past the first
     * character — which, since the scan runs to the end when there is none, is almost always.
     */
    private fun parseAuthority(start: Int, n: Int): Int {
        val serverChars = if (scanTo(start, n, "]") > start) {
            scan(start, n, SERVER_PERCENT) == n
        } else {
            scan(start, n, SERVER) == n
        }
        val regChars = scan(start, n, REG_NAME) == n
        if (regChars && !serverChars) {
            authority = input.substring(start, n)
            return n
        }
        val skipParseException = regChars
        var parsedAsServer = false
        var serverFailure: MalformedUri? = null
        if (serverChars) {
            try {
                val q = parseServer(start, n, skipParseException)
                if (q < n) {
                    if (!skipParseException) malformed()
                    clearServer()
                } else {
                    authority = input.substring(start, n)
                    parsedAsServer = true
                }
            } catch (failure: MalformedUri) {
                clearServer()
                serverFailure = failure
            }
        }
        if (!parsedAsServer) {
            if (regChars) {
                authority = input.substring(start, n)
            } else {
                throw serverFailure ?: MalformedUri()
            }
        }
        return n
    }

    private fun clearServer() {
        userInfo = null
        host = null
        port = -1
    }

    private fun parseServer(start: Int, n: Int, skipParseException: Boolean): Int {
        var p = start
        val atSign = scanUntil(p, n, stopAt = "@", failOn = "/?#")
        if (atSign >= p && at(atSign, n, '@')) {
            checkChars(p, atSign, USER_INFO)
            userInfo = input.substring(p, atSign)
            p = atSign + 1
        }
        if (at(p, n, '[')) {
            p++
            val close = scanUntil(p, n, stopAt = "]", failOn = "/?#")
            if (close > p && at(close, n, ']')) {
                val percent = scanTo(p, close, "%")
                if (percent > p) {
                    parseIPv6Reference(p, percent)
                    if (percent + 1 == close) malformed()
                    checkChars(percent + 1, close, SCOPE_ID)
                } else {
                    parseIPv6Reference(p, close)
                }
                host = input.substring(p - 1, close + 1)
                p = close + 1
            } else {
                malformed()
            }
        } else {
            var q = parseIPv4Address(p, n)
            if (q <= p) q = parseHostname(p, n, skipParseException)
            p = q
        }
        if (at(p, n, ':')) {
            p++
            val q = scanTo(p, n, "/")
            if (q > p) {
                checkChars(p, q, DIGITS)
                port = input.substring(p, q).toIntOrNull() ?: malformed()
                p = q
            }
        } else if (p < n && skipParseException) {
            return p
        }
        if (p < n) malformed()
        return p
    }

    private fun parseHostname(start: Int, n: Int, skipParseException: Boolean): Int {
        var p = start
        var lastLabel = -1
        do {
            var q = scan(p, n, ALPHANUMERIC)
            if (q <= p) break
            lastLabel = p
            p = q
            q = scan(p, n, ALPHANUMERIC_OR_DASH)
            if (q > p) {
                if (input[q - 1] == '-') malformed()
                p = q
            }
            q = skip(p, n, '.')
            if (q <= p) break
            p = q
        } while (p < n)
        if (p < n && !at(p, n, ':')) {
            if (skipParseException) return p
            malformed()
        }
        if (lastLabel < 0) malformed()
        // In a dotted name the rightmost label must start with a letter.
        if (lastLabel > start && !input[lastLabel].isAsciiAlpha()) malformed()
        host = input.substring(start, p)
        return p
    }

    private fun parseIPv4Address(start: Int, n: Int): Int {
        val p = try {
            scanIPv4Address(start, n, strict = false)
        } catch (_: MalformedUri) {
            return -1
        }
        if (p == -1) return -1
        if (p in (start + 1) until n && input[p] != ':') return -1
        if (p > start) host = input.substring(start, p)
        return p
    }

    /** Four dot-separated bytes; with [strict], nothing may follow them. */
    private fun scanIPv4Address(start: Int, n: Int, strict: Boolean): Int {
        val end = scan(start, n, DIGITS_AND_DOTS)
        if (end <= start || (strict && end != n)) return -1
        var p = start
        repeat(4) { index ->
            val afterByte = scanByte(p, end)
            if (afterByte <= p) return failIPv4(strict)
            p = afterByte
            if (index < 3) {
                val afterDot = skip(p, end, '.')
                if (afterDot <= p) return failIPv4(strict)
                p = afterDot
            }
        }
        return if (p < end) failIPv4(strict) else p
    }

    private fun failIPv4(strict: Boolean): Int = if (strict) malformed() else -1

    private fun scanByte(start: Int, end: Int): Int {
        val q = scan(start, end, DIGITS)
        if (q <= start) return q
        var significant = start
        while (significant < q && input[significant] == '0') significant++
        val digits = q - significant
        if (digits < MAX_BYTE_DIGITS) return q
        if (digits > MAX_BYTE_DIGITS) return start
        return if (input.substring(start, q).toInt() > MAX_BYTE) start else q
    }

    private fun takeIPv4Address(start: Int, n: Int): Int {
        val p = scanIPv4Address(start, n, strict = true)
        if (p <= start) malformed()
        return p
    }

    private fun parseIPv6Reference(start: Int, n: Int): Int {
        var p = start
        var compressedZeros = false
        val q = scanHexSequence(p, n)
        if (q > p) {
            p = q
            if (at(p, n, "::")) {
                compressedZeros = true
                p = scanHexTail(p + 2, n)
            } else if (at(p, n, ':')) {
                p = takeIPv4Address(p + 1, n)
                ipv6ByteCount += 4
            }
        } else if (at(p, n, "::")) {
            compressedZeros = true
            p = scanHexTail(p + 2, n)
        }
        if (p < n) malformed()
        if (ipv6ByteCount > MAX_IPV6_BYTES) malformed()
        if (!compressedZeros && ipv6ByteCount < MAX_IPV6_BYTES) malformed()
        if (compressedZeros && ipv6ByteCount == MAX_IPV6_BYTES) malformed()
        return p
    }

    private fun scanHexTail(start: Int, n: Int): Int {
        if (start == n) return start
        var p = start
        val q = scanHexSequence(p, n)
        if (q > p) {
            p = q
            if (at(p, n, ':')) {
                p = takeIPv4Address(p + 1, n)
                ipv6ByteCount += 4
            }
        } else {
            p = takeIPv4Address(p, n)
            ipv6ByteCount += 4
        }
        return p
    }

    /** Colon-separated groups of hex digits, stopping before `::` or an embedded IPv4 address. */
    private fun scanHexSequence(start: Int, n: Int): Int {
        var p = start
        var q = scan(p, n, HEX)
        if (q <= p) return -1
        if (at(q, n, '.')) return -1
        if (q > p + MAX_HEX_GROUP_LENGTH) malformed()
        ipv6ByteCount += 2
        p = q
        while (p < n) {
            if (!at(p, n, ':') || at(p + 1, n, ':')) break
            p++
            q = scan(p, n, HEX)
            if (q <= p) malformed()
            if (at(q, n, '.')) {
                p--
                break
            }
            if (q > p + MAX_HEX_GROUP_LENGTH) malformed()
            ipv6ByteCount += 2
            p = q
        }
        return p
    }
}

// -- Resolution ----------------------------------------------------------------------------------

private fun compose(
    scheme: String?,
    authority: String?,
    userInfo: String?,
    host: String?,
    port: Int,
    path: String?,
    query: String?,
    fragment: String?,
): String = buildString {
    if (scheme != null) append(scheme).append(':')
    if (host != null) {
        append("//")
        if (userInfo != null) append(userInfo).append('@')
        val needsBrackets = ':' in host && !host.startsWith('[') && !host.endsWith(']')
        if (needsBrackets) append('[')
        append(host)
        if (needsBrackets) append(']')
        if (port != -1) append(':').append(port)
    } else if (authority != null) {
        append("//").append(authority)
    }
    if (path != null) append(path)
    if (query != null) append('?').append(query)
    if (fragment != null) append('#').append(fragment)
}

private fun resolvePath(base: String, child: String, baseIsAbsolute: Boolean): String {
    val lastSlash = base.lastIndexOf('/')
    val merged = when {
        child.isEmpty() -> if (lastSlash >= 0) base.substring(0, lastSlash + 1) else ""
        lastSlash >= 0 || !baseIsAbsolute -> base.substring(0, lastSlash + 1) + child
        else -> "/$child"
    }
    return normalizePath(merged)
}

/** One path segment and whether a slash followed it, which normalisation keeps. */
private data class Segment(val text: String, val trailingSlash: Boolean)

/**
 * Removes `.` segments, `x/..` pairs and repeated slashes, keeps trailing slashes, and — Java's
 * deviation — prefixes `./` when a relative result would otherwise start with something that reads
 * as a scheme.
 */
private fun normalizePath(path: String): String {
    val absolute = path.startsWith('/')
    val segments = mutableListOf<Segment?>()
    var p = 0
    while (p < path.length && path[p] == '/') p++
    while (p < path.length) {
        val end = path.indexOf('/', p).let { if (it < 0) path.length else it }
        var next = end
        while (next < path.length && path[next] == '/') next++
        segments += Segment(path.substring(p, end), trailingSlash = end < path.length)
        p = next
    }

    for (i in segments.indices) {
        when (segments[i]?.text) {
            "." -> segments[i] = null
            ".." -> {
                val previous = (i - 1 downTo 0).firstOrNull { segments[it] != null }
                if (previous != null && segments[previous]?.text != "..") {
                    segments[previous] = null
                    segments[i] = null
                }
            }
        }
    }

    val firstKept = segments.indexOfFirst { it != null }
    val needsLeadingDot = !absolute && firstKept > 0 && ':' in segments[firstKept]!!.text
    val result = buildString {
        if (absolute) append('/')
        if (needsLeadingDot) append("./")
        segments.filterNotNull().forEach { segment ->
            append(segment.text)
            if (segment.trailingSlash) append('/')
        }
    }
    return result
}

// -- Escapes -------------------------------------------------------------------------------------

private fun encodeNonAscii(value: String): String {
    if (value.all { it.code < 0x80 }) return value
    return buildString {
        for (byte in value.encodeToByteArray()) {
            val unsigned = byte.toInt() and 0xFF
            if (unsigned >= 0x80) appendEscape(unsigned) else append(unsigned.toChar())
        }
    }
}

/** Decodes runs of `%XX` as UTF-8, leaving a `%` inside `[...]` alone (an IPv6 scope id). */
private fun decode(value: String): String {
    if ('%' !in value) return value
    return buildString {
        var i = 0
        var inBrackets = false
        while (i < value.length) {
            val char = value[i]
            if (char == '[') inBrackets = true else if (inBrackets && char == ']') inBrackets = false
            if (char != '%' || inBrackets || !value.isEscapeAt(i)) {
                append(char)
                i++
                continue
            }
            val bytes = mutableListOf<Byte>()
            while (i < value.length && value.isEscapeAt(i)) {
                bytes += ((hexValue(value[i + 1]) shl 4) or hexValue(value[i + 2])).toByte()
                i += 3
            }
            append(bytes.toByteArray().decodeToString())
        }
    }
}

private fun String.isEscapeAt(index: Int): Boolean =
    index + 2 < length && this[index] == '%' && this[index + 1].isHexDigit() && this[index + 2].isHexDigit()
