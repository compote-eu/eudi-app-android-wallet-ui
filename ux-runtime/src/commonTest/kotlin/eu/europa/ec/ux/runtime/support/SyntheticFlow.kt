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

// M9r (spike rule T1, SPIKE-PLAN §0.2b): a small test-only bundle for the engine's generic semantics (async, back and
// restore policies, saved state, RULING-2, guards and derive). Every name in it (states, events, services, results,
// context paths) is owned by this file, so no export can turn these tests red. The tests that check the real export
// read their expectations from ux-spec/ (ExportWalk) instead.
package eu.europa.ec.ux.runtime.support

import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.LoadResult
import eu.europa.ec.ux.runtime.engine.FlowEngine
import eu.europa.ec.ux.runtime.engine.ServiceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.fail

/**
 * The synthetic flow. Shape (the parts the generic tests need, nothing more):
 *
 * - entries: `default` → [START]; [LINK] → [LOADING] (`requires: [unlocked]`, assigns the launch `uri`);
 * - [START] --SCANNED{uri}--> [LOADING], which invokes the non-idempotent `load` (restore: to [FAILED]);
 * - `load` results lead to [CHOOSE] (a mandatory item), [CHOOSE_OPT] (optional items only, nothing picked) or
 *   [CHOOSE_ALT] (an alternative path through [CONFIRM_ALT], back `to`);
 * - the choose family follows the derived selection with guards and assign-then-guard ([CHOOSE_OPT_ON], [CHOOSE_NONE]);
 * - GO → [CHECK] (idempotent `check`; back and restore `to_history` over the choose family) → [CODE] (idempotent
 *   `produce`, whose result is internal, `accepts_while_pending`, `ui_state_when`) → CONFIRM → [COMMIT] (non-idempotent
 *   `commit` with the derived selection; back blocked; restore to [FAILED]) → [OK] → [FINISHED] (home, presented);
 * - ABORT → [ABORTED] (a detached `stop`); errors → [FAILED] (recovery) → HELP → [HELPED] (flow exit carrying the error
 *   class) or CLOSE → [CLOSED] (home, not presented: the abandon state).
 */
internal object Synthetic {
    const val FLOW_ID: String = "FLW-_test-synthetic"
    val SPEC_HASH: String = "sha256:" + "5".repeat(64)

    const val LINK = "link"
    const val START = "start"
    const val LOADING = "loading"
    const val CHOOSE = "choose"
    const val CHOOSE_OPT = "choose-opt"
    const val CHOOSE_OPT_ON = "choose-opt-on"
    const val CHOOSE_NONE = "choose-none"
    const val CHOOSE_ALT = "choose-alt"
    const val CONFIRM_ALT = "confirm-alt"
    const val CHECK = "check"
    const val CODE = "code"
    const val COMMIT = "commit"
    const val OK = "ok"
    const val ABORTED = "aborted"
    const val FAILED = "failed"
    const val FINISHED = "finished"
    const val CLOSED = "closed"
    const val HELPED = "helped"

    const val SVC_LOAD = "load"
    const val SVC_CHECK = "check"
    const val SVC_PRODUCE = "produce"
    const val SVC_COMMIT = "commit"
    const val SVC_STOP = "stop"

    const val SCANNED = "SCANNED"
    const val GO = "GO"
    const val PICK = "PICK"
    const val ABORT = "ABORT"
    const val CONFIRM = "CONFIRM"
    const val CLOSE = "CLOSE"
    const val HELP = "HELP"

    const val NO_URL = "no-url"
    const val DEFAULT_UI = "default"
    const val TECHNICAL = "technical-error"
    const val REFUSAL = "user-refusal"
    const val URI = "test://synthetic/request"

    private const val PICKED = """{"any": [{"field": "must", "op": "is_true"}, {"field": "on", "op": "is_true"}]}"""
    private const val TOGGLE = """{"op": "toggle_item", "list": "/items", "key": "id", "key_from": {"event": "item_id"}, "field": "on"}"""
    private const val ZERO = """{"op": "eq", "path": "/picked_count", "value": 0}"""
    private const val NONZERO = """{"not": $ZERO}"""
    private const val ABORT_BRANCH = """"ABORT": [{"target": "aborted", "kind": "alt", "error_class": "user-refusal"}]"""
    private const val HISTORY =
        """{"policy": "to_history", "candidates": ["choose", "choose-opt", "choose-opt-on", "choose-none", "choose-alt"], "fallback": "choose"}"""
    private const val LOADED_ASSIGN =
        """[{"op": "merge", "path": "/info", "from": {"result": "info"}}, {"op": "set", "path": "/items", "from": {"result": "items"}}]"""

