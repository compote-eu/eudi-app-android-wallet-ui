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

import eu.europa.ec.ux.contract.pres.SendResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FakeScriptPlayerTest {

    private val script = FakeScript.parse(
        """
        {"script": "t", "flow_id": "F", "x_unknown": 1, "calls": {
          "send": [{"delay_ms": 5, "result": {"type": "Expired"}}, {"result": {"type": "Sent"}}],
          "cancel": [{"fail": "scripted"}]
        }}
        """.trimIndent(),
    )

    @Test
    fun stepsPlayInOrderAndTheLastRepeats() = runTest {
        val lines = mutableListOf<String>()
        val player = FakeScriptPlayer(script) { lines += it }

        assertIs<SendResult.Expired>(player.play("send", SendResult.serializer()))
        assertIs<SendResult.Sent>(player.play("send", SendResult.serializer()))
        assertIs<SendResult.Sent>(player.play("send", SendResult.serializer()))

        assertEquals(3, player.calls("send"))
        assertEquals(listOf("send#1 (script t)", "send#2 (script t)", "send#3 (script t)"), lines)
    }

    @Test
    fun aFailStepThrowsAndAMissingServiceThrows() = runTest {
        val player = FakeScriptPlayer(script)
        assertFailsWith<IllegalStateException> { player.play("cancel", SendResult.serializer()) }
        assertFailsWith<IllegalStateException> { player.play("authenticate", SendResult.serializer()) }
        assertEquals(1, player.calls("cancel"))
        assertEquals(1, player.calls("authenticate"))
        assertEquals(2, player.totalCalls)
    }
}
