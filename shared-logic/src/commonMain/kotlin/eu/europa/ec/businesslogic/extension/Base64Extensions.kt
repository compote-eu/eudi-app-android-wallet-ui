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

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Decodes a Base64 string into **every** interpretation that decodes cleanly, most likely first.
 *
 * Deliberately resilient rather than strict, because the input is issuer-supplied — a document's
 * portrait or an issuer logo — and such strings arrive in inconsistent shapes. It therefore:
 * 1. strips a Data-URI prefix (everything up to and including `base64,`),
 * 2. removes all whitespace, and
 * 3. tries the standard (`+/`) and URL-safe (`-_`) alphabets, each accepting present-or-absent padding.
 *
 * Returning a **list** is the point, and callers depend on it: a URL-safe string can also decode
 * against the standard alphabet and yield plausible-but-wrong bytes, so the caller keeps trying
 * candidates until one is usable — e.g. `firstNotNullOfOrNull { it.toImageBitmapOrNull() }`. An empty
 * list means nothing decoded.
 *
 * Moved here from the Android `:business-logic` so shared components can decode images. The Android
 * version took a list of `android.util.Base64` flags defaulting to
 * `DEFAULT, NO_WRAP, URL_SAFE, URL_SAFE or NO_WRAP`; that parameter is gone rather than translated,
 * because **for decoding those four collapse to just two distinct behaviours** — `NO_WRAP` only
 * concerns line breaks when *encoding*, so `DEFAULT` and `NO_WRAP` decoded identically, as did the two
 * URL-safe variants. The two alphabets below are the whole of what it actually tried, and no caller
 * ever passed the parameter.
 */
@OptIn(ExperimentalEncodingApi::class)
fun String.decodeBase64ToByteArrays(): List<ByteArray> {
    val sanitized = substringAfter(delimiter = "base64,", missingDelimiterValue = this)
        .filterNot { character -> character.isWhitespace() }

    if (sanitized.isBlank()) return emptyList()

    // `PRESENT_OPTIONAL` accepts a payload whether or not it carries `=` padding, which is what the
    // Android version approximated by decoding both the raw string and a manually re-padded copy.
    return listOf(
        Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL),
        Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL),
    ).mapNotNull { codec ->
        runCatching { codec.decode(sanitized) }.getOrNull()
    }
}
