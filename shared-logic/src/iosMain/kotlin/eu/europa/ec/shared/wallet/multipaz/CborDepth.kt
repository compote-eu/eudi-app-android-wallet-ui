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

// A bound on how deep CBOR from outside the wallet may nest, checked before multipaz decodes it.
//
// multipaz's `Cbor.decode` recurses once per array, map or tag, with no limit (0.101.0 and 0.102.0). On
// Kotlin/Native a stack overflow is not an exception: the process dies, and no `catch` around the decode
// helps. 10,000 nested arrays, 10 KB, are enough. The official iOS wallet's data-model library decodes with
// a bound of 64 (eudi-lib-ios-iso18013-data-model PR #154, for PAR_ISO_DATA_MODEL_7), which this follows.
package eu.europa.ec.shared.wallet.multipaz

/** How deep CBOR from outside the wallet may nest: the official iOS wallet's bound. */
internal const val CBOR_MAXIMUM_DEPTH = 64

/**
 * These bytes, once checked to be one well-formed CBOR data item nested no deeper than [maximumDepth].
 *
 * Every array, map and tag is a level. A byte string whose content is itself one CBOR data item is looked
 * into as well, one level below it, since multipaz decodes some of those on its own: a tag-24
 * `EncodedCbor`, a COSE protected header or payload. One that is not CBOR, a signature or a picture, is
 * left as bytes. Levels add up across those embeddings, which keeps the work bounded; well-formed ISO
 * 18013-5 messages and credentials nest about a dozen levels deep in all.
 *
 * Also refused, before multipaz could misread them: a declared length longer than the bytes left, a
 * truncated item, and bytes after the item.
 *
 * Iterative within an item, and recursive only into embedded byte strings, which [maximumDepth] bounds,
 * so the check cannot exhaust the stack itself.
 *
 * @throws IllegalArgumentException when the bytes are nested too deep or are not one well-formed item.
 */
internal fun ByteArray.requireCborWithinDepth(maximumDepth: Int = CBOR_MAXIMUM_DEPTH): ByteArray {
    val end = CborDepthScan(this, maximumDepth).item(start = 0, limit = size, depth = 0)
    require(end != MALFORMED) { "Not well-formed CBOR" }
    require(end == size) { "Bytes after the CBOR data item" }
    return this
}

private const val MALFORMED = -1
private const val INDEFINITE = -1L
private const val BREAK = 0xff

private class CborDepthScan(private val bytes: ByteArray, private val maximumDepth: Int) {

    /**
     * Where the one data item at [start] ends, or [MALFORMED] when there is none before [limit]. [depth] is
     * the level the item's own containers start below.
     *
     * @throws IllegalArgumentException as soon as anything nests deeper than [maximumDepth], also inside a
     *   byte string that is only being tried as CBOR.
     */
    fun item(start: Int, limit: Int, depth: Int): Int {
        // The items each open container still holds, innermost last; INDEFINITE until its break.
        val open = ArrayList<Long>()
        var pos = start
        while (true) {
            if (pos >= limit) return MALFORMED
            val initial = bytes[pos].toInt() and 0xff
            val major = initial ushr 5
            val info = initial and 0x1f
            pos++

            if (initial == BREAK) {
                if (open.lastOrNull() != INDEFINITE) return MALFORMED
                open.removeAt(open.lastIndex)
            } else {
                val argument: Long = when {
                    info < 24 -> info.toLong()
                    info <= 27 -> {
                        val length = 1 shl (info - 24)
                        if (limit - pos < length) return MALFORMED
                        var value = 0UL
                        repeat(length) { value = (value shl 8) or (bytes[pos + it].toULong() and 0xffUL) }
                        pos += length
                        // Only a length or a count is read further, and none this long fits in the bytes there are.
                        if (value > Int.MAX_VALUE.toULong()) {
                            if (major in 2..5) return MALFORMED else 0L
                        } else {
                            value.toLong()
                        }
                    }

                    info == 31 && major in 2..5 -> INDEFINITE
                    else -> return MALFORMED
                }
                when (major) {
                    // An integer, or a simple value or a float: complete.
                    0, 1, 7 -> Unit

                    // A byte or text string: its content, unless it is sent in chunks.
                    2, 3 -> if (argument == INDEFINITE) {
                        enter(depth, open, INDEFINITE)
                        continue
                    } else {
                        if (argument > limit - pos) return MALFORMED
                        val contentEnd = pos + argument.toInt()
                        if (major == 2) tryEmbedded(pos, contentEnd, depth + open.size + 1)
                        pos = contentEnd
                    }

                    // An array or a map: every item takes at least a byte, so a count beyond the bytes left
                    // cannot be right.
                    4, 5 -> {
                        val items = when {
                            argument == INDEFINITE -> INDEFINITE
                            major == 4 -> argument
                            else -> argument * 2
                        }
                        if (items != INDEFINITE && items > limit - pos) return MALFORMED
                        if (items != 0L) {
                            enter(depth, open, items)
                            continue
                        }
                    }

                    // A tag: one item follows, a level down.
                    6 -> {
                        enter(depth, open, 1L)
                        continue
                    }
                }
            }

            // An item is complete: count it in the container it belongs to, closing those it fills.
            while (true) {
                if (open.isEmpty()) return pos
                val left = open[open.lastIndex]
                if (left == INDEFINITE) break
                if (left > 1L) {
                    open[open.lastIndex] = left - 1
                    break
                }
                open.removeAt(open.lastIndex)
            }
        }
    }

    private fun enter(depth: Int, open: ArrayList<Long>, items: Long) {
        require(depth + open.size + 1 <= maximumDepth) { "CBOR nested deeper than $maximumDepth levels" }
        open.add(items)
    }

    /**
     * Looks into a byte string that may hold CBOR multipaz will decode; leaves it be when it does not. Byte
     * strings inside byte strings count as levels too, which is what bounds this recursion.
     */
    private fun tryEmbedded(start: Int, end: Int, depth: Int) {
        if (start == end) return
        require(depth <= maximumDepth) { "CBOR nested deeper than $maximumDepth levels" }
        item(start = start, limit = end, depth = depth)
    }
}
