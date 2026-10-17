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

// H1-H12 of spike-contract-boundary.md §4 (M1b, on the provisional fixture), re-run at M6a on the real export.
package eu.europa.ec.ux.runtime.bundle

import eu.europa.ec.ux.runtime.engine.AbandonReason
import eu.europa.ec.ux.runtime.engine.FlowEngine
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.engine.FlowSnapshot
import eu.europa.ec.ux.runtime.engine.FlowStatus
import eu.europa.ec.ux.runtime.engine.RestoreResult
import eu.europa.ec.ux.runtime.fixture.ExportedPresBundle
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.json
import eu.europa.ec.ux.runtime.support.with
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandshakeTest {

    private val hash = Fixture.specHash
    private val flowId = Fixture.flowId

    /** The consent screen's id, read from the bundle so no screen id is written in :ux-runtime (KC1 guard). */
    private val requestScreen = Fixture.bundle().state("request").screen!!

    private fun load(root: JsonObject = Fixture.root, expected: String = hash, theme: String = expected, provisional: Boolean = false) =
        FlowBundle.load(Fixture.bytes(root), flowId, expected, theme, provisional)

    private fun refusal(result: LoadResult): RefusalReason = assertIs<LoadResult.Refused>(result).reason

    @Test
    fun fixtureIsTheExportedFile() {
        // The fixture task already refused any other sha256 than UX_SPEC.lock's; the hash is a real one.
        assertEquals(64, ExportedPresBundle.SHA256.length)
        assertTrue(hash.startsWith("sha256:"))
        assertFalse(hash.startsWith(FlowBundle.PROVISIONAL_PREFIX))
    }

    @Test
    fun h1_equalHashesAndVersion1Load() {
        val bundle = assertIs<LoadResult.Loaded>(load()).bundle
        // The counts come from the exported JSON itself (M8r: no pinned numbers, so a re-export that adds a state or a
        // screen keeps this green); the loader must keep every state and screen of it.
        val states = Fixture.root.getValue("states").jsonObject
        val screens = Fixture.root.getValue("screens").jsonObject
        assertTrue(states.isNotEmpty() && screens.isNotEmpty())
        assertEquals(states.keys, bundle.states.keys)
        assertEquals(screens.keys, bundle.screens.keys)
        assertEquals(hash, bundle.specHash)
        assertFalse(bundle.provisional)
    }

    @Test
    fun h2_specHashMismatchRefusesAndCallsNothing() = runTest {
        val other = "sha256:" + "0".repeat(64)
        val result = load(Fixture.root.with("/spec_hash", JsonPrimitive(other)), expected = hash)
        assertEquals(RefusalReason.SpecHashMismatch(hash, other), refusal(result))
        val port = FakePort()
        assertNull(FlowEngine.from(result, port, backgroundScope))
        assertEquals(0, port.total)
    }

    @Test
    fun h3_missingOrEmptySpecHashRefuses() {
        assertEquals(RefusalReason.MissingSpecHash, refusal(load(Fixture.root.with("/spec_hash", null))))
        assertEquals(RefusalReason.MissingSpecHash, refusal(load(Fixture.root.with("/spec_hash", JsonPrimitive("")))))
    }

    @Test
    fun h4_unsupportedSchemaVersionRefuses() {
        listOf(null, JsonPrimitive(0), JsonPrimitive(2), JsonPrimitive("1")).forEach { v ->
            assertIs<RefusalReason.UnsupportedSchemaVersion>(refusal(load(Fixture.root.with("/bundle_schema_version", v))), "$v")
        }
    }

    @Test
    fun h5_flowIdMismatchRefuses() {
        val r = refusal(load(Fixture.root.with("/flow_id", JsonPrimitive("FLW-other"))))
        assertEquals(RefusalReason.FlowIdMismatch(flowId, "FLW-other"), r)
    }

    @Test
    fun h6_themeHashMismatchRefuses() {
        val theme = "sha256:" + "1".repeat(64)
        assertEquals(RefusalReason.ThemeHashMismatch(hash, theme), refusal(load(theme = theme)))
    }

    @Test
    fun h7_provisionalHashRefusedWhenNotAllowed() {
        // The export has no provisional hash any more: plant one, as a leftover provisional bundle would carry it.
        val planted = FlowBundle.PROVISIONAL_PREFIX + hash
        val root = Fixture.root.with("/spec_hash", JsonPrimitive(planted))
        assertEquals(RefusalReason.ProvisionalNotAllowed, refusal(load(root, expected = planted)))
        assertIs<LoadResult.Loaded>(load(root, expected = planted, provisional = true))
    }

    @Test
    fun h8_malformedJsonOrWrongTypeRefuses() {
        val bad = FlowBundle.load("{\"bundle_schema_version\": 1,".encodeToByteArray(), flowId, hash, hash, true)
        assertIs<RefusalReason.Malformed>(refusal(bad))
        val wrongType = refusal(load(Fixture.root.with("/states/request/on", JsonPrimitive("CONTINUE"))))
        assertEquals("/states/request/on", assertIs<RefusalReason.Malformed>(wrongType).path)
        val wrongBool = refusal(load(Fixture.root.with("/services/send/idempotent", JsonPrimitive("no"))))
        assertEquals("/services/send/idempotent", assertIs<RefusalReason.Malformed>(wrongBool).path)
    }

    @Test
    fun h9_unknownSemanticEnumRefuses() {
        val cases = mapOf(
            "/states/request/back/policy" to "undo",
            "/states/request-optional-only/on/CONTINUE/0/guard/when/op" to "gt",
            "/states/request/kind" to "modal",
            "/states/request/on/TOGGLE_ITEM/0/assign/0/op" to "append",
            "/context/~1request~1selected_count/derive/op" to "sum_where",
            "/states/cancelled/invoke/scope" to "global",
            "/states/done/exit/kind" to "web",
            "/states/sending/restore/policy" to "replay",
        )
        cases.forEach { (path, value) ->
            val r = refusal(load(Fixture.root.with(path, JsonPrimitive(value))))
            assertEquals(RefusalReason.UnsupportedFeature(path, value), r, path)
        }
    }

    @Test
    fun h10_structuralInvariantRefuses() {
        val unguardedFirst = Fixture.root.with("/states/request-optional-only/on/CONTINUE/0/guard", null)
        val snapshotOnSending = Fixture.root.with("/states/sending/restore", json("""{"policy":"snapshot"}"""))
        val resultEventMissing = Fixture.root.with("/states/sending/invoke/on/Sent/event", JsonPrimitive("SYS_NOPE"))
        val unknownTarget = Fixture.root.with("/states/request/on/CONTINUE/0/target", JsonPrimitive("nowhere"))
        val unmappedResult = Fixture.root.with("/states/auth/invoke/on/Failed", null)
        listOf(
            unguardedFirst to "/states/request-optional-only/on/CONTINUE/0",
            snapshotOnSending to "/states/sending/restore/policy",
            resultEventMissing to "/states/sending/invoke/on/Sent/event",
            unknownTarget to "/states/request/on/CONTINUE/0/target",
            unmappedResult to "/states/auth/invoke/on",
        ).forEach { (root, path) ->
            assertEquals(path, assertIs<RefusalReason.InvalidBundle>(refusal(load(root)), path).path)
        }
    }

    @Test
    fun h11_snapshotOfAnotherSpecIsDiscardedAndExitsClosed() = runTest {
        val port = FakePort()
        val engine = FlowEngine(Fixture.bundle(), port, backgroundScope)
        val foreign = FlowSnapshot(1, "sha256:" + "2".repeat(64), "request", JsonObject(emptyMap()), listOf("request"), 4)
        val result = engine.restore(FlowSnapshot.fromJson(foreign.toJson()), FlowPlatform.ANDROID)
        runCurrent()
        assertEquals(RestoreResult.Abandoned(AbandonReason.SPEC_HASH_MISMATCH), result)
        val exited = assertIs<FlowStatus.Exited>(engine.current.value)
        assertEquals("closed", exited.exit?.state)
        assertEquals(0, port.total)
    }

    @Test
    fun h12_unknownDescriptiveKeyIsIgnored() {
        val root = Fixture.root.with("/screens/$requestScreen/regions/actions/0/x_note", JsonPrimitive(1))
            .with("/states/request/x_note", JsonPrimitive("note"))
        assertIs<LoadResult.Loaded>(load(root))
    }

    @Test
    fun unknownScreenEffectIsLoggedNotRefused() {
        val root = Fixture.root.with(
            "/screens/$requestScreen/rules/0/then/0",
            json("""{"effect":"sparkle","node":"instructions"}"""),
        )
        val bundle = assertIs<LoadResult.Loaded>(load(root)).bundle
        assertEquals(1, bundle.warnings.size)
        assertTrue("sparkle" in bundle.warnings.single())
    }
}
