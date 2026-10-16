# Spike log: `spike/ux-runtime`

Base: `origin/feature/kmp` @ `b23f6a1f5`. Design: `SPIKE-PLAN.md`. Boundary: `spike-contract-boundary.md`. Drill
rows: `spike-results.md`. Owner steps: `HUMAN-QUEUE.md`.

## Milestones

| M | Status | Started | Finished | Agent (model) | Commits | Notes |
|---|---|---|---|---|---|---|
| M0 | in progress | 2026-10-16 | — | Haiku/Sonnet | — | Android SDK, Maestro, baseline gate (Android + lint green; iOS tests failed before Xcode). Xcode 27.1 now installed; iOS runtime and `M0-baseline-launch.png` in progress. See `spike-evidence/M0-baseline.md`. |
| M1a | done | 2026-10-16 | 2026-10-16 | Opus | see Commits | plan + state files; RULING-1 fold-in |
| M1b | done | 2026-10-16 | 2026-10-16 | Opus | see Commits | `spike-contract-boundary.md` + PROVISIONAL bundle (sha256 `31bfda65…cf8d71`) |
| M2 | done | 2026-10-16 | 2026-10-16 | Sonnet | see Commits | Six module skeletons, `DebugFlowHook`, conditional wiring; probes P1 (iOS, redirect option A works), P2, P3, P5 passed, P6 compile-level; release isolation clean; gate GREEN. ⟲: `origin/feature/kmp` still at `b23f6a1f5`, no rebase. T1-T4 touched only; reserved T6/T7/T8 unused. |
| M3 | done | 2026-10-16 | 2026-10-16 | Opus | see Commits | Pure-Kotlin engine + loader + handshake in `:ux-runtime` (`bundle`, `engine` packages; no Compose import, no bundle-name literal). 95 new tests (47 common ×2 targets + 1 host guard): H1-H12, async rules, brief §5 back/restore, iOS abandon, deeplink, exits, D5-engine. Drills D1-engine, D2-engine, D5-engine (preliminary). Only `ux-runtime/**` changed (+ these state files); no T-file touched. KC1 grep over `ux-runtime` = 0. |
| M4 | not started | — | — | — | — | — |
| M5 | not started | — | — | — | — | — |
| M5b | not started | — | — | — | — | — |
| M6 | not started | — | — | — | — | blocked on `ux-spec/`; ⟲ after sync |
| M7 | not started | — | — | — | — | ⟲ at end |
| M8 | not started | — | — | — | — | D8 baseline SHA: _to be named when cut_ |
| M9 | not started | — | — | — | — | D9 baseline SHA: _to be named when cut_ |
| M10 | not started | — | — | — | — | ⟲ at end |
| M11 | not started | — | — | — | — | ⟲ at start; needs emulator/device |
| M12 | not started | — | — | — | — | ⟲ final |

## Rulings

| Id | Date | Question | Minimal-footprint view | Faithful-to-brief view | Judgement | Touch list widened? |
|---|---|---|---|---|---|---|
| RULING-1 | 2026-10-16 | M1a review panel (brief-compliance + technical reviews, merged) on `SPIKE-PLAN.md` | technical review: hook renamed to `DebugFlowHook` (release grep with no exemption); `#if DEBUG` around the ConsentHost import; one `backHandler(` call; KMP skeleton details; ui-test without a catalog touch; QrScan redirect null for Issuance/Signature; F2 run-once guard; no `simctl defaults write`; Koin hook-absent/present test; iOS Gradle-graph + Android dev/demo classpath checks | brief-compliance review: verdict needs D4, D5, D6 on both platforms and D7-Android; KC3 per the brief (VoiceOver); every code milestone closes with a §7 drill (preliminary allowed, not counted); KC1 fails on any non-`ux-spec` D8 diff unless the owner ruled in advance, data-driven scripts; wider KC1 and D1 greps; pre-D8 baseline grep, brand-selection path; graph-only Android check = unresolved, APK required; extra ⟲ after M7 and before M11, named D8 baseline; rows split; D3 device queued; D10 not run = Incomplete; D9 common lines count per platform; `--diff-filter=MDRT`; ≤ 1 file per ruling | all 21 points accepted and folded in; M1b defaults adopted (boundary §7 Q1–Q17); refinements F1–F5 accepted | no (5 planned + 3 reserved, cap 8) |
| RULING-2 | 2026-10-16 | M3 finding 3: what does a service that throws mean? | — (orchestrator ruling, no panel) | — | A service that throws, or a coroutine failure other than cancellation, is converted into the `ServiceResult` type `Error` with payload `{class: unexpected}` and follows the bundle's declared error branch. If a state declares no error branch, the bundle is refused at load. Implemented in M4: `FlowEngine.callPort` (a `CancellationException` while the state job is still active counts as a failure); `BundleValidator.requireErrorBranch` requires, for every **state-scoped** invoke, an `Error` result on the service and an `invoke.on.Error` mapped to an event of the state's `on` (detached invokes are exempt: their results are ignored). `Error` becomes the single engine-convention name, exempted in `ProvisionalRuntimeLiteralGuardTest`. Tests: `ProvisionalRuling2Test` (9). Queued for week 0 / owner as Q-004 with this default. | no |

