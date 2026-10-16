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

package eu.europa.ec.ux.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The D1 grep of SPIKE-PLAN §3, as a gate test. Back in the flow is handled at exactly one place: one back-handler call
 * in `FlowHost`, and one platform adapter per hook; nothing else in the spike modules, the iOS glue or the redirect
 * site handles Back. The patterns are spelled with character classes so this file does not match them itself.
 */
class D1BackHandlerGrepTest {

    private val root: File = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile && File(it, "ux-runtime").isDirectory }

    private fun sources(vararg dirs: String): List<File> = dirs.map { File(root, it) }.filter { it.exists() }.flatMap { dir ->
        dir.walkTopDown().onEnter { it.name != "build" }.filter { it.isFile && it.extension in setOf("kt", "kts") }.toList()
    }

    private fun hits(files: List<File>, pattern: Regex): List<String> = files.flatMap { f ->
        f.readLines().mapIndexedNotNull { i, line -> if (pattern.containsMatchIn(line)) "${f.relativeTo(root)}:${i + 1}: ${line.trim()}" else null }
    }

    private val spikeDirs: List<String> = root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("ux-") }.map { it.name } +
        "shared-ui/src/iosUxSpikeMain"

    @Test
    fun theRuntimeCallsItsBackHandlerOnceInFlowHost() {
        val calls = hits(sources("ux-runtime"), Regex("""\bback[H]andler\("""))
        assertEquals(1, calls.size, calls.joinToString("\n"))
        assertTrue(calls.single().startsWith("ux-runtime/src/commonMain/kotlin/eu/europa/ec/ux/runtime/host/FlowHost.kt:"), calls.single())
    }

    @Test
    fun eachHookHasExactlyOnePlatformAdapter() {
        listOf("shared-ui/src/iosUxSpikeMain", "ux-spike-android").forEach { hook ->
            val adapters = hits(sources(hook), Regex("""Platform[B]ackHandler\("""))
            assertEquals(1, adapters.size, "$hook: ${adapters.joinToString("\n")}")
        }
    }

    @Test
    fun nothingElseHandlesBack() {
        val pattern = Regex("""[B]ackHandler\(|Navigation[E]ventHandler|Navigation[B]ackHandler|on[B]ack *=""")
        val all = hits(sources(*spikeDirs.toTypedArray()), pattern)
        val adapters = all.filter { Regex("""Platform[B]ackHandler\(""").containsMatchIn(it) }
        assertEquals(2, adapters.size, all.joinToString("\n"))
        assertEquals(adapters, all, "back handling outside the FlowHost call and the two adapters")
        assertTrue(sources("ux-spike", "ux-elements", "ux-theme", "ux-contract").none { f -> f.readText().contains(Regex("""[B]ackHandler""")) })
    }

    @Test
    fun theRedirectSiteHasNoBackHandling() {
        val lines = File(root, "shared-ui/src/commonMain/kotlin/eu/europa/ec/shared/navigation/SharedEntries.kt").readLines()
        val start = lines.indexOfFirst { "fun redirectedByDebugHook(" in it }
        assertTrue(start >= 0, "redirect site not found")
        val body = lines.drop(start).takeWhile { it != "}" }
        val back = Regex("""[B]ackHandler|on[B]ack|\.pop\(|Navigation[E]vent""")
        assertEquals(emptyList(), body.filter { back.containsMatchIn(it) })
        assertTrue(lines.count { "redirectedByDebugHook(navigator, route)" in it } >= 2, "both redirect calls (option A)")
    }
}
