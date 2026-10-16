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

package eu.europa.ec.ux.runtime.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The only way the engine reaches app logic: a string-keyed call with a typed result envelope.
 *
 * `service` is a key of `bundle.services`; `args` holds the invoke's resolved `args`. The envelope's [ServiceResult.type]
 * is a result name of that service (`bundle.services.<name>.results`), and the engine matches it against `invoke.on`.
 * The generated, typed `Services` interface reaches this port through [ServicePort.fromJsonInvoker] at sync time.
 */
fun interface ServicePort {
    suspend fun call(service: String, args: JsonObject): ServiceResult

    companion object {
        /**
         * The adapter seam for generated code. The exporter emits an invoker (boundary §2.5) that calls the typed
         * `Services` interface and encodes its sealed result with a class discriminator. This wraps such an invoker,
         * for example `ServicePort.fromJsonInvoker(PresCrossDeviceInvoker(services)::invoke)`, so neither side depends
         * on the other: `:ux-contract` knows no runtime type, and the runtime knows no contract type.
         */
        fun fromJsonInvoker(
            discriminator: String = DEFAULT_DISCRIMINATOR,
            invoke: suspend (service: String, args: JsonObject) -> JsonObject,
        ): ServicePort = ServicePort { service, args ->
            val encoded = invoke(service, args)
            val type = (encoded[discriminator] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalStateException("result of '$service' has no '$discriminator' discriminator")
            ServiceResult(type, JsonObject(encoded - discriminator))
        }

        const val DEFAULT_DISCRIMINATOR: String = "type"
    }
}

/** A service result: the bundle result name and its fields (the `{result: <field>}` inputs of an assign). */
data class ServiceResult(val type: String, val payload: JsonObject = JsonObject(emptyMap()))

/** The engine's log sink; lines are meant to be prefixed `UX-SPIKE:` by the host. */
fun interface FlowLog {
    fun log(message: String)

    companion object {
        val NONE: FlowLog = FlowLog { }
    }
}
