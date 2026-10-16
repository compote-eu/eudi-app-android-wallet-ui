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

// PROVISIONAL (M5b): helpers over the boundary fixture and its offline-loading test copy; re-pointed in M6.
package eu.europa.ec.ux.runtime.support

import eu.europa.ec.ux.runtime.fixture.OfflineLoadingTestBundle
import eu.europa.ec.ux.runtime.screen.CopyProvider
import eu.europa.ec.ux.runtime.screen.ScreenNode
import eu.europa.ec.ux.runtime.screen.ScreenTree
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The TEST copy of the bundle with the added state `offline-loading` (spike-evidence/M5b). */
internal object OfflineFixture {
    val root: JsonObject by lazy { Json.parseToJsonElement(OfflineLoadingTestBundle.JSON).jsonObject }
}

/** PROVISIONAL copy for tests: the `_sk` annotations of a bundle's copy table, `{name}` → `%n$s` (boundary §1.8). */
internal object TestCopy {
    fun table(root: JsonObject): Map<String, String> {
        val copy = root["copy"] as JsonObject
        val keys = (copy["keys"] as JsonObject).values +
            (copy["brand_overrides"] as? JsonObject).orEmpty().values.flatMap { (it as JsonObject).values }
        return keys.map { it.jsonObject }.mapNotNull { k ->
            val res = (k["res"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val sk = (k["_sk"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val names = (k["placeholders"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
            res to names.foldIndexed(sk) { i, t, n -> t.replace("{$n}", "%${i + 1}\$s") }
        }.toMap()
    }

    fun provider(root: JsonObject): CopyProvider = CopyProvider.of(table(root))

    /** The `_sk` text of a copy key with no placeholders. */
    fun sk(root: JsonObject, key: String): String? =
        (((root["copy"] as JsonObject)["keys"] as JsonObject)[key] as? JsonObject)?.get("_sk")?.let { (it as JsonPrimitive).content }
}

/**
 * What a screen drawn from the bundle must show: per visible, non-inert node, the text of each `{copy}` prop that has
 * no placeholders. Read from the bundle, so the test names no screen.
 */
internal fun expectedTexts(root: JsonObject, tree: ScreenTree, hidden: Set<String> = emptySet()): List<Pair<String, String>> {
    fun walk(n: ScreenNode): List<ScreenNode> =
        if (n.id in hidden || n.a11y.inert) emptyList() else listOf(n) + n.slots.values.flatten().flatMap(::walk)
    return tree.regions.values.flatten().flatMap(::walk).flatMap { n ->
        n.props.values.mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val key = (o["copy"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            if (o["args"] != null) return@mapNotNull null
            TestCopy.sk(root, key)?.let { n.id to it }
        }
    }
}

/** Every visible node id of the tree, slots included. */
internal fun visibleNodeIds(tree: ScreenTree, hidden: Set<String> = emptySet()): List<String> =
    tree.nodes.map { it.id }.filter { it !in hidden }

/**
 * EL-link nodes whose `href` binds a context path with a null default that the flow never sets in these tests
 * (`/links/help`, boundary §1.3): EL-link draws nothing without an href (M5), so they are absent by design.
 */
internal fun unlinked(root: JsonObject, tree: ScreenTree): Set<String> = tree.nodes.filter { n ->
    val path = ((n.props["href"] as? JsonObject)?.get("bind") as? JsonPrimitive)?.content
    n.element == "EL-link" && path != null && ((root["context"] as JsonObject)[path] as? JsonObject)?.get("default") is JsonNull
}.map { it.id }.toSet()