    val JSON: String = """
    {
      "bundle_schema_version": 1,
      "flow_id": "$FLOW_ID",
      "spec_hash": "$SPEC_HASH",
      "initial": "start",
      "entries": {
        "default": {"state": "start"},
        "link": {"state": "loading", "schemes": ["test"], "requires": ["unlocked"],
                 "assign": [{"op": "set", "path": "/in/uri", "from": {"launch": "uri"}}]}
      },
      "process_death": {"android": "restore-per-state", "ios": "abandon"},
      "context": {
        "/in/uri": {"type": "url", "nullable": true, "default": null},
        "/info/name": {"type": "string", "nullable": true, "default": null},
        "/info/kind": {"type": "string", "default": "plain"},
        "/items": {"type": "list", "default": []},
        "/picked": {"type": "list", "default": [], "derive": {"op": "ids_where", "list": "/items", "key": "id", "where": $PICKED}},
        "/picked_count": {"type": "number", "default": 0, "derive": {"op": "count_where", "list": "/items", "where": $PICKED}},
        "/tried": {"type": "boolean", "default": false},
        "/code": {"type": "string", "nullable": true, "default": null},
        "/url": {"type": "url", "nullable": true, "default": null},
        "/flow/error_class": {"type": "string", "nullable": true, "default": null}
      },
      "events": {
        "SCANNED": {"source": "ui", "payload": {"uri": "url"}},
        "GO": {"source": "ui"}, "PICK": {"source": "ui", "payload": {"item_id": "id"}}, "ABORT": {"source": "ui"},
        "CONFIRM": {"source": "ui"}, "CLOSE": {"source": "ui"}, "HELP": {"source": "ui"},
        "LOADED": {"source": "service"}, "LOADED_OPT": {"source": "service"}, "LOADED_ALT": {"source": "service"},
        "OK": {"source": "service"}, "FAIL": {"source": "service"}, "DONE": {"source": "service"}
      },
      "services": {
        "load": {"args": {"uri": "url"}, "idempotent": false, "results": {"Loaded": {}, "LoadedOpt": {}, "LoadedAlt": {}, "Error": {}}},
        "check": {"args": {}, "idempotent": true, "results": {"Passed": {}, "Error": {}}},
        "produce": {"args": {}, "idempotent": true, "results": {"Made": {}, "Error": {}}},
        "commit": {"args": {"picked": {"type": "list", "of": "id"}}, "idempotent": false, "results": {"Committed": {}, "Error": {}}},
        "stop": {"args": {}, "idempotent": true, "results": {"Stopped": {}, "Error": {}}}
      },
      "states": {
        "start": {"kind": "step",
          "on": {"SCANNED": [{"target": "loading", "kind": "main", "assign": [{"op": "set", "path": "/in/uri", "from": {"event": "uri"}}]}],
                 "ABORT": [{"target": "closed", "kind": "alt"}]},
          "back": {"policy": "pass"}, "restore": {"policy": "snapshot"}},
        "loading": {"kind": "step",
          "invoke": {"service": "load", "args": {"uri": {"context": "/in/uri"}}, "on": {
            "Loaded": {"event": "LOADED", "assign": $LOADED_ASSIGN},
            "LoadedOpt": {"event": "LOADED_OPT", "assign": $LOADED_ASSIGN},
            "LoadedAlt": {"event": "LOADED_ALT", "assign": $LOADED_ASSIGN},
            "Error": {"event": "FAIL"}}},
          "on": {"LOADED": [{"target": "choose", "kind": "main"}], "LOADED_OPT": [{"target": "choose-opt", "kind": "alt"}],
                 "LOADED_ALT": [{"target": "choose-alt", "kind": "error", "error_class": "alt-path"}],
                 "FAIL": [{"target": "failed", "kind": "error", "error_class": "technical-error"}], $ABORT_BRANCH},
          "back": {"policy": "event", "event": "ABORT"}, "restore": {"policy": "to", "target": "failed"}},
        "choose": {"kind": "step",
          "on": {"PICK": [{"target": null, "kind": "alt", "assign": [$TOGGLE]}],
                 "GO": [{"target": "check", "kind": "main", "guard": {"when": $NONZERO}}], $ABORT_BRANCH},
          "back": {"policy": "event", "event": "ABORT"}, "restore": {"policy": "snapshot"}},
        "choose-opt": {"kind": "step",
          "on": {"PICK": [{"target": null, "kind": "alt", "assign": [$TOGGLE], "guard": {"when": $ZERO}},
                          {"target": "choose-opt-on", "kind": "alt", "assign": [$TOGGLE]}],
                 "GO": [{"target": "choose-none", "kind": "alt", "assign": [{"op": "set", "path": "/tried", "value": true}], "guard": {"when": $ZERO}},
                        {"target": "check", "kind": "main", "guard": {"when": $NONZERO}}], $ABORT_BRANCH},
          "back": {"policy": "event", "event": "ABORT"}, "restore": {"policy": "snapshot"}},
        "choose-opt-on": {"kind": "step",
          "on": {"PICK": [{"target": "choose-none", "kind": "alt", "assign": [$TOGGLE],
                           "guard": {"when": {"all": [$ZERO, {"op": "is_true", "path": "/tried"}]}}},
                          {"target": "choose-opt", "kind": "alt", "assign": [$TOGGLE], "guard": {"when": $ZERO}},
                          {"target": null, "kind": "alt", "assign": [$TOGGLE]}],
                 "GO": [{"target": "check", "kind": "main", "guard": {"when": $NONZERO}}], $ABORT_BRANCH},
          "back": {"policy": "event", "event": "ABORT"}, "restore": {"policy": "snapshot"}},
        "choose-none": {"kind": "step",
          "on": {"PICK": [{"target": "choose-opt-on", "kind": "alt", "assign": [$TOGGLE]}],
                 "GO": [{"target": null, "kind": "alt"}], $ABORT_BRANCH},
          "back": {"policy": "event", "event": "ABORT"}, "restore": {"policy": "snapshot"}},
        "choose-alt": {"kind": "step",
          "on": {"GO": [{"target": "confirm-alt", "kind": "main"}], $ABORT_BRANCH},
          "back": {"policy": "event", "event": "ABORT"}, "restore": {"policy": "snapshot"}},
        "confirm-alt": {"kind": "step",
          "on": {"GO": [{"target": "check", "kind": "main"}], $ABORT_BRANCH},
          "back": {"policy": "to", "target": "choose-alt"}, "restore": {"policy": "to", "target": "choose-alt"}},
        "check": {"kind": "step",
          "invoke": {"service": "check", "args": {}, "on": {"Passed": {"event": "OK"}, "Error": {"event": "FAIL"}}},
          "on": {"OK": [{"target": "code", "kind": "main"}],
                 "FAIL": [{"target": "failed", "kind": "error", "error_class": "technical-error"}], $ABORT_BRANCH},
          "back": $HISTORY, "restore": $HISTORY},
        "code": {"kind": "step", "ui_state": "default",
          "ui_state_when": [{"when": {"op": "is_empty", "path": "/url"}, "ui_state": "no-url"}],
          "invoke": {"service": "produce", "args": {}, "accepts_while_pending": ["ABORT", "FAIL"], "on": {
            "Made": {"event": null, "assign": [{"op": "set", "path": "/code", "from": {"result": "code"}},
                                               {"op": "set", "path": "/url", "from": {"result": "url"}}]},
            "Error": {"event": "FAIL"}}},
          "on": {"CONFIRM": [{"target": "commit", "kind": "main"}],
                 "FAIL": [{"target": "failed", "kind": "error", "error_class": "technical-error"}], $ABORT_BRANCH},
          "back": $HISTORY, "restore": $HISTORY},
        "commit": {"kind": "step",
          "invoke": {"service": "commit", "args": {"picked": {"context": "/picked"}}, "on": {"Committed": {"event": "DONE"}, "Error": {"event": "FAIL"}}},
          "on": {"DONE": [{"target": "ok", "kind": "main"}], "FAIL": [{"target": "failed", "kind": "error", "error_class": "technical-error"}]},
          "back": {"policy": "blocked"}, "restore": {"policy": "to", "target": "failed"}},
        "ok": {"kind": "step", "on": {"CLOSE": [{"target": "finished", "kind": "main"}]},
          "back": {"policy": "event", "event": "CLOSE"}, "restore": {"policy": "snapshot"}},
        "aborted": {"kind": "step", "invoke": {"service": "stop", "args": {}, "scope": "detached", "on": {}},
          "on": {"CLOSE": [{"target": "closed", "kind": "main"}]},
          "back": {"policy": "event", "event": "CLOSE"}, "restore": {"policy": "snapshot"}},
        "failed": {"kind": "recovery", "on": {"CLOSE": [{"target": "closed", "kind": "alt"}], "HELP": [{"target": "helped", "kind": "alt"}]},
          "back": {"policy": "event", "event": "CLOSE"}, "restore": {"policy": "snapshot"}},
        "finished": {"kind": "final", "exit": {"kind": "home", "ref": null, "result": "presented"}},
        "closed": {"kind": "final", "exit": {"kind": "home", "ref": null, "result": "not-presented"}},
        "helped": {"kind": "final", "exit": {"kind": "flow", "ref": "_test-help", "result": "not-presented", "carries": ["/flow/error_class"]}}
      }
    }
    """.trimIndent()

