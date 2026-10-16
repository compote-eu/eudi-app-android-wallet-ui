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

/** The structural invariants of boundary §1.4-§1.6. A failure is `Refused(InvalidBundle(path))` (H10). */
internal class BundleValidator(private val b: FlowBundle) {

    fun validate() {
        requireState(b.initial, "/initial")
        b.entries.values.forEach { requireState(it.state, "/entries/${it.name}/state") }
        b.context.values.forEach { f ->
            f.derive?.let { if (it.list !in b.context) invalid("/context/${f.path}/derive/list", "unknown list '${it.list}'") }
        }
        b.states.values.forEach { validateState(it) }
    }

    private fun validateState(s: StateDef) {
        val p = "/states/${s.id}"
        if (s.isFinal) {
            if (s.exit == null) invalid(p, "a final state needs an exit")
            if (s.on.isNotEmpty() || s.invoke != null) invalid(p, "a final state has exit and nothing else")
            return
        }
        if (s.back == null) invalid("$p/back", "a non-final state needs a back policy")
        if (s.restore == null) invalid("$p/restore", "a non-final state needs a restore policy")
        s.on.forEach { (event, branches) -> validateBranches("$p/on/$event", event, branches) }
        s.invoke?.let { validateInvoke(s, it, "$p/invoke") }
        validateBack(s, "$p/back")
        validateRestore(s, "$p/restore")
    }

    private fun validateBranches(p: String, event: String, branches: List<Branch>) {
        if (event !in b.events) invalid(p, "event '$event' is not declared in events")
        if (branches.isEmpty()) invalid(p, "an empty branch list")
        branches.forEachIndexed { i, br ->
            if (br.guard == null && i != branches.lastIndex) invalid("$p/$i", "only the last branch may have no guard")
            br.target?.let { requireState(it, "$p/$i/target") }
        }
    }

    private fun validateInvoke(s: StateDef, inv: Invoke, p: String) {
        val svc = b.services[inv.service] ?: invalid("$p/service", "unknown service '${inv.service}'")
        if (inv.args.keys != svc.args.keys) invalid("$p/args", "args ${inv.args.keys} differ from the service's ${svc.args.keys}")
        (inv.on.keys - svc.results).firstOrNull()?.let { invalid("$p/on/$it", "'$it' is not a result of '${svc.name}'") }
        if (inv.scope == InvokeScope.STATE) {
            (svc.results - inv.on.keys).firstOrNull()?.let { invalid("$p/on", "result '$it' of '${svc.name}' is not mapped") }
        }
        inv.on.forEach { (result, m) ->
            m.event?.let { if (it !in s.on) invalid("$p/on/$result/event", "'$it' is not in the state's on") }
        }
        inv.acceptsWhilePending?.forEach { if (it !in s.on) invalid("$p/accepts_while_pending", "'$it' is not in the state's on") }
    }

    private fun validateBack(s: StateDef, p: String) {
        when (val back = s.back) {
            is BackPolicy.Event -> if (back.event !in s.on) invalid("$p/event", "'${back.event}' is not in the state's on")
            is BackPolicy.To -> requireState(back.target, "$p/target")
            is BackPolicy.ToHistory -> (back.candidates + back.fallback).forEach { requireState(it, p) }
            BackPolicy.Blocked, BackPolicy.Pass, null -> Unit
        }
    }

    private fun validateRestore(s: StateDef, p: String) {
        when (val restore = s.restore) {
            RestorePolicy.Snapshot -> if (hasNonIdempotentInvoke(s)) {
                invalid("$p/policy", "snapshot restore on a state whose invoke is not idempotent")
            }
            is RestorePolicy.To -> requireRestoreTarget(restore.target, "$p/target")
            is RestorePolicy.ToHistory -> (restore.candidates + restore.fallback).forEach { requireRestoreTarget(it, p) }
            null -> Unit
        }
    }

    /** Restoring never re-invokes a non-idempotent service (boundary §1.4.2), so a restore target cannot have one. */
    private fun requireRestoreTarget(id: String, p: String) {
        requireState(id, p)
        if (hasNonIdempotentInvoke(b.state(id))) invalid(p, "restore target '$id' re-invokes a non-idempotent service")
    }

    private fun hasNonIdempotentInvoke(s: StateDef): Boolean =
        s.invoke?.let { inv -> inv.scope == InvokeScope.STATE && b.services[inv.service]?.idempotent == false } ?: false

    private fun requireState(id: String, p: String) {
        if (id !in b.states) invalid(p, "unknown state '$id'")
    }

    private fun invalid(path: String, detail: String): Nothing = throw RefusedException(RefusalReason.InvalidBundle(path, detail))
}
