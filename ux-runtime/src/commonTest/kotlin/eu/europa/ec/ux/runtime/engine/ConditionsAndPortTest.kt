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

import eu.europa.ec.ux.runtime.bundle.Condition
import eu.europa.ec.ux.runtime.bundle.ConditionOp
import eu.europa.ec.ux.runtime.support.obj
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The condition language of boundary §1.4.1, and the generated-code seam of [ServicePort.fromJsonInvoker]. */
class ConditionsAndPortTest {

    private val ctx = obj("""{"a":{"zero":0,"s":"0","empty":"","list":[],"t":true,"f":false,"n":null,"one":1.0}}""")

    private fun leaf(path: String, op: ConditionOp, value: JsonElement = JsonNull) = Condition.Leaf(path, null, op, value)

    private fun holds(c: Condition) = Conditions.eval(c, ctx)

    @Test
    fun emptinessCoversNullMissingEmptyStringAndEmptyList() {
        listOf("/a/n", "/a/missing", "/nope/deeper", "/a/empty", "/a/list").forEach { assertTrue(holds(leaf(it, ConditionOp.IS_EMPTY)), it) }
        listOf("/a/zero", "/a/s", "/a/f").forEach { assertTrue(holds(leaf(it, ConditionOp.NOT_EMPTY)), it) }
    }

    @Test
    fun equalityHasNoCoercion() {
        assertTrue(holds(leaf("/a/zero", ConditionOp.EQ, JsonPrimitive(0))))
        assertFalse(holds(leaf("/a/zero", ConditionOp.EQ, JsonPrimitive("0"))))
        assertFalse(holds(leaf("/a/s", ConditionOp.EQ, JsonPrimitive(0))))
        assertTrue(holds(leaf("/a/one", ConditionOp.EQ, JsonPrimitive(1))), "1.0 and 1 are the same JSON number")
        assertTrue(holds(leaf("/a/zero", ConditionOp.NEQ, JsonPrimitive("0"))))
        assertTrue(holds(leaf("/a/missing", ConditionOp.EQ, JsonNull)), "a missing path evaluates as null")
    }

    @Test
    fun booleansAndMembershipAndCombinators() {
        assertTrue(holds(leaf("/a/t", ConditionOp.IS_TRUE)))
        assertFalse(holds(leaf("/a/n", ConditionOp.IS_FALSE)), "null is not false")
        assertTrue(holds(leaf("/a/s", ConditionOp.IN, JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("0"))))))
        val t = leaf("/a/t", ConditionOp.IS_TRUE)
        val f = leaf("/a/f", ConditionOp.IS_TRUE)
        assertFalse(holds(Condition.All(listOf(t, f))))
        assertTrue(holds(Condition.AnyOf(listOf(t, f))))
        assertTrue(holds(Condition.Not(f)))
    }

    @Serializable
    sealed interface TypedResult {
        @Serializable
        @SerialName("Ready")
        data class Ready(val code: String) : TypedResult
    }

    /** Stands in for a generated typed `Services` method plus its generated invoker (boundary §2.5). */
    private class TypedServices {
        fun verificationCode(): TypedResult = TypedResult.Ready("1234")
    }

    @Test
    fun aTypedServiceReachesThePortThroughTheJsonInvoker() = runTest {
        val services = TypedServices()
        val json = Json { classDiscriminator = "type" }
        val invoker: suspend (String, JsonObject) -> JsonObject = { _, _ ->
            json.encodeToJsonElement(TypedResult.serializer(), services.verificationCode()).jsonObject
        }
        val result = ServicePort.fromJsonInvoker(invoke = invoker).call("verificationCode", JsonObject(emptyMap()))
        assertEquals(ServiceResult("Ready", obj("""{"code":"1234"}""")), result)
    }
}
