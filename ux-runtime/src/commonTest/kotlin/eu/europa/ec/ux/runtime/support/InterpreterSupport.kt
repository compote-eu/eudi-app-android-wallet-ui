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

// Helpers over the exported bundle and its added-state test copy (M5b; re-pointed at the export in M6a; the copy is
// built at test time from ux-spec/ since M8r).
package eu.europa.ec.ux.runtime.support

import eu.europa.ec.ux.runtime.fixture.ExportedPresBundle
import eu.europa.ec.ux.runtime.screen.CopyProvider
import eu.europa.ec.ux.runtime.screen.ScreenNode
import eu.europa.ec.ux.runtime.screen.ScreenTree
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * M5b test (ii), M8r: the exported bundle (ux-spec/, read at test time) plus one added state, built here, so any
 * re-export keeps it current (its spec hash is the export's). Every added name starts with `_test`, which no export can
 * carry (kmp-bundle.schema.json: state and node ids `^[a-z0-9]…`, events `^[A-Z]…`, results `^[A-Z]…`, screens
 * `^SCR…`, copy ids `^CPY-…`, resource names `^cpy_…`), so the copy never collides with a state the spec itself adds.
 * The added state sits behind a new result of the service the [host] state invokes; its screen is a copy of the host's
 * screen with every plain copy prop moved to a test-only key (texts in `copy._test_strings`); every event its nodes
 * emit, and its back event, lead back to the host. Data only: the runtime gets no Kotlin change.
 */
internal object AddedStateFixture {
    const val STATE: String = "_test-added-state"
    const val SCREEN: String = "_test-added-screen"
    const val EVENT: String = "_TEST_ADDED"
    const val RESULT: String = "_TestAdded"
    private const val COPY: String = "_test-copy-"
    private const val RES: String = "_test_copy_"

    /** The first state (bundle order) that invokes a service and draws a screen: the request fetch of the export. */
    val host: String by lazy {
        Fixture.root.obj("states").entries.first { (_, s) ->
            (s.jsonObject["invoke"] as? JsonObject)?.get("service") != null && s.jsonObject["screen"] is JsonPrimitive
        }.key
    }

    /** The service the host invokes; its new result [RESULT] leads to the added state. */
    val service: String by lazy {
        Fixture.root.obj("states").getValue(host).jsonObject.obj("invoke").getValue("service").jsonPrimitive.content
    }

    val root: JsonObject by lazy { build(Fixture.root) }

    private fun build(export: JsonObject): JsonObject {
        val names = listOf(STATE to "states", SCREEN to "screens", EVENT to "events")
        names.forEach { (name, section) -> check(name !in export.obj(section)) { "the export already has $name" } }
        val hostState = export.obj("states").getValue(host).jsonObject
        check(RESULT !in export.obj("services").obj(service).obj("results")) { "the export already has $RESULT" }
        val hostScreen = hostState.getValue("screen").jsonPrimitive.content
        val texts = mutableMapOf<String, String>()
        val screen = retext(export.obj("screens").getValue(hostScreen), texts).jsonObject
        val emitted = (screen.obj("regions").values.flatMap { (it as JsonArray).flatMap(::emits) } +
            listOfNotNull((hostState["back"] as? JsonObject)?.get("event")?.jsonPrimitive?.content)).distinct()
        check(emitted.isNotEmpty()) { "the host screen $hostScreen has no event to leave the added state" }
        val state = buildJsonObject {
            hostState["kind"]?.let { put("kind", it) }
            put("description", "M8r test copy: a state added to the export at test time")
            put("screen", SCREEN)
            hostState["ui_state"]?.let { put("ui_state", it) }
            put("on", JsonObject(emitted.associateWith { buildJsonArray { add(branch(host)) } }))
            hostState["back"]?.let { put("back", it) }
            hostState["restore"]?.let { put("restore", it) }
        }
        var root = export
            .with("/events/$EVENT", buildJsonObject { put("source", "service"); put("description", "M8r test copy") })
            .with("/services/$service/results/$RESULT", JsonObject(emptyMap()))
            .with("/states/$host/invoke/on/$RESULT", buildJsonObject { put("event", EVENT) })
            .with("/states/$host/on/$EVENT", buildJsonArray { add(branch(STATE)) })
            .with("/states/$STATE", state)
            .with("/screens/$SCREEN", screen)
        texts.keys.forEach { res -> root = root.with("/copy/keys/${COPY}${res.removePrefix(RES)}", buildJsonObject { put("res", res) }) }
        return root.with("/copy/_test_strings", JsonObject(texts.mapValues { JsonPrimitive(it.value) }))
    }

    private fun branch(target: String) = buildJsonObject { put("target", target); put("kind", "main") }

    private fun emits(node: JsonElement): List<String> {
        val o = node.jsonObject
        val own = (o["events"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject["emits"]?.jsonPrimitive?.content }
        return own + (o["slots"] as? JsonObject).orEmpty().values.flatMap { (it as JsonArray).flatMap(::emits) }
    }

    /** Every `{copy}` prop without args gets a new test-only key whose text says which one it is. */
    private fun retext(e: JsonElement, texts: MutableMap<String, String>): JsonElement = when (e) {
        is JsonObject -> if (e.keys == setOf("copy")) {
            val n = texts.size + 1
            texts["$RES$n"] = "Pridaný stav, text $n"
            buildJsonObject { put("copy", "$COPY$n") }
        } else {
            JsonObject(e.mapValues { retext(it.value, texts) })
        }
        is JsonArray -> JsonArray(e.map { retext(it, texts) })
        else -> e
    }

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
}

/**
 * The exported copy for tests: `strings.xml` as embedded by the fixture task (resource name to text), plus a test copy's
 * own `copy._test_strings` (the added-state copy adds keys the export does not have).
 */
internal object TestCopy {
    fun table(root: JsonObject): Map<String, String> {
        val own = ((root["copy"] as JsonObject)["_test_strings"] as? JsonObject).orEmpty()
        return ExportedPresBundle.STRINGS + own.mapValues { (it.value as JsonPrimitive).content }
    }

    fun provider(root: JsonObject): CopyProvider = CopyProvider.of(table(root))

    /** The text of a copy key with no placeholders, through its resource name. */
    fun sk(root: JsonObject, key: String): String? {
        val res = (((root["copy"] as JsonObject)["keys"] as JsonObject)[key] as? JsonObject)?.get("res") as? JsonPrimitive
        return res?.let { table(root)[it.content] }
    }
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
