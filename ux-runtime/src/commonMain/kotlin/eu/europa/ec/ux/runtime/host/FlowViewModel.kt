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

package eu.europa.ec.ux.runtime.host

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.serialization.saved
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.engine.BackResult
import eu.europa.ec.ux.runtime.engine.EventResult
import eu.europa.ec.ux.runtime.engine.FlowEngine
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.engine.FlowSnapshot
import eu.europa.ec.ux.runtime.engine.FlowStatus
import eu.europa.ec.ux.runtime.engine.RestoreResult
import eu.europa.ec.ux.runtime.engine.ServicePort
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/**
 * Owns one [FlowEngine] for one Nav3 entry (SPIKE-PLAN §1.5). The engine runs on [viewModelScope], and every status
 * change writes the engine's [FlowSnapshot] as JSON into the entry's [SavedStateHandle] (lifecycle `saved {}` with
 * kotlinx-serialization; the snapshot itself holds JSON trees, so it is stored as its JSON text).
 *
 * A new view model with a saved snapshot restores by the bundle policy ([FlowEngine.restore], which applies the
 * platform's `process_death` first); without one it starts at [entry]. The host passes `unlocked = true`: the
 * wallet's unlock gate runs before the flow entry exists.
 */
class FlowViewModel(
    handle: SavedStateHandle,
    bundle: FlowBundle,
    port: ServicePort,
    entry: String,
    launch: JsonObject,
    platform: FlowPlatform,
    private val log: FlowLog = FlowLog.NONE,
) : ViewModel() {

    private var savedSnapshot: String? by handle.saved(String.serializer().nullable, SNAPSHOT_KEY) { null }

    private val engine = FlowEngine(bundle, port, viewModelScope, log)

    val status: StateFlow<FlowStatus> = engine.current

    /** Set when this view model was created from a saved snapshot. */
    val restored: RestoreResult?

    init {
        val snapshot = savedSnapshot?.let { json ->
            runCatching { FlowSnapshot.fromJson(json) }.onFailure { log.log("saved snapshot unreadable: ${it.message}") }.getOrNull()
        }
        restored = snapshot?.let { engine.restore(it, platform) }
        if (snapshot == null) log.log("start $entry: ${engine.start(entry, launch, unlocked = true)}") else log.log("restore: $restored")
        save()
        viewModelScope.launch { engine.current.collect { save() } }
    }

    fun dispatch(event: String, payload: JsonObject, gen: Long): EventResult = engine.dispatch(event, payload, gen)

    fun isAccepted(event: String): Boolean = engine.isAccepted(event)

    fun back(): BackResult = engine.back().also { log.log("back: $it") }

    private fun save() {
        savedSnapshot = engine.snapshot()?.toJson()
    }

    companion object {
        const val SNAPSHOT_KEY: String = "ux-flow-snapshot"

        /**
         * Builds the view model inside `viewModel { }`. Probe P4: the entry's owner must supply a [SavedStateHandle]
         * (`createSavedStateHandle` needs the saved-state owner in the creation extras). Without one the flow runs
         * on an unsaved handle and the log says so.
         */
        fun create(
            extras: CreationExtras,
            bundle: FlowBundle,
            port: ServicePort,
            entry: String,
            launch: JsonObject,
            platform: FlowPlatform,
            log: FlowLog,
        ): FlowViewModel {
            val handle = try {
                extras.createSavedStateHandle().also { log.log("P4 SavedStateHandle supplied by the entry owner ($platform)") }
            } catch (e: IllegalArgumentException) {
                log.log("P4 SavedStateHandle MISSING on $platform (${e.message}); the flow is not saved")
                SavedStateHandle()
            }
            return FlowViewModel(handle, bundle, port, entry, launch, platform, log)
        }
    }
}