## Gate results

Counts from `*/build/test-results/**/*.xml`. A task with 0 executed tests where tests exist is red.

| Date | SHA | Milestone | Task | Executed | Failed | Skipped | detekt | ktlint | xcodebuild | Result |
|---|---|---|---|---|---|---|---|---|---|---|
| 2026-10-16 | b23f6a1f5 | M0 baseline | see `spike-evidence/M0-baseline.md` | 560 (shared-ui host), 166 (shared-logic host), Android unit tests per module | 0 | 0 | pass | pass | not run (no Xcode then) | Android/lint green; iOS tests pending |
| 2026-10-16 | final M2 commit | M2 | full `./spike-gate.sh` (retry 1) | 4004 (shared-logic iOS 832, shared-ui iOS 648, host 166/562, ux-spike 1+1, module unit tests as M0) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-16 | f31de1283 | M3 | `./spike-gate.sh --fast` | 4099 (ux-runtime host 48, iOS 47; rest as M2) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | f31de1283 | M3 | full `./spike-gate.sh` | 4099 (shared-logic iOS 832, shared-ui iOS 648, host 166/562, ux-runtime 48/47, ux-spike 1+1, module unit tests as M0) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

Gate try 1 was RED: my first `DebugFlowHookTest` used Koin DSL modules, which made the Koin compiler plugin validate the iOS test compilation and reject `IosFilterValidatorScopeTest` (KOIN-D002). The test now uses `Koin().declare`. No wallet code changed.

## M2 probes

| Probe | Outcome | Evidence |
|---|---|---|
| P1 redirect (option A, `SharedEntries.kt`) | passed on iOS: with the switch on, the `openid4vp://` link reaches the stub after the unlock gate; with it off the real request screen runs. Reserved T7 not used. `PresentationRequestViewModel` construction log not checked (the entry returns before `koinViewModel`). | `spike-evidence/M2/05-…`, `06-…` |
| P2 Koin pickup (option A) | passed: `@Single(binds=[DebugFlowHook::class]) IosUxSpikeHook` in `iosUxSpikeMain` is found by the component scan; no T6. Extension pickup not run (DC API cannot be driven on the simulator). | stub rendered |
| P3 compose resource from `:ux-contract` | passed in the app (stub read `ux-contract-resource-ok` at run time) and present in the `.appex` bundle (`compose-resources/…/eu.europa.ec.ux.contract.resources/files/ux-probe.txt`); not read at run time inside the extension. | `05-…`; `find` on the Debug `.app` |
| P5 `--ux-spike` read in Kotlin | passed: `--ux-spike happy` parsed in `IosUxSpikeSwitch`, persisted to the app-group defaults, survives a `simctl openurl` cold start; `off` clears it. Log: `UX-SPIKE: argument --ux-spike=happy (persisting)`. No T8. | launch log |
| P6 Android standalone `UxFlowRoute` restore | compile-level only: `@Serializable @SerialName("UxFlow")`, JSON round-trip test green on host and iOS. No emulator or adb on this machine; the Android hook, provider and Koin load were built (`:ux-spike-android:assembleDebug`, merged manifest has the provider in debug, not in release) but not run. | `UxFlowRouteTest` |

Note on P2 timing: the iOS hook parses the argument in its `init`, so persistence happens when the hook is first resolved (app start).

## M3 engine notes and findings

Public API (`eu.europa.ec.ux.runtime`): `FlowBundle.load(bytes, expectedFlowId, expectedSpecHash, themeSpecHash, allowProvisional) -> Loaded | Refused(reason)`;
`FlowEngine(bundle, ServicePort, scope, FlowLog)` with `start(entry, launch, unlocked)`, `dispatch(event, payload, gen)`, `isAccepted`, `back()`,
`snapshot()`, `restore(snapshot, platform)`, `current: StateFlow<FlowStatus>`; `ServicePort.call(service, args) -> ServiceResult(type, payload)`;
`ServicePort.fromJsonInvoker(PresCrossDeviceInvoker(services)::invoke)` is the sync-time seam for the generated typed `Services`.

