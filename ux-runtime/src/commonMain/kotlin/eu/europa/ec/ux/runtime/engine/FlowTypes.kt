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

import eu.europa.ec.ux.runtime.bundle.ExitKind
import eu.europa.ec.ux.runtime.bundle.ExitResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What the host renders. */
sealed interface FlowStatus {
    data object Idle : FlowStatus

    data class Active(
        val state: String,
        val screen: String?,
        val uiState: String?,
        val context: JsonObject,
        /** The generation token: the host stamps every event it sends with it. */
        val gen: Long,
        /** A state-scoped invoke is running. */
        val pending: Boolean,
        /** False only for `back.policy = pass`: the host disables its handler and Nav3 pops the flow entry. */
        val backEnabled: Boolean,
    ) : FlowStatus

    /** `exit` is null only when a discarded or abandoned flow has no home/not-presented final state. */
    data class Exited(val exit: FlowExit?, val reason: ExitReason) : FlowStatus
}

enum class ExitReason { FINAL_STATE, ABANDONED }

/** The structured exit of a final state (boundary §1.4). `carries` maps each carried path to its value. */
data class FlowExit(
    val state: String,
    val kind: ExitKind,
    val ref: String?,
    val result: ExitResult,
    val carries: Map<String, JsonElement>,
)

enum class FlowPlatform { ANDROID, IOS }

sealed interface StartResult {
    data class Started(val state: String) : StartResult
    data class UnknownEntry(val entry: String) : StartResult

    /** The entry `requires: [unlocked]` and the host did not pass `unlocked = true` (boundary §1.5). */
    data class Locked(val entry: String) : StartResult
    data object AlreadyStarted : StartResult
}

sealed interface EventResult {
    data class Transitioned(val target: String) : EventResult
    data object Internal : EventResult
    data class Dropped(val reason: DropReason) : EventResult
}

enum class DropReason {
    /** No flow, or the flow has exited. */
    INACTIVE,

    /** The event's `gen` is not the current one: a late or repeated event (the double tap). */
    STALE_GENERATION,

    /** The state has no `on` entry for the event. */
    NOT_ACCEPTED,

    /** An invoke is pending and the event is not in `accepts_while_pending`. */
    PENDING,

    /** No branch's guard held. */
    NO_BRANCH,
}

sealed interface BackResult {
    /** `back.policy = event`: the named event went through [FlowEngine.dispatch]. */
    data class Fired(val event: String, val outcome: EventResult) : BackResult

    /** `to` or `to_history`. */
    data class Moved(val target: String) : BackResult

    /** Consumed; nothing happens, and the platform handler stays enabled. */
    data object Blocked : BackResult

    /** The host lets the platform pop the flow entry. */
    data object Pass : BackResult
    data object Inactive : BackResult
}

sealed interface RestoreResult {
    data class Restored(val state: String, val reinvoked: Boolean) : RestoreResult
    data class Abandoned(val reason: AbandonReason) : RestoreResult
}

enum class AbandonReason {
    /** `process_death.<platform> = abandon`: nothing is restored (brief §5, iOS cold start). */
    PLATFORM_POLICY,

    /** H11: the snapshot was written by another spec. */
    SPEC_HASH_MISMATCH,
    SCHEMA_VERSION_MISMATCH,
    UNKNOWN_STATE,

    /** The engine already runs a flow; nothing was changed. */
    ALREADY_STARTED,
}

/** The serializable snapshot of boundary §1.4.2, stored by the host (Android `SavedStateHandle`). */
@Serializable
data class FlowSnapshot(
    @SerialName("bundle_schema_version")
    val bundleSchemaVersion: Int,
    @SerialName("spec_hash")
    val specHash: String,
    val state: String,
    val context: JsonObject,
    val history: List<String>,
    val gen: Long,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): FlowSnapshot = Json.decodeFromString(serializer(), json)
    }
}
