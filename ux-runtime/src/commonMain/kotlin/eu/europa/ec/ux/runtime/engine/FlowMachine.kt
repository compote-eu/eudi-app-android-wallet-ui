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

import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.StateDef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where the flow is: the pure part of the engine's state, and what a [FlowSnapshot] stores. */
internal data class Config(val state: String, val context: JsonObject, val history: List<String>, val gen: Long)

internal sealed interface Fired {
    /** `target: null`: the state is kept, there is no exit or entry, and `gen` stays the same. */
    data class Internal(val context: JsonObject) : Fired
    data class Moved(val target: String, val context: JsonObject, val errorClass: String?) : Fired

    /** The state has the event, but no branch's guard held. */
    data object NoBranch : Fired
    data object NotAccepted : Fired
}

/**
 * The transition semantics of boundary §1.4.1, with no coroutines and no I/O, so the engine and the bounded model
 * check run the same code. Nothing here names a flow, a state or a screen.
 */
internal class FlowMachine(val bundle: FlowBundle, private val log: FlowLog) {

    /** Ordered branches, assign-then-guard: each branch's assigns go to a copy, and the guard is read on that copy. */
    fun fire(state: StateDef, ctx: JsonObject, event: String, inputs: AssignInputs): Fired {
        val branches = state.on[event] ?: return Fired.NotAccepted
        for (branch in branches) {
            val copy = FlowContext.assign(ctx, branch.assign, inputs, bundle.context, log)
            val guard = branch.guard
            if (guard == null || Conditions.eval(guard.condition, copy)) {
                val target = branch.target ?: return Fired.Internal(copy)
                return Fired.Moved(target, copy, branch.errorClass)
            }
        }
        return Fired.NoBranch
    }

    /** Exit then entry: `/flow/error_class` is set from the branch, history grows, and `gen` moves on. */
    fun enter(from: Config, target: String, ctx: JsonObject, errorClass: String?): Config {
        val withError = errorClass?.let { FlowContext.set(ctx, ERROR_CLASS_PATH, JsonPrimitive(it)) } ?: ctx
        return Config(target, withError, from.history + target, from.gen + 1)
    }

    /** The most recently visited candidate before the current state, else the fallback (`to_history`). */
    fun historyTarget(history: List<String>, candidates: List<String>, fallback: String): String =
        history.dropLast(1).lastOrNull { it in candidates } ?: fallback

    /** `ui_state_when`: the first match wins, else the static `ui_state`. */
    fun uiState(state: StateDef, ctx: JsonObject): String? =
        state.uiStateWhen.firstOrNull { Conditions.eval(it.condition, ctx) }?.uiState ?: state.uiState

    companion object {
        /** A flow-level path of bundle schema 1, set by the engine (boundary §1.3). */
        const val ERROR_CLASS_PATH: String = "/flow/error_class"
    }
}
