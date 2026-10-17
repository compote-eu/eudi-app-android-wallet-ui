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

// M5b test, re-run at M6a on the generated invoker (V26: VerifierPayload, RequestAttributePayload, named arguments).
package eu.europa.ec.ux.contract

import eu.europa.ec.ux.contract.pres.AuthenticateResult
import eu.europa.ec.ux.contract.pres.CancelResult
import eu.europa.ec.ux.contract.pres.FetchRequestResult
import eu.europa.ec.ux.contract.pres.PresCrossDeviceInvoker
import eu.europa.ec.ux.contract.pres.PresCrossDeviceServices
import eu.europa.ec.ux.contract.pres.RequestAttributePayload
import eu.europa.ec.ux.contract.pres.RequestPayload
import eu.europa.ec.ux.contract.pres.Requirement
import eu.europa.ec.ux.contract.pres.SendResult
import eu.europa.ec.ux.contract.pres.Trust
import eu.europa.ec.ux.contract.pres.VerificationCodeResult
import eu.europa.ec.ux.contract.pres.VerifierPayload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** M4 open issue: the invoker must send fields left at their defaults, so the engine never has to guess them. */
class InvokerDefaultsTest {

    private val services = object : PresCrossDeviceServices {
        override suspend fun fetchRequest(uri: String): FetchRequestResult = FetchRequestResult.RequestReady(
            verifier = VerifierPayload(name = "V", serviceName = "S", reason = "R", trust = Trust.TRUSTED),
            request = RequestPayload(documentName = "PID", attributes = listOf(RequestAttributePayload(id = "a", labelCopy = "L", requirement = Requirement.OPTIONAL))),
        )

        override suspend fun authenticate(): AuthenticateResult = AuthenticateResult.Error()

        override suspend fun verificationCode(): VerificationCodeResult = VerificationCodeResult.Ready(code = "1")

        override suspend fun send(selection: List<String>): SendResult = SendResult.Sent

        override suspend fun cancel(): CancelResult = CancelResult.Cancelled
    }

    private val invoker = PresCrossDeviceInvoker(services)

    @Test
    fun defaultsOfARequestAreEncoded() = runTest {
        val result = invoker.invoke("fetchRequest", JsonObject(mapOf("uri" to JsonPrimitive("openid4vp://x"))))
        val verifier = result.getValue("verifier").jsonObject
        val item = result.getValue("request").jsonObject.getValue("attributes").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("RequestReady"), result["type"])
        assertEquals(JsonNull, verifier["privacy_url"])
        assertEquals(JsonPrimitive(false), item["selected"])
        assertEquals(JsonNull, item["value"])
    }

    @Test
    fun defaultNullFieldsOfOtherResultsAreEncoded() = runTest {
        assertEquals(JsonNull, invoker.invoke("verificationCode", JsonObject(emptyMap()))["portal_url"])
        assertEquals(JsonNull, invoker.invoke("authenticate", JsonObject(emptyMap()))["reason"])
    }
}
