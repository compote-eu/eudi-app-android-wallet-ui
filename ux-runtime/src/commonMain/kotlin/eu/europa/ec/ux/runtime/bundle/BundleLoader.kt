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

package eu.europa.ec.ux.runtime.bundle

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Thrown inside the loader only; [BundleLoader.load] turns it into [LoadResult.Refused]. */
internal class RefusedException(val reason: RefusalReason) : Exception(reason.toString())

/** Handshake order: JSON, schema version, hashes and flow id, then the full parse and the structural checks. */
internal object BundleLoader {

    fun load(
        bytes: ByteArray,
        expectedFlowId: String,
        expectedSpecHash: String,
        themeSpecHash: String,
        allowProvisional: Boolean,
    ): LoadResult = try {
        val root = parseRoot(bytes)
        handshake(root, expectedFlowId, expectedSpecHash, themeSpecHash, allowProvisional)
        val bundle = BundleParser(root).parse()
        BundleValidator(bundle).validate()
        LoadResult.Loaded(bundle)
    } catch (e: RefusedException) {
        LoadResult.Refused(e.reason)
    }

    private fun parseRoot(bytes: ByteArray): JsonObject {
        val element = try {
            Json.parseToJsonElement(bytes.decodeToString())
        } catch (e: SerializationException) {
            throw RefusedException(RefusalReason.Malformed("", e.message ?: "invalid JSON"))
        }
        return element as? JsonObject ?: throw RefusedException(RefusalReason.Malformed("", "the bundle is not an object"))
    }

    private fun handshake(
        root: JsonObject,
        expectedFlowId: String,
        expectedSpecHash: String,
        themeSpecHash: String,
        allowProvisional: Boolean,
    ) {
        val version = root["bundle_schema_version"] as? JsonPrimitive
        if (version == null || version.isString || version.content != FlowBundle.SUPPORTED_SCHEMA_VERSION.toString()) {
            throw RefusedException(RefusalReason.UnsupportedSchemaVersion(root["bundle_schema_version"].toString()))
        }
        val hashElement = root["spec_hash"] as? JsonPrimitive
        val hash = hashElement?.takeIf { it.isString }?.content
        if (hash.isNullOrEmpty()) throw RefusedException(RefusalReason.MissingSpecHash)
        if (hash.startsWith(FlowBundle.PROVISIONAL_PREFIX) && !allowProvisional) {
            throw RefusedException(RefusalReason.ProvisionalNotAllowed)
        }
        val flowId = (root["flow_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (flowId != expectedFlowId) {
            throw RefusedException(RefusalReason.FlowIdMismatch(expectedFlowId, flowId.toString()))
        }
        if (themeSpecHash != expectedSpecHash) {
            throw RefusedException(RefusalReason.ThemeHashMismatch(expectedSpecHash, themeSpecHash))
        }
        if (hash != expectedSpecHash) {
            throw RefusedException(RefusalReason.SpecHashMismatch(expectedSpecHash, hash))
        }
    }
}

/** A JSON value with its pointer, so every refusal names where it happened. */
internal class Node(val path: String, val element: JsonElement?) {

    fun child(key: String): Node = Node("$path/${escape(key)}", obj()[key])

    fun opt(key: String): Node? = obj()[key]?.takeIf { it !is JsonNull }?.let { Node("$path/${escape(key)}", it) }

    fun obj(): JsonObject = element as? JsonObject ?: malformed("expected an object")

    fun arr(): List<Node> = (element as? JsonArray ?: malformed("expected an array"))
        .mapIndexed { i, e -> Node("$path/$i", e) }

    fun str(): String = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed("expected a string")

    fun bool(): Boolean = (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: malformed("expected a boolean")

    fun entries(): List<Pair<String, Node>> = obj().keys.map { it to child(it) }

    /** A semantic enum: an unknown value refuses the load (boundary §1.1). */
    fun <E> enumOf(values: Array<E>, wire: (E) -> String): E {
        val s = str()
        return values.firstOrNull { wire(it) == s } ?: throw RefusedException(RefusalReason.UnsupportedFeature(path, s))
    }

    fun malformed(detail: String): Nothing = throw RefusedException(RefusalReason.Malformed(path, detail))

    private fun escape(key: String) = key.replace("~", "~0").replace("/", "~1")
}

internal fun Node.optStr(key: String): String? = opt(key)?.str()

internal fun Node.strList(key: String): List<String> = opt(key)?.arr()?.map { it.str() }.orEmpty()

internal fun Node.strMap(key: String): Map<String, String> =
    opt(key)?.entries()?.associate { (k, v) -> k to v.str() }.orEmpty()
