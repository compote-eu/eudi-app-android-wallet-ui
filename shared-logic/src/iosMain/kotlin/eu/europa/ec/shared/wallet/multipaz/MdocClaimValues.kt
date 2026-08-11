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

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.CborDouble
import org.multipaz.cbor.CborFloat
import org.multipaz.cbor.CborInt
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.IndefLengthBstr
import org.multipaz.cbor.IndefLengthTstr
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.util.toBase64Url

/**
 * Renders an mdoc data element as the plain string `WalletDocument.claims` carries.
 *
 * This reproduces what Android produces for the same element, which is
 * `MsoMdocClaim.value?.toString().orEmpty()` where `value` came from the document manager's
 * upokecenter-based `ByteArray.toObject()`. Reproducing it here rather than reusing multipaz's own
 * `MdocClaim.render()` is deliberate: `render()` without a `DocumentTypeRepository` falls back to
 * CBOR *diagnostic notation*, which would quote strings (`"Tester"` instead of `Tester`) and would
 * not match the Android side.
 *
 * The mapping, element kind by kind:
 *  - null/undefined -> `""` (Android's `?.toString().orEmpty()` on a null value)
 *  - booleans -> `true`/`false`
 *  - integers -> the decimal form; floats/doubles -> `Double.toString()`
 *  - text -> the text itself
 *  - a **tagged** item -> the tagged content, since upokecenter reports the *untagged* type; this is
 *    what makes a tag-1004 `full-date` come out as `1990-01-01` rather than as a wrapper
 *  - byte strings -> base64url, except tag 24 (embedded CBOR), which is decoded and rendered
 *  - arrays/maps -> Kotlin's collection form (`[a, b]` / `{k=v}`), as `List`/`Map.toString()` gives
 *
 * One knowingly-accepted difference: base64url here is **unpadded** (multipaz's `toBase64Url`
 * trims `=`) where Android's `Base64.getUrlEncoder()` pads. It only affects byte-string elements
 * such as `portrait`, which no consumer parses out of this flat map.
 */
internal fun DataItem.toClaimString(): String = when (this) {
    is Simple -> when (this) {
        Simple.TRUE -> "true"
        Simple.FALSE -> "false"
        else -> "" // NULL, UNDEFINED and reserved simple values all read as absent.
    }

    is CborInt -> asNumber.toString()
    is CborDouble -> value.toString()
    is CborFloat -> value.toString()
    is Tstr -> value
    is IndefLengthTstr -> chunks.joinToString(separator = "")

    is Tagged -> when {
        // `asTaggedEncodedCbor` requires the tagged item to be a bstr of valid CBOR and throws
        // otherwise, so fall through to the plain tagged content when it is anything else.
        tagNumber == Tagged.ENCODED_CBOR && taggedItem is Bstr ->
            runCatching { asTaggedEncodedCbor.toClaimString() }
                .getOrElse { taggedItem.toClaimString() }

        else -> taggedItem.toClaimString()
    }

    is Bstr -> value.toBase64Url()
    is IndefLengthBstr -> chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }.toBase64Url()

    is CborArray -> items.joinToString(prefix = "[", postfix = "]") { it.toClaimString() }
    is CborMap -> items.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "${key.toClaimString()}=${value.toClaimString()}"
    }

    else -> ""
}
