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

// PROVISIONAL (M1b): replaced by ux-spec/ at M6; do not extend. Hand copy of spike-contract-boundary.md §2.
package eu.europa.ec.ux.contract.pres

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bridges the generic runtime (JSON in, JSON out) to the typed [PresCrossDeviceServices].
 * The result's `type` is the bundle result name; :ux-runtime matches it against `invoke.on`.
 * :ux-contract does not depend on :ux-runtime: the host passes `invoker::invoke` as a `suspend (String, JsonObject) -> JsonObject`.
 */
class PresCrossDeviceInvoker(private val services: PresCrossDeviceServices) {

    suspend fun invoke(service: String, args: JsonObject): JsonObject = when (service) {
        FETCH_REQUEST -> encode(FetchRequestResult.serializer(), services.fetchRequest(args.string("uri")))
        AUTHENTICATE -> encode(AuthenticateResult.serializer(), services.authenticate())
        VERIFICATION_CODE -> encode(VerificationCodeResult.serializer(), services.verificationCode())
        SEND -> encode(SendResult.serializer(), services.send(args.strings("selection")))
        CANCEL -> encode(CancelResult.serializer(), services.cancel())
        else -> error("Unknown service '$service' in ${PresCrossDeviceSpec.FLOW_ID}")
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject =
        json.encodeToJsonElement(serializer, value).jsonObject

    private companion object {
        const val FETCH_REQUEST = "fetchRequest"
        const val AUTHENTICATE = "authenticate"
        const val VERIFICATION_CODE = "verificationCode"
        const val SEND = "send"
        const val CANCEL = "cancel"
        // encodeDefaults: a field left at its default (`selected: false`, `value: null`, `privacy_url: null`) still
        // reaches the engine, so the context never depends on the defaults of the contract classes (M4 finding 2).
        val json = Json {
            classDiscriminator = "type"
            encodeDefaults = true
        }
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }
