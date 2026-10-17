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

// Helpers over the exported bundle (M1b wrote them over the provisional boundary fixture; re-pointed at M6a).
package eu.europa.ec.ux.runtime.support

import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.LoadResult
import eu.europa.ec.ux.runtime.engine.FlowEngine
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowStatus
import eu.europa.ec.ux.runtime.engine.EventResult
import eu.europa.ec.ux.runtime.engine.ServicePort
import eu.europa.ec.ux.runtime.engine.ServiceResult
import eu.europa.ec.ux.runtime.fixture.ExportedPresBundle
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.assertIs
import kotlin.test.fail

internal object Fixture {
    val root: JsonObject by lazy { Json.parseToJsonElement(ExportedPresBundle.JSON).jsonObject }
    val flowId: String get() = root.getValue("flow_id").jsonPrimitive.content
    val specHash: String get() = root.getValue("spec_hash").jsonPrimitive.content

    fun bytes(root: JsonObject = this.root): ByteArray = Json.encodeToString(JsonObject.serializer(), root).encodeToByteArray()

    fun load(root: JsonObject = this.root): LoadResult =
        FlowBundle.load(bytes(root), flowId, specHash, specHash)

    fun bundle(root: JsonObject = this.root): FlowBundle = when (val r = load(root)) {
        is LoadResult.Loaded -> r.bundle
        is LoadResult.Refused -> fail("exported bundle refused: ${r.reason}")
    }
}

/** Sets (or, with null, removes) the value at a JSON pointer; array segments are indices. */
internal fun JsonElement.with(pointer: String, value: JsonElement?): JsonElement =
    edit(pointer.trim('/').split('/').map { it.replace("~1", "/").replace("~0", "~") }, value)

internal fun JsonObject.with(pointer: String, value: JsonElement?): JsonObject = (this as JsonElement).with(pointer, value).jsonObject

private fun JsonElement.edit(segs: List<String>, value: JsonElement?): JsonElement {
    val head = segs.first()
    val rest = segs.drop(1)
    return when (this) {
        is JsonArray -> {
            val i = head.toInt()
            val list = toMutableList()
            if (rest.isEmpty()) {
                if (value == null) list.removeAt(i) else list[i] = value
            } else {
                list[i] = list[i].edit(rest, value)
            }
            JsonArray(list)
        }
        is JsonObject -> {
            val map = toMutableMap()
            if (rest.isEmpty()) {
                if (value == null) map.remove(head) else map[head] = value
            } else {
                map[head] = (map[head] ?: JsonObject(emptyMap())).edit(rest, value)
            }
            JsonObject(map)
        }
        else -> fail("cannot descend into $this at $head")
    }
}

internal fun json(text: String): JsonElement = Json.parseToJsonElement(text)

internal fun obj(text: String): JsonObject = json(text).jsonObject

/**
 * A scripted service port with call counters. A service in [auto] answers at once; any other call waits until
 * [complete]. With [cancellable] false the wait ignores cancellation, which models a late completion.
 */
internal class FakePort : ServicePort {
    val auto = mutableMapOf<String, ServiceResult>()
    val calls = mutableMapOf<String, Int>()
    val cancelled = mutableMapOf<String, Int>()
    val args = mutableListOf<Pair<String, JsonObject>>()
    var cancellable = true

    /** A service in [throwing] throws this from its call (RULING-2). */
    val throwing = mutableMapOf<String, Exception>()
    private val waiting = mutableMapOf<String, ArrayDeque<Continuation<ServiceResult>>>()

    val total: Int get() = calls.values.sum()

    fun calls(service: String): Int = calls[service] ?: 0

    fun waiting(service: String): Int = waiting[service]?.size ?: 0

    override suspend fun call(service: String, args: JsonObject): ServiceResult {
        calls[service] = calls(service) + 1
        this.args += service to args
        throwing[service]?.let { throw it }
        auto[service]?.let { return it }
        val queue = waiting.getOrPut(service) { ArrayDeque() }
        return if (cancellable) {
            suspendCancellableCoroutine { c ->
                queue.addLast(c)
                c.invokeOnCancellation {
                    cancelled[service] = (cancelled[service] ?: 0) + 1
                    queue.remove(c)
                }
            }
        } else {
            suspendCoroutine { c -> queue.addLast(c) }
        }
    }

    fun complete(service: String, result: ServiceResult) {
        val c = waiting[service]?.removeFirstOrNull() ?: fail("no pending call of $service")
        c.resume(result)
    }
}

internal class RecordingLog : FlowLog {
    val lines = mutableListOf<String>()

    override fun log(message: String) {
        lines += message
    }
}

internal fun FlowEngine.active(): FlowStatus.Active = assertIs<FlowStatus.Active>(current.value)

internal fun FlowEngine.stateId(): String? = (current.value as? FlowStatus.Active)?.state

/** Sends with the current generation, as a well-behaved host does. */
internal fun FlowEngine.send(event: String, payload: JsonObject = JsonObject(emptyMap())): EventResult =
    dispatch(event, payload, active().gen)

internal fun FlowEngine.ctx(path: String): JsonElement =
    path.trim('/').split('/').fold(active().context as JsonElement) { e, k -> (e as JsonObject).getValue(k) }

internal fun JsonElement.int(): Int = (this as JsonPrimitive).content.toInt()
