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

package eu.europa.ec.uxspike

import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A fake-engine script (boundary §3, `ux-spec/fixtures/scripts/<name>.json`, Q14): per service, the steps its calls
 * play in order; the last step repeats. Data only, so a new step or script needs no Kotlin (D8).
 */
@Serializable
data class FakeScript(
    val script: String,
    @SerialName("flow_id")
    val flowId: String,
    val calls: Map<String, List<FakeStep>>,
    val description: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): FakeScript = json.decodeFromString(serializer(), text)
    }
}

/** One answer: after [delayMs], either [result] (a result object with its `type` discriminator) or a throw ([fail]). */
@Serializable
data class FakeStep(
    @SerialName("delay_ms")
    val delayMs: Long = 0,
    val result: JsonObject? = null,
    val fail: String? = null,
)

/**
 * Plays a [FakeScript] with a call counter per service. Every call is logged as `<service>#<n>` (`UX-SPIKE: send#1`
 * once the host adds its prefix). Call it on one dispatcher (the engine's).
 */
class FakeScriptPlayer(val script: FakeScript, private val log: (String) -> Unit = {}) {
    private val counts = mutableMapOf<String, Int>()

    fun calls(service: String): Int = counts[service] ?: 0

    val totalCalls: Int get() = counts.values.sum()

    suspend fun <T> play(service: String, serializer: KSerializer<T>): T {
        val n = calls(service) + 1
        counts[service] = n
        log("$service#$n (script ${script.script})")
        val steps = script.calls[service].orEmpty()
        check(steps.isNotEmpty()) { "script '${script.script}' has no step for $service" }
        val step = steps[minOf(n, steps.size) - 1]
        if (step.delayMs > 0) delay(step.delayMs)
        step.fail?.let { error("scripted failure of $service#$n: $it") }
        val result = checkNotNull(step.result) { "step $n of $service in '${script.script}' has neither result nor fail" }
        return resultJson.decodeFromJsonElement(serializer, result)
    }

    private companion object {
        /** The contract's discriminator (boundary §2.5). */
        val resultJson = Json { classDiscriminator = "type" }
    }
}
