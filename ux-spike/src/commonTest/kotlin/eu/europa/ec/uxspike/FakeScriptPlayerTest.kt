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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** M9r (spike rule T1): a test-owned result type and test-owned service names, so no export can turn this red. */
@Serializable
private sealed interface TestResult {
    @Serializable
    @SerialName("Early")
    data object Early : TestResult

    @Serializable
    @SerialName("Done")
    data object Done : TestResult
}

class FakeScriptPlayerTest {

    private val script = FakeScript.parse(
        """
        {"script": "t", "flow_id": "F", "x_unknown": 1, "calls": {
          "first": [{"delay_ms": 5, "result": {"type": "Early"}}, {"result": {"type": "Done"}}],
          "second": [{"fail": "scripted"}]
        }}
        """.trimIndent(),
    )

    @Test
    fun stepsPlayInOrderAndTheLastRepeats() = runTest {
        val lines = mutableListOf<String>()
        val player = FakeScriptPlayer(script) { lines += it }

        assertIs<TestResult.Early>(player.play("first", TestResult.serializer()))
        assertIs<TestResult.Done>(player.play("first", TestResult.serializer()))
        assertIs<TestResult.Done>(player.play("first", TestResult.serializer()))

        assertEquals(3, player.calls("first"))
        assertEquals(listOf("first#1 (script t)", "first#2 (script t)", "first#3 (script t)"), lines)
    }

    @Test
    fun aFailStepThrowsAndAMissingServiceThrows() = runTest {
        val player = FakeScriptPlayer(script)
        assertFailsWith<IllegalStateException> { player.play("second", TestResult.serializer()) }
        assertFailsWith<IllegalStateException> { player.play("_test-missing", TestResult.serializer()) }
        assertEquals(1, player.calls("second"))
        assertEquals(1, player.calls("_test-missing"))
        assertEquals(2, player.totalCalls)
    }
}
