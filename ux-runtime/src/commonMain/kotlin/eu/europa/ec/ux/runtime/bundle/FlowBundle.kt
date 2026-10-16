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

package eu.europa.ec.ux.runtime.bundle

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A flow bundle of `bundle_schema_version` 1 (spike-contract-boundary.md §1), after the loader has checked it.
 *
 * The model is generic: it names no flow, state, screen or service. Every behaviour of the engine comes from
 * these values. Build one only through [FlowBundle.load], which runs the spec-hash handshake (boundary §4).
 */
class FlowBundle internal constructor(
    val schemaVersion: Int,
    val flowId: String,
    val specHash: String,
    val provisional: Boolean,
    val initial: String,
    val entries: Map<String, EntryDef>,
    val processDeath: ProcessDeath,
    /** Context paths in bundle order; the order is the derive order. */
    val context: Map<String, ContextField>,
    val events: Map<String, EventDef>,
    val services: Map<String, ServiceDef>,
    val states: Map<String, StateDef>,
    val screens: Map<String, ScreenDef>,
    /** Non-fatal findings of the load (unknown screen effects, boundary §1.1), for the M6 divergence list. */
    val warnings: List<String>,
    /** The bundle as parsed; the screen interpreter (M5b) reads node trees from it. */
    val raw: JsonObject,
) {
    /**
     * The final state the flow ends in when a saved flow is discarded or abandoned (boundary §4 H11, brief §5 iOS
     * cold start): the single final state whose exit is `home` and `not-presented`. Null when there is not exactly one.
     */
    val abandonState: String? = states.values
        .filter { it.exit?.kind == ExitKind.HOME && it.exit.result == ExitResult.NOT_PRESENTED }
        .singleOrNull()?.id

    fun state(id: String): StateDef = states.getValue(id)

    companion object {
        const val SUPPORTED_SCHEMA_VERSION: Int = 1
        const val PROVISIONAL_PREFIX: String = "provisional:"

        /**
         * The spec-hash handshake (boundary §4, H1-H12). A [LoadResult.Refused] builds no engine and calls no service.
         * The production host always passes `allowProvisional = false`.
         */
        fun load(
            bytes: ByteArray,
            expectedFlowId: String,
            expectedSpecHash: String,
            themeSpecHash: String,
            allowProvisional: Boolean = false,
        ): LoadResult = BundleLoader.load(bytes, expectedFlowId, expectedSpecHash, themeSpecHash, allowProvisional)
    }
}

sealed interface LoadResult {
    data class Loaded(val bundle: FlowBundle) : LoadResult
    data class Refused(val reason: RefusalReason) : LoadResult
}

/** Why a bundle was refused. `path` is a JSON pointer into the bundle. */
sealed interface RefusalReason {
    data class SpecHashMismatch(val expected: String, val actual: String) : RefusalReason
    data object MissingSpecHash : RefusalReason
    data class UnsupportedSchemaVersion(val found: String) : RefusalReason
    data class FlowIdMismatch(val expected: String, val actual: String) : RefusalReason
    data class ThemeHashMismatch(val contract: String, val theme: String) : RefusalReason
    data object ProvisionalNotAllowed : RefusalReason
    data class Malformed(val path: String, val detail: String) : RefusalReason
    data class UnsupportedFeature(val path: String, val value: String) : RefusalReason
    data class InvalidBundle(val path: String, val detail: String) : RefusalReason
}

data class EntryDef(
    val name: String,
    val state: String,
    val schemes: List<String>,
    val requires: Set<EntryRequirement>,
    val assign: List<AssignOp>,
)

enum class EntryRequirement(val wire: String) { UNLOCKED("unlocked") }

/** `process_death` per platform (boundary §1.5). */
data class ProcessDeath(val android: ProcessDeathPolicy, val ios: ProcessDeathPolicy)

enum class ProcessDeathPolicy(val wire: String) { RESTORE_PER_STATE("restore-per-state"), ABANDON("abandon") }

data class ContextField(
    val path: String,
    val type: String,
    val nullable: Boolean,
    val default: JsonElement?,
    val derive: Derive?,
)

data class EventDef(val name: String, val source: String?, val payload: Map<String, String>)

data class ServiceDef(
    val name: String,
    val ref: String?,
    val args: Map<String, String>,
    val idempotent: Boolean,
    val results: Set<String>,
)

enum class StateKind(val wire: String) { STEP("step"), RECOVERY("recovery"), FINAL("final") }

data class StateDef(
    val id: String,
    val kind: StateKind,
    val screen: String?,
    val uiState: String?,
    val uiStateWhen: List<UiStateWhen>,
    /** Event name to its ordered branch list (boundary §1.4.1). */
    val on: Map<String, List<Branch>>,
    val invoke: Invoke?,
    val back: BackPolicy?,
    val restore: RestorePolicy?,
    val exit: ExitDef?,
) {
    val isFinal: Boolean get() = kind == StateKind.FINAL
}

data class UiStateWhen(val condition: Condition, val uiState: String)

data class Branch(
    val target: String?,
    val kind: String?,
    val guard: Guard?,
    val assign: List<AssignOp>,
    val errorClass: String?,
)

/** `rule` names the rule the guard was inlined from; it is traceability only. */
data class Guard(val rule: String?, val condition: Condition)

enum class InvokeScope(val wire: String) { STATE("state"), DETACHED("detached") }

data class Invoke(
    val service: String,
    val args: Map<String, ValueSource>,
    val scope: InvokeScope,
    /** Null: every event is accepted while the invoke is pending. */
    val acceptsWhilePending: Set<String>?,
    val on: Map<String, ResultMapping>,
)

/** `event` null is internal: the assigns apply and the state is kept. */
data class ResultMapping(val event: String?, val assign: List<AssignOp>)

sealed interface BackPolicy {
    data class Event(val event: String) : BackPolicy
    data class To(val target: String) : BackPolicy
    data class ToHistory(val candidates: List<String>, val fallback: String) : BackPolicy
    data object Blocked : BackPolicy
    data object Pass : BackPolicy
}

sealed interface RestorePolicy {
    data object Snapshot : RestorePolicy
    data class To(val target: String) : RestorePolicy
    data class ToHistory(val candidates: List<String>, val fallback: String) : RestorePolicy
}

enum class ExitKind(val wire: String) { HOME("home"), FLOW("flow"), PLATFORM("platform") }

enum class ExitResult(val wire: String) { PRESENTED("presented"), NOT_PRESENTED("not-presented") }

data class ExitDef(
    val kind: ExitKind,
    val ref: String?,
    val result: ExitResult,
    val carries: List<String>,
    val note: String?,
)

data class ScreenDef(val id: String, val template: String?, val uiStates: List<String>, val rules: List<ScreenRule>)

data class ScreenRule(val id: String, val condition: Condition, val effects: List<ScreenEffect>)

/** `known` is false for an effect this runtime does not apply; it is logged at load (boundary §1.1). */
data class ScreenEffect(val effect: String, val raw: JsonObject, val known: Boolean)