    val root: JsonObject by lazy { Json.parseToJsonElement(JSON).jsonObject }

    fun load(root: JsonObject = this.root): LoadResult = eu.europa.ec.ux.runtime.bundle.FlowBundle.load(
        Json.encodeToString(JsonObject.serializer(), root).encodeToByteArray(),
        FLOW_ID,
        SPEC_HASH,
        SPEC_HASH,
    )

    fun bundle(root: JsonObject = this.root): FlowBundle = when (val r = load(root)) {
        is LoadResult.Loaded -> r.bundle
        is LoadResult.Refused -> fail("synthetic bundle refused: ${r.reason}")
    }

    val launch: JsonObject = buildJsonObject { put("uri", URI) }
}

/** The synthetic services' results (test-owned names and payloads). */
internal object SynthResults {
    private fun loaded(type: String, vararg items: Pair<String, Boolean>) = ServiceResult(
        type,
        obj(
            """{"info": {"name": "Tester"}, "items": [${items.joinToString { (id, must) -> """{"id": "$id", "must": $must}""" }}]}""",
        ),
    )

    /** A mandatory item `m1` and an optional `o1`: the derived selection is never empty. */
    val loaded = loaded("Loaded", "m1" to true, "o1" to false)

    /** Two optional items, nothing picked. */
    val loadedOpt = loaded("LoadedOpt", "o1" to false, "o2" to false)
    val loadedAlt = loaded("LoadedAlt", "m1" to true, "o1" to false)
    val passed = ServiceResult("Passed")
    val made = ServiceResult("Made", obj("""{"code": "1234", "url": "https://synthetic.example/p"}"""))
    val madeNoUrl = ServiceResult("Made", obj("""{"code": "1234", "url": null}"""))
    val committed = ServiceResult("Committed")
}

