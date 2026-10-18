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

import eu.europa.ec.ux.runtime.bundle.BackPolicy
import eu.europa.ec.ux.runtime.bundle.EntryRequirement
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.InvokeScope
import eu.europa.ec.ux.runtime.bundle.LoadResult
import eu.europa.ec.ux.runtime.bundle.ProcessDeathPolicy
import eu.europa.ec.ux.runtime.bundle.RestorePolicy
import eu.europa.ec.ux.runtime.bundle.StateDef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Runs one flow of a loaded [FlowBundle] (SPIKE-PLAN §1.3). Pure Kotlin: no Compose, no app type, and no state,
 * screen, event or service name; every behaviour comes from the bundle.
 *
 * Async rules:
 * - each state-scoped `invoke` runs in a job of the state entry, cancelled when the state is left;
 * - every entry moves the generation token on; results and events carry the `gen` they were made for, and a mismatch
 *   is dropped and logged, so a late result never acts on a later state, even one with the same name;
 * - a state-changing event moves `gen` on, so a repeated tap stamped with the old `gen` is dropped: one `CONTINUE`,
 *   one `send`. Internal events (`target: null`) keep `gen` and may repeat.
 *
 * Confinement: call every method on the dispatcher of [scope] (the host's main dispatcher); results are delivered there.
 */
class FlowEngine(
    val bundle: FlowBundle,
    private val port: ServicePort,
    private val scope: CoroutineScope,
    private val log: FlowLog = FlowLog.NONE,
) {
    private val machine = FlowMachine(bundle, log)
    private val status = MutableStateFlow<FlowStatus>(FlowStatus.Idle)
    private var config: Config? = null
    private var launch: JsonObject = AssignInputs.EMPTY
    private var stateJob: Job? = null
    private var pending = false
    private var exited = false

    val current: StateFlow<FlowStatus> = status.asStateFlow()

    /** Starts at a named entry (`bundle.entries`), or at `initial` when the bundle has no entry of that name. */
    fun start(entry: String = DEFAULT_ENTRY, launch: JsonObject = AssignInputs.EMPTY, unlocked: Boolean = false): StartResult {
        if (config != null || exited) return StartResult.AlreadyStarted
        val def = bundle.entries[entry]
        if (def == null && entry != DEFAULT_ENTRY) return StartResult.UnknownEntry(entry)
        if (def != null && EntryRequirement.UNLOCKED in def.requires && !unlocked) {
            log.log("entry '$entry' refused: requires unlocked")
            return StartResult.Locked(entry)
        }
        this.launch = launch
        val ctx = FlowContext.assign(
            FlowContext.initial(bundle.context),
            def?.assign.orEmpty(),
            AssignInputs(launch = launch),
            bundle.context,
            log,
        )
        val target = def?.state ?: bundle.initial
        enter(Config(target, ctx, emptyList(), 0), target, ctx, errorClass = null)
        return StartResult.Started(target)
    }

    /** An event from the UI or the platform, stamped with the `gen` of the status it was made for. */
    fun dispatch(event: String, payload: JsonObject = AssignInputs.EMPTY, gen: Long): EventResult {
        val cfg = config ?: return EventResult.Dropped(DropReason.INACTIVE)
        if (gen != cfg.gen) return drop(DropReason.STALE_GENERATION, "$event (gen $gen, current ${cfg.gen})")
        val state = bundle.state(cfg.state)
        if (event !in state.on) return drop(DropReason.NOT_ACCEPTED, "$event in ${cfg.state}")
        if (pending && state.invoke?.acceptsWhilePending?.contains(event) == false) {
            return drop(DropReason.PENDING, "$event while ${state.invoke.service} is pending")
        }
        return fire(cfg, state, event, AssignInputs(event = payload, launch = launch))
    }

    /** Whether [dispatch] would accept the event now; the interpreter draws other nodes disabled (boundary §1.6). */
    fun isAccepted(event: String): Boolean {
        val cfg = config ?: return false
        val state = bundle.state(cfg.state)
        return event in state.on && !(pending && state.invoke?.acceptsWhilePending?.contains(event) == false)
    }

    /** Applies the current state's `back` policy (boundary §1.4.2). */
    fun back(): BackResult {
        val cfg = config ?: return BackResult.Inactive
        return when (val policy = bundle.state(cfg.state).back) {
            is BackPolicy.Event -> BackResult.Fired(policy.event, dispatch(policy.event, gen = cfg.gen))
            is BackPolicy.To -> BackResult.Moved(policy.target).also { enter(cfg, policy.target, cfg.context, null) }
            is BackPolicy.ToHistory -> {
                val target = machine.historyTarget(cfg.history, policy.candidates, policy.fallback)
                BackResult.Moved(target).also { enter(cfg, target, cfg.context, null) }
            }
            BackPolicy.Blocked -> BackResult.Blocked
            BackPolicy.Pass, null -> BackResult.Pass
        }
    }

    fun snapshot(): FlowSnapshot? = config?.let {
        FlowSnapshot(bundle.schemaVersion, bundle.specHash, it.state, it.context, it.history, it.gen)
    }

    /**
     * Restores after process death by the saved state's `restore` policy. A non-idempotent invoke is never re-run;
     * an idempotent one re-runs with a new `gen`. The platform's `process_death` policy comes first: `abandon`
     * ignores the snapshot. A snapshot of another spec or schema is discarded (H11). Either way the flow ends at
     * [FlowBundle.abandonState] without calling a service.
     */
    fun restore(snapshot: FlowSnapshot, platform: FlowPlatform): RestoreResult {
        if (config != null || exited) return RestoreResult.Abandoned(AbandonReason.ALREADY_STARTED)
        val policy = if (platform == FlowPlatform.IOS) bundle.processDeath.ios else bundle.processDeath.android
        if (policy == ProcessDeathPolicy.ABANDON) return abandon(AbandonReason.PLATFORM_POLICY, platform.name)
        if (snapshot.bundleSchemaVersion != bundle.schemaVersion) {
            return abandon(AbandonReason.SCHEMA_VERSION_MISMATCH, "${snapshot.bundleSchemaVersion}")
        }
        if (snapshot.specHash != bundle.specHash) return abandon(AbandonReason.SPEC_HASH_MISMATCH, snapshot.specHash)
        val state = bundle.states[snapshot.state]?.takeUnless { it.isFinal }
            ?: return abandon(AbandonReason.UNKNOWN_STATE, snapshot.state)
        val saved = Config(snapshot.state, snapshot.context, snapshot.history, snapshot.gen)
        val target = when (val restore = state.restore) {
            RestorePolicy.Snapshot, null -> null
            is RestorePolicy.To -> restore.target
            is RestorePolicy.ToHistory -> machine.historyTarget(saved.history, restore.candidates, restore.fallback)
        }
        log.log("restore ${snapshot.state} -> ${target ?: "snapshot"}")
        if (target != null) {
            enter(saved, target, saved.context, errorClass = null)
            return RestoreResult.Restored(target, reinvoked = false)
        }
        config = saved.copy(gen = saved.gen + 1)
        val reinvoked = startInvoke(state)
        publish()
        return RestoreResult.Restored(state.id, reinvoked)
    }

    private fun fire(cfg: Config, state: StateDef, event: String, inputs: AssignInputs): EventResult =
        when (val fired = machine.fire(state, cfg.context, event, inputs)) {
            Fired.NotAccepted -> drop(DropReason.NOT_ACCEPTED, "$event in ${cfg.state}")
            Fired.NoBranch -> drop(DropReason.NO_BRANCH, "$event in ${cfg.state}")
            is Fired.Internal -> {
                config = cfg.copy(context = fired.context)
                publish()
                EventResult.Internal
            }
            is Fired.Moved -> {
                enter(cfg, fired.target, fired.context, fired.errorClass)
                EventResult.Transitioned(fired.target)
            }
        }

    private fun enter(from: Config, target: String, ctx: JsonObject, errorClass: String?) {
        stateJob?.cancel()
        stateJob = null
        pending = false
        val next = machine.enter(from, target, ctx, errorClass)
        val state = bundle.state(target)
        log.log("enter $target (gen ${next.gen})")
        if (state.isFinal) {
            finish(state, next.context)
            return
        }
        config = next
        startInvoke(state)
        publish()
    }

    /** Returns whether a state-scoped invoke was started. */
    private fun startInvoke(state: StateDef): Boolean {
        val invoke = state.invoke ?: return false
        val cfg = config ?: return false
        val args = JsonObject(invoke.args.mapValues { (_, src) -> FlowContext.read(cfg.context, src, AssignInputs(launch = launch)) })
        if (invoke.scope == InvokeScope.DETACHED) {
            scope.launch {
                val result = callPort(invoke.service, args)
                log.log("detached ${invoke.service} finished (${result.type}); result ignored")
            }
            return false
        }
        val job = SupervisorJob(scope.coroutineContext[Job])
        stateJob = job
        pending = true
        val gen = cfg.gen
        scope.launch(job) {
            val result = callPort(invoke.service, args)
            onResult(state.id, gen, result)
        }
        return true
    }

    /**
     * RULING-2: a port that throws, or a coroutine failure other than this job's own cancellation, becomes the
     * [ServiceResult.UNEXPECTED_ERROR_TYPE] result with `{class: unexpected}`, so the state takes its declared error
     * branch (the loader refuses a state-scoped invoke without one). The job's own cancellation is rethrown.
     */
    private suspend fun callPort(service: String, args: JsonObject): ServiceResult = try {
        port.call(service, args)
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        unexpected(service, e)
    } catch (e: Exception) {
        unexpected(service, e)
    }

    private fun unexpected(service: String, e: Exception): ServiceResult {
        log.log("service $service failed (${e::class.simpleName}: ${e.message}); mapped to ${ServiceResult.UNEXPECTED_ERROR_TYPE}")
        return ServiceResult.unexpected()
    }

    private fun onResult(stateId: String, gen: Long, result: ServiceResult) {
        val cfg = config
        if (cfg == null || cfg.gen != gen || cfg.state != stateId) {
            log.log("late result ${result.type} for $stateId gen $gen dropped (current ${cfg?.state} gen ${cfg?.gen})")
            return
        }
        pending = false
        val state = bundle.state(stateId)
        val mapping = state.invoke?.on?.get(result.type)
        if (mapping == null) {
            log.log("contract error: result ${result.type} is not in $stateId invoke.on")
            publish()
            return
        }
        val inputs = AssignInputs(event = result.payload, result = result.payload, launch = launch)
        val ctx = FlowContext.assign(cfg.context, mapping.assign, inputs, bundle.context, log)
        val event = mapping.event
        if (event == null) {
            config = cfg.copy(context = ctx)
            publish()
            return
        }
        fire(cfg.copy(context = ctx), state, event, inputs)
    }

    private fun finish(state: StateDef, ctx: JsonObject) {
        val exit = state.exit ?: return
        config = null
        exited = true
        status.value = FlowStatus.Exited(
            FlowExit(state.id, exit.kind, exit.ref, exit.result, exit.carries.associateWith { FlowContext.get(ctx, it) }),
            ExitReason.FINAL_STATE,
        )
    }

    private fun abandon(reason: AbandonReason, detail: String): RestoreResult {
        log.log("snapshot not restored: $reason ($detail)")
        stateJob?.cancel()
        config = null
        exited = true
        val exit = bundle.abandonState?.let { id ->
            bundle.state(id).exit?.let { FlowExit(id, it.kind, it.ref, it.result, emptyMap()) }
        }
        status.value = FlowStatus.Exited(exit, ExitReason.ABANDONED)
        return RestoreResult.Abandoned(reason)
    }

    private fun publish() {
        val cfg = config ?: return
        val state = bundle.state(cfg.state)
        status.value = FlowStatus.Active(
            state = cfg.state,
            screen = state.screen,
            uiState = machine.uiState(state, cfg.context),
            context = cfg.context,
            gen = cfg.gen,
            pending = pending,
            backEnabled = state.back != null && state.back != BackPolicy.Pass,
        )
    }

    private fun drop(reason: DropReason, detail: String): EventResult {
        log.log("event dropped ($reason): $detail")
        return EventResult.Dropped(reason)
    }

    companion object {
        /** The entry name used when the host gives none (boundary §1.5). */
        const val DEFAULT_ENTRY: String = "default"

        /** Builds an engine only from a [LoadResult.Loaded]; a refused bundle builds none and calls no service. */
        fun from(result: LoadResult, port: ServicePort, scope: CoroutineScope, log: FlowLog = FlowLog.NONE): FlowEngine? =
            (result as? LoadResult.Loaded)?.let { FlowEngine(it.bundle, port, scope, log) }
    }
}
