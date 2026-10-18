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
import eu.europa.ec.ux.runtime.support.Synthetic
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

    /** A screen of the export with rules, read from the bundle so no screen id is written here (KC1 guard, rule T1). */
    private val ruledScreen = Fixture.bundle().screens.values.first { it.rules.isNotEmpty() }.id

    /** M9r (rule T1): the loader's refusals are engine semantics, checked on the synthetic bundle's test-owned paths. */
    private fun loadSynthetic(root: JsonObject) = FlowBundle.load(Fixture.bytes(root), Synthetic.FLOW_ID, Synthetic.SPEC_HASH, Synthetic.SPEC_HASH)

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
        val wrongType = refusal(loadSynthetic(Synthetic.root.with("/states/choose/on", JsonPrimitive("GO"))))
        assertEquals("/states/choose/on", assertIs<RefusalReason.Malformed>(wrongType).path)
        val wrongBool = refusal(loadSynthetic(Synthetic.root.with("/services/commit/idempotent", JsonPrimitive("no"))))
        assertEquals("/services/commit/idempotent", assertIs<RefusalReason.Malformed>(wrongBool).path)
    }

    @Test
    fun h9_unknownSemanticEnumRefuses() {
        val cases = mapOf(
            "/states/choose/back/policy" to "undo",
            "/states/choose-opt/on/GO/0/guard/when/op" to "gt",
            "/states/choose/kind" to "modal",
            "/states/choose/on/PICK/0/assign/0/op" to "append",
            "/context/~1picked_count/derive/op" to "sum_where",
            "/states/aborted/invoke/scope" to "global",
            "/states/finished/exit/kind" to "web",
            "/states/commit/restore/policy" to "replay",
        )
        cases.forEach { (path, value) ->
            val r = refusal(loadSynthetic(Synthetic.root.with(path, JsonPrimitive(value))))
            assertEquals(RefusalReason.UnsupportedFeature(path, value), r, path)
        }
    }

    @Test
    fun h10_structuralInvariantRefuses() {
        val unguardedFirst = Synthetic.root.with("/states/choose-opt/on/GO/0/guard", null)
        val snapshotOnCommit = Synthetic.root.with("/states/commit/restore", json("""{"policy":"snapshot"}"""))
        val resultEventMissing = Synthetic.root.with("/states/commit/invoke/on/Committed/event", JsonPrimitive("NOPE"))
        val unknownTarget = Synthetic.root.with("/states/choose/on/GO/0/target", JsonPrimitive("nowhere"))
        val unmappedResult = Synthetic.root.with("/states/check/invoke/on/Passed", null)
        listOf(
            unguardedFirst to "/states/choose-opt/on/GO/0",
            snapshotOnCommit to "/states/commit/restore/policy",
            resultEventMissing to "/states/commit/invoke/on/Committed/event",
            unknownTarget to "/states/choose/on/GO/0/target",
            unmappedResult to "/states/check/invoke/on",
        ).forEach { (root, path) ->
            assertEquals(path, assertIs<RefusalReason.InvalidBundle>(refusal(loadSynthetic(root)), path).path)
        }
    }

    @Test
    fun h11_snapshotOfAnotherSpecIsDiscardedAndExitsClosed() = runTest {
        val port = FakePort()
        val engine = FlowEngine(Fixture.bundle(), port, backgroundScope)
        val state = Fixture.bundle().initial
        val foreign = FlowSnapshot(1, "sha256:" + "2".repeat(64), state, JsonObject(emptyMap()), listOf(state), 4)
        val result = engine.restore(FlowSnapshot.fromJson(foreign.toJson()), FlowPlatform.ANDROID)
        runCurrent()
        assertEquals(RestoreResult.Abandoned(AbandonReason.SPEC_HASH_MISMATCH), result)
        val exited = assertIs<FlowStatus.Exited>(engine.current.value)
        assertEquals(Fixture.bundle().abandonState, exited.exit?.state)
        assertEquals(0, port.total)
    }

    @Test
    fun h12_unknownDescriptiveKeyIsIgnored() {
        val region = Fixture.bundle().screens.getValue(ruledScreen).let { ((Fixture.root["screens"] as JsonObject)[it.id] as JsonObject)["regions"] as JsonObject }.keys.first()
        val root = Fixture.root.with("/screens/$ruledScreen/regions/$region/0/x_note", JsonPrimitive(1))
            .with("/states/${Fixture.bundle().initial}/x_note", JsonPrimitive("note"))
        assertIs<LoadResult.Loaded>(load(root))
    }

    @Test
    fun unknownScreenEffectIsLoggedNotRefused() {
        val root = Fixture.root.with(
            "/screens/$ruledScreen/rules/0/then/0",
            json("""{"effect":"sparkle","node":"_test-node"}"""),
        )
        val bundle = assertIs<LoadResult.Loaded>(load(root)).bundle
        assertEquals(1, bundle.warnings.size)
        assertTrue("sparkle" in bundle.warnings.single())
    }
}