The PROVISIONAL fixture is generated at build time (`:ux-runtime:provisionalBundleFixture`) from `spike-evidence/boundary/…PROVISIONAL.json`,
sha256-checked, into `ux-runtime/build/`; boundary §6.1 put a tracked Kotlin copy in commonTest, which would break the KC1 grep (it greps all of
`ux-runtime`). Tests read the screen id from the bundle. `ProvisionalRuntimeLiteralGuardTest` (host) fails if a main source names any state,
event, service, result or screen of the bundle. The repo-wide KC1 grep stays an M5b gate item (`spike-gate.sh` not touched in M3).

Findings for week 0 / the owner (not decided here):
1. **L58 rests on a service-contract assumption.** With `RequestReady` or `RpUntrusted` carrying only optional items, the fixed bundle still
   reaches `auth` with `selected_count == 0` (`fetchRequest->RequestReady, CONTINUE` and `…RpUntrusted, CONTINUE, APPROVE`): `request` and
   `request-untrusted` `CONTINUE` are unguarded. Either SVC guarantees ≥ 1 mandatory item for those results, or the guard moves there.
2. **H11 / iOS abandon target.** The boundary says "exits `closed`"; the engine finds it generically as the single final state with exit
   `{home, not-presented}`. Suggest the bundle names it (e.g. `process_death.abandon_to`).
3. **A throwing service** has no defined meaning; the engine logs it and does not move (the state stays pending-free with no transition).
   W0 to define (for example, map to the service's `Error` result).
4. Missing `back`/`restore` on a non-final state is refused (InvalidBundle), and `process_death` values, entry `requires` and exit `result`
   are treated as closed enums, beyond the boundary's §1.1 list. Restore targets with a non-idempotent state-scoped invoke are refused.
5. Unknown screen effects are recorded in `FlowBundle.warnings` (the M4 host logs them).

## M2 release-isolation check (baseline: `spike-evidence/M0-baseline.md`)

| Check | Result |
|---|---|
| iOS Gradle graph, `CONFIGURATION=Release :shared-ui:dependencies` (simulator and arm64 compile klibs) | 0 `ux-` lines; control without `CONFIGURATION` shows 7 |
| iOS Release `SharedKit`, app binary, `.appex` binary: `nm -gU` + `strings` grep of all spike patterns | 0 / 0 / 0. `nm -gU SharedKit` = 114167 (baseline 114164): +3 symbols are `DebugFlowHook`, which matches no spike pattern (F5) |
| iOS Release `.app` and `.appex` resources | no `ux-probe.txt`, no `eu.europa.ec.ux.contract.resources`; only `eu.europa.ec.shared.resources` |
| Android `devReleaseRuntimeClasspath`, `demoReleaseRuntimeClasspath` | 0 `ux-`/`uxspike`; debug classpaths list `:ux-spike-android` and `:ux-spike` |
| Android `assembleDevRelease` (throwaway keystore via `-Pandroid.injected.signing.*`) | built; `apkanalyzer dex packages` 0 spike packages, `files list` 0 spike files, manifest has no `ux-spike-init` provider. Control, the dev debug APK: 240 dex hits, 3 files, provider present. Demo release APK not built (classpath only). |

## Commits

| SHA | Date | Milestone | Subject | Co-Authored-By (model) | Gate |
|---|---|---|---|---|---|
| 1e4946b70 | 2026-10-16 | M2 | DebugFlowHook seam + SharedEntries calls | Claude Sonnet 5.5 | lint + shared-ui tests |
| d33a9c727 | 2026-10-16 | M2 | six module skeletons, stub | Claude Sonnet 5.5 | module tests |
| 965694770 | 2026-10-16 | M2 | iOS srcDir/dependency, Android debugImplementation | Claude Sonnet 5.5 | build |
| 320a10232 | 2026-10-16 | M2 | persist launch argument; Maestro flows, screenshots | Claude Sonnet 5.5 | Debug build |
| be838cca3 | 2026-10-16 | M2 | hook test without Koin DSL | Claude Sonnet 5.5 | full gate GREEN |
| (this commit) | 2026-10-16 | M2 | SPIKE-LOG, spike-results | Claude Sonnet 5.5 | — |
| f31de1283 | 2026-10-16 | M3 | engine, loader, handshake, tests | Claude Opus 5.5 | fast + full gate GREEN (4099) |
| (this commit) | 2026-10-16 | M3 | SPIKE-LOG, spike-results (D1/D2/D5-engine) | Claude Opus 5.5 | — |