/** Drives the synthetic flow to a named state through the engine's public API only. */
internal class SynthDriver(
    val scope: TestScope,
    val port: FakePort = FakePort(),
    engineScope: CoroutineScope = scope.backgroundScope,
    bundle: FlowBundle = Synthetic.bundle(),
) {
    val log = RecordingLog()
    val engine = FlowEngine(bundle, port, engineScope, log)

    /** Start → SCANNED → `load` answers [result] → a choose state. */
    fun choose(result: ServiceResult = SynthResults.loaded): SynthDriver = apply {
        port.auto[Synthetic.SVC_LOAD] = result
        engine.start()
        engine.send(Synthetic.SCANNED, buildJsonObject { put("uri", Synthetic.URI) })
        scope.runCurrent()
    }

    /** GO into `check` (its `check` call waits unless answered). */
    fun check(): SynthDriver = apply {
        engine.send(Synthetic.GO)
        scope.runCurrent()
    }

    /** Through `check` into `code`, `produce` answered. */
    fun code(): SynthDriver = apply {
        port.auto[Synthetic.SVC_CHECK] = SynthResults.passed
        port.auto[Synthetic.SVC_PRODUCE] = SynthResults.made
        check()
    }

    /** CONFIRM into `commit` (its `commit` call waits unless answered). */
    fun commit(): SynthDriver = apply {
        code()
        engine.send(Synthetic.CONFIRM)
        scope.runCurrent()
    }
}

internal fun FlowEngine.pick(id: String) = send(Synthetic.PICK, buildJsonObject { put("item_id", id) })
