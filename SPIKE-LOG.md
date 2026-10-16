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
| M4 | done | 2026-10-16 | 2026-10-16 | Opus | see Commits | RULING-2 in the engine; `FlowHost` + `FlowViewModel` (`:ux-runtime` `host`), placeholder states; P4 passed on iOS (app, simulator) and Android (Robolectric round trip; app not run); D1 grep as a host test; PROVISIONAL contract copy + bundle resource in `:ux-contract`; data-driven fake engine (6 scripts) in `:ux-spike`; both hooks host the flow with one `PlatformBackHandler` adapter each. No existing file touched (only T1-T4 from M2 differ from the base). Drills: D3-sim-preliminary passed ×3, D-switch-1 passed, **D1-iOS-preliminary not run** (Maestro edge swipe triggers no back on any screen, baseline included); host-level FlowHost back test green. Gate GREEN 4148. |
| M5 | done | 2026-10-16 | 2026-10-16 | Sonnet | see Commits | `:ux-theme` (token value types, `UxBrand`, `UxTheme`, `LocalUxTokens`, PROVISIONAL eudiw_v1 set in `…theme.provisional`), `:ux-elements` (9 elements, 4 templates, heading focus API `headingFocus` / `LocalUxHeadingFocus`, `focusRequester` on interactive elements). 23 semantics tests per target (iOS sim + Android host under Robolectric). Debug-only template gallery behind `--ux-spike gallery[-page]` (UxFlowEntry, 4 lines). D7-semantics-preliminary recorded. No pre-spike file touched; only M2-M4 spike files (`ux-theme`/`ux-elements` build files, `UxFlowEntry.kt`). Gate GREEN 4196. |
| M5b | done | 2026-10-16 | 2026-10-16 | Opus | see Commits | Screen interpreter in `:ux-runtime` (`screen`, `a11y`), generic EL-*/TPL-* registry in `:ux-elements`, FlowHost draws every described screen; PROVISIONAL `PresRequestScreen` + registration in `:ux-spike` (the one KC1 exception); deferred exits; invoker `encodeDefaults`; KC1 guard step in `spike-gate.sh`. Tests (i) (ii) (iii) green. Drills D6-iOS-provisional (happy, expired), D8-added-state-preliminary, D1-iOS-preliminary rerun with the edge-swipe tool: all as brief §5. No pre-spike file touched. |
| M5c | done | 2026-10-16 | 2026-10-16 | Sonnet | see Commits | Styling hardening (OR-2): `style-guard` step in `spike-gate.sh` (13 source lines of hard-coded dp/alpha/FontWeight fixed, a planted literal turns it red); `wireEnum` and unknown variants, props, events, slots and regions fail loudly (tagged `ux-error.<id>` node); registry schemas tested against the library YAML snapshot (spec commit `ec5e58e3`); `UxTokens` shell grown (elevation drawn, fonts, `comp.*`, four modes); `spike-evidence/M5c/exporter-requirements.md`. No pre-spike file touched. Gate GREEN 4273. |
| M6 | not started | — | — | — | — | blocked on `ux-spec/`; ⟲ after sync |
| M7 | in progress (walk queued) | 2026-10-16 | — | Opus | see Commits | `AccessibilityBridge` expect/actual in `:ux-runtime` (iOS ScreenChanged on the h1 element + queued announcements; Android view announcements), settle-time focus/announce from the bundle a11y, leaving state cleared during the crossfade, TplSheet dialog semantics, list/group semantics, mandatory items not "dimmed", link label once; FlowHost-level D7 tests; scripted Inspector audit; Q-D7-iOS queued (device rebuild needed). No KC3 kill-signal. ⟲ at end not done (no rebase in this run). |
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

## Owner rulings (2026-10-16, given in chat)

| Id | Date | Ruling | Effect on the plan |
|---|---|---|---|
| OR-1 | 2026-10-16 | Home-screen probe: render only the dashboard Home screen from the bundle through the interpreter (no dashboard flow). A probe, not counted toward the verdict. After M7. | M5d (after M5c). |
| OR-2 | 2026-10-16 | Adopt the evaluator's recommendations 2 and 3: (a) gate guard: no hard-coded dp/sp/colour/alpha/FontWeight in `:ux-elements` (templates included); (b) `wireEnum` and unknown variants or props fail loudly, prop names checked against the library YAML; (c) extend the `UxTokens` shell: elevation, font family and weight, `comp.*` tokens, modes incl. HighContrast; the exporter (week-0 batch 3) must emit these. Dark mode, icons/assets and motion stay declared gaps (out of brief scope). | M5c. |
| OR-3 | 2026-10-16 | Q-002/KC1: the fake engine is data-driven and does not count. A compile break in the app's real `Services` adapter counts as a reported cost (not a screen-code edit); record it per D8 row. | D8 rows carry a "real adapter compile break" cost field. |
| OR-4 | 2026-10-16 | Android (M11) is deferred to the end of the spike, after everything is confirmed on iPhone. | M11 runs last of the drills. |
| OR-5 | 2026-10-16 | Real adapter probe at the end (after M11, before M12): one real `Services` adapter binds this flow's request step (and, as far as cheap, auth and send) to the app's real presentation interactors behind the debug switch; measure cost and the typed-error gaps (untyped `Failure` to `Expired` etc.). Not counted toward the verdict; feeds the estimates. | M11b. |
| OR-6 | 2026-10-16 | Phase metrics for post-spike estimation: every milestone records a metrics row (phase, agent wall time, hand-written LOC main + test per module, generated LOC, files touched, gate runs, rework loops and cause). Backfill M2 to M5b from git history. M12 adds a per-phase cost model for a new flow (input to the post-spike challenge of estimates and of generalisation ideas; the challenge itself happens after the spike). | "Phase metrics" table below; M12 cost model. |

Open: the owner's review of SPIKE-PLAN §2 (the touch list, brief §6.4) has not been given yet.

## Gate results

Counts from `*/build/test-results/**/*.xml`. A task with 0 executed tests where tests exist is red.

| Date | SHA | Milestone | Task | Executed | Failed | Skipped | detekt | ktlint | xcodebuild | Result |
|---|---|---|---|---|---|---|---|---|---|---|
| 2026-10-16 | b23f6a1f5 | M0 baseline | see `spike-evidence/M0-baseline.md` | 560 (shared-ui host), 166 (shared-logic host), Android unit tests per module | 0 | 0 | pass | pass | not run (no Xcode then) | Android/lint green; iOS tests pending |
| 2026-10-16 | final M2 commit | M2 | full `./spike-gate.sh` (retry 1) | 4004 (shared-logic iOS 832, shared-ui iOS 648, host 166/562, ux-spike 1+1, module unit tests as M0) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-16 | f31de1283 | M3 | `./spike-gate.sh --fast` | 4099 (ux-runtime host 48, iOS 47; rest as M2) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | f31de1283 | M3 | full `./spike-gate.sh` | 4099 (shared-logic iOS 832, shared-ui iOS 648, host 166/562, ux-runtime 48/47, ux-spike 1+1, module unit tests as M0) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

| 2026-10-16 | 700787c3c | M4 | `./spike-gate.sh --fast` (RULING-2) | 4117 (ux-runtime host 57, iOS 56; rest as M3) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | b754b7543 | M4 | `./spike-gate.sh --fast` (host, fake engine) | 4145 (ux-runtime 66/61, ux-spike 11/3, ux-contract 2; rest as M3) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | b754b7543 + FlowHost back test (this commit) | M4 | full `./spike-gate.sh` | 4148 (shared-logic iOS 832, shared-ui iOS 648, host 166/562, ux-runtime 66/64, ux-spike 11/3, ux-contract 2, module unit tests as M0) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

| 2026-10-16 | f34c35dc0 | M5b | `./spike-gate.sh --fast` (+ kc1-guard) | 4220 (ux-runtime 76/74, ux-contract 4/2, ux-elements 23/23, ux-spike 12/4; rest as M4) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | f34c35dc0 + drill evidence | M5b | full `./spike-gate.sh` (+ kc1-guard) | 4220 (as the fast run) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

| 2026-10-16 | adb3e33b1 | M7 | `./spike-gate.sh --fast` (+ kc1-guard) | 4234 (ux-runtime 81/83, ux-elements 23/23; rest as M5b) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | adb3e33b1 + link label (this commit) | M7 | full `./spike-gate.sh` (+ kc1-guard) | 4234 (as the fast run) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

| 2026-10-16 | f1eac295f + fc32e0b72 (working tree, same content) | M5c | `./spike-gate.sh --fast` (+ kc1-guard, style-guard) | 4273 (ux-elements 31/32, ux-runtime 86/88, ux-theme 6/6, +39 over M7; rest as M7) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | fc32e0b72 + this commit (docs) | M5c | full `./spike-gate.sh` (+ kc1-guard, style-guard) | 4273 (as the fast run) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

M5c Release spot check: `strings` on the Release app binary finds 0 spike patterns (`ProvisionalEudiwV1`, `SchemaCheck`, `UxElementRegistry`, `ux-error`, `UxTokens`, `IosAccessibilityBridge`, `PresRequestScreen`, `UX-SPIKE`).

M7 Release spot check: `strings` on the Release app binary finds 0 spike or bridge patterns (`IosAccessibilityBridge`, `UIKitHeadingFinder`, `StateChangeA11y`, `UX-SPIKE`, `PresRequestScreen`).

M5b Release spot check: `strings` on the Release app and extension binaries finds 0 spike patterns (incl. `PresRequestScreen`).

M4 Release spot check (full gate's Release build): `strings` on the app binary finds 0 spike patterns; the `.app` and `.appex` carry only `eu.europa.ec.shared.resources`.

Gate try 1 was RED: my first `DebugFlowHookTest` used Koin DSL modules, which made the Koin compiler plugin validate the iOS test compilation and reject `IosFilterValidatorScopeTest` (KOIN-D002). The test now uses `Koin().declare`. No wallet code changed.

## Phase metrics (OR-6)

How to read it. Source: `git log --numstat` and commit timestamps on `spike/ux-runtime`, backfilled for M2, M3, M4, M5, M5b
and M7 (M5c onward: recorded by the milestone itself).
- **Agent wall time** is the span from the first to the last commit timestamp of the milestone: approximate and a lower
  bound (work before the first commit is invisible; the M2 code commits share one timestamp because they were
  re-committed together). The bracket is the time since the previous milestone's last commit, an upper bound that
  includes owner waits.
- **LOC** are lines added (`--numstat`, `.kt`/`.kts`/`.sh`/`.swift` files; the PROVISIONAL bundle JSON, Maestro YAML and
  evidence files are not counted), split main / test per module. Test = a `*Test` source set. All hand-written; **generated
  LOC is 0** so far (the exporter does not exist yet; the fixture task in `:ux-runtime` emits a test-only Kotlin string
  that is not counted).
- **Gate runs** are the rows of "Gate results" and the commit table; **rework** is a loop that redid finished work, with its cause.

| Milestone | Phase category | Agent wall time | Hand-written LOC main / test, per module | Generated LOC | Existing files touched | Gate runs | Rework loops and cause |
|---|---|---|---|---|---|---|---|
| M2 | runtime (skeletons, hook, wiring) | 34 min (since M1: 1 h 35 min incl. wiring) | shared-ui hook 172/70; ux-spike 150/31; ux-spike-android 177/0; ux-contract 85/0; ux-elements 72/0; ux-runtime 72/0; ux-theme 71/0; edits in shared-ui 5, androidApp 2, root 6 | 0 | 4 (T1-T4: `settings.gradle.kts`, `shared-ui/build.gradle.kts`, `SharedEntries.kt`, `androidApp/build.gradle.kts`) | 2 (full try 1 RED, retry GREEN) | 1: the hook test used Koin DSL modules, so the Koin compiler plugin validated the iOS tests and rejected `IosFilterValidatorScopeTest` (KOIN-D002); rewritten with `Koin().declare` |
| M3 | runtime (engine, loader, handshake) | 9 min (since M2: 18 min) | ux-runtime 1551/1339 | 0 | 0 | 2 (fast, full; 4099) | 0 within M3; its finding (a throwing service) caused RULING-2 and the M4 rework below |
| M4 | runtime (host, FlowViewModel, fake engine, contract copy) | 30 min (since M3: 1 h 12 min) | ux-runtime 359/615; ux-contract 407/53; ux-spike 263/229; shared-ui hook 22/0; ux-spike-android 22/0 | 0 | 0 | 3 (fast 4117, fast 4145, full 4148) | 1: RULING-2 (throwing service to Error branch, bundle refused without an error branch), found in M3 and done as the first M4 commit; D1-iOS-preliminary could not run (Maestro edge swipe triggers no back) and was redone in M5b with a dedicated tool |
| M5 | elements + templates (theme shell, 9 elements, 4 templates, gallery) | 11 min (since M4: 24 min) | ux-elements 1486/596; ux-theme 329/15; ux-spike 188/33 | 0 | 0 | 2 (fast, full; 4196) | 1: template action buttons were intrinsic width, fixed in a second commit (`LocalUxFullWidthActions`) |
| M5b | runtime (interpreter, registry) + hand-written screens (`PresRequestScreen`) | 45 min (since M5: 1 h 3 min) | ux-runtime 806/467; ux-elements 290/0; ux-spike 256/0 (the consent screen); ux-contract 7/77; shared-ui hook 25/0; ux-spike-android 25/0; root 21 (gate) | 0 | 0 | 2 (fast 4220, full 4220) | 1: the QR redirect passed the state name `scan` as the entry name (unknown since M4) and the gallery keys spelled it; the M4 back test pressed placeholder buttons and was re-pointed to node ids; `ui-test` moved to `:ux-runtime` commonTest |
| M7 | drills + a11y (bridge, settle-time focus, audit) | 13 min (since M5b: 44 min incl. owner device runs) | ux-runtime 284/440; ux-elements 38/31 | 0 | 0 | 2 (fast 4234, full 4234) | 1: `EL-link` text repeated its content description (audit tree showed the name twice), fixed in the second commit |
| M5c | elements+templates (token shell, hardening) + runtime (schema check) | about 1 h 10 min, first (`87d397d22`, rulings and plan) to last (this commit) commit timestamp, approximate; the code commits are 23:49, the rest is gate waiting and documentation (since M7: 1 h 25 min) | ux-theme 518/101 (382 of the main lines are the four-mode PROVISIONAL value transcription); ux-elements 251/344; ux-runtime 106/95; root 57 (`spike-gate.sh`) | 0 | 0 | 3 (fast 4273 on the working tree before the commits, the `--style-guard` mutation run, full 4273) | 0 redone work; one re-scope inside the milestone: the registry-vs-YAML comparison first read `EL-attribute-item` as a registry element and was re-scoped to the record fields of `EL-attribute-list.items` |

Reading the table for a new flow (input to the M12 cost model): with no generator, every phase is hand-written; the
runtime phases (M2 to M4, M5b interpreter) hold about 4.3 k main lines and 2.9 k test lines across six modules, the elements
and templates about 1.5 k main and 0.6 k test. Rework is small and mostly integration (a gate-plugin interaction, an
entry-name convention, a missing width rule), never an engine redesign.

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

## M4 notes and findings

**P4 (SavedStateHandle from the Nav3 entry).**

| Platform | Outcome | Evidence |
|---|---|---|
| iOS (app, simulator) | **passed**: `rememberViewModelStoreNavEntryDecorator` + `rememberSaveableStateHolderNavEntryDecorator` (`AppNavDisplay.kt`) supply the saved-state owner, so `createSavedStateHandle()` works in `viewModel { }`. Log on every flow start: `P4 SavedStateHandle supplied by the entry owner (IOS)`. Across process death nothing is saved anyway (no back stack, `process_death.ios = abandon`), which D3 shows. | `spike-evidence/M4/logs/d3-*.log` |
| iOS (unit) | round trip green: `saved {}` → `savedStateProvider().saveState()` → `SavedStateHandle(map)` → new `FlowViewModel` with a new port | `ProvisionalFlowViewModelSavedStateIosTest` (5) |
| Android (unit) | round trip green under Robolectric (SDK 37): the provider's `Bundle` → `SavedStateHandle.createHandle` → new view model: request = snapshot (0 calls), auth = request (0 `authenticate`), sending = error (0 `send`), iOS platform = abandoned | `ProvisionalFlowViewModelSavedStateAndroidTest` (5) |
| Android (app) | not run: no emulator (M11). The decorator code is the same `AppNavDisplay` path; the R14 fallback is not needed so far. | — |

`saved {}` stores the snapshot's JSON text (`String?`), because `FlowSnapshot` holds `JsonObject` trees, whose serializer needs a JSON encoder, not the saved-state encoder. If an owner supplies no handle, `FlowViewModel.create` logs `P4 … MISSING` and runs on an unsaved handle.

**D1 grep.** `D1BackHandlerGrepTest` (host) implements the SPIKE-PLAN §3 checks: one `backHandler(` call in `ux-runtime` (FlowHost.kt:64), one `PlatformBackHandler(` adapter in each hook, nothing else matching `BackHandler\(|NavigationEventHandler|NavigationBackHandler|onBack *=` in `ux-*` or `iosUxSpikeMain`, and no back handling in `redirectedByDebugHook`. Note: the plan's literal `git grep -n -E '\bbackHandler\('` matches nothing on macOS (POSIX ERE has no `\b`); `git grep -n -P` gives the one line.

Findings for week 0 / the owner (not decided here):
1. **D1-iOS cannot be driven by Maestro 2.11 on this simulator.** No synthesized swipe (four variants) triggers back, on the flow or on the wallet's own pushed screens. D1-iOS (M6) needs a manual swipe in Simulator.app, a device, or another driver (e.g. idb). Queued as part of Q-006.
2. **The boundary §2.5 invoker drops default fields** (`Json { classDiscriminator = "type" }` without `encodeDefaults`): `selected: false`, `value: null` and `privacy_url: null` are not sent to the engine. The engine tolerates it (a missing `selected` reads as false), but the exporter should decide (M6 API diff).
3. **The theme spec hash** has no generated source yet; the spike passes the contract `SPEC_HASH` as `themeSpecHash` until M6.
4. **Script `expired-at-request`** is defined as `fetchRequest → Expired` (connecting → error-expired), since the `request` state has no invoke that could expire. Week 0 owns the real scripts (Q14).
5. Exits: every final state and an abandon return to Home (`popUpTo(DashboardRoute)`). Mapping `add-document`, `device-settings` and `to-help` (SPIKE-PLAN §1.5) is left for M5b, and the gap is logged by the hook.
6. The placeholder lists every `on` event as a button; events with a payload (`TOGGLE_ITEM`, `SYS_QR_SCANNED`) are disabled, so the `scan` entry cannot advance until M5b.

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
| 700787c3c | 2026-10-16 | M4 | RULING-2: throwing service → Error branch; load refusal | Claude Opus 5.5 | fast GREEN (4117) |
| b754b7543 | 2026-10-16 | M4 | FlowHost, FlowViewModel, fake engine, provisional contract, hooks | Claude Opus 5.5 | fast GREEN (4145) |
| (this commit) | 2026-10-16 | M4 | FlowHost back test (iOS), Maestro flows, evidence, SPIKE-LOG, spike-results, HUMAN-QUEUE | Claude Opus 5.5 | full GREEN (4148) |
| 85b1e98f0 | 2026-10-16 | M5 | tokens, 9 elements, 4 templates, semantics tests, gallery | Claude Sonnet 5.5 | fast GREEN (4196) |
| 55d04b1e9 | 2026-10-16 | M5 | full-width template actions, gallery Maestro flow | Claude Sonnet 5.5 | full GREEN (4196) |
| (this commit) | 2026-10-16 | M5 | SPIKE-LOG, spike-results, M5 evidence | Claude Sonnet 5.5 | — |
| d1ec06cf5 | 2026-10-16 | M5b | iOS edge-swipe tool + evidence (was untracked) | Claude Opus 5.5 | — |
| f34c35dc0 | 2026-10-16 | M5b | interpreter, registry, PresRequestScreen, exits, invoker defaults, KC1 guard, tests | Claude Opus 5.5 | fast GREEN (4220) |
| (this commit) | 2026-10-16 | M5b | Maestro flows, drill evidence, SPIKE-LOG, spike-results | Claude Opus 5.5 | full gate (see Gate results) |
| c41662455 | 2026-10-16 | device | owner device runs: D1/D4/D6-iOS-device rows, F-dev-1, checklists | Claude Opus 5.5 | docs only |
| adb3e33b1 | 2026-10-16 | M7 | AccessibilityBridge expect/actual, settle-time focus/announce, cleared leaving state, element fixes, FlowHost-level D7 tests, Q-D7-iOS | Claude Opus 5.5 | fast GREEN (4234) |
| (this commit) | 2026-10-16 | M7 | link label once, audit evidence, SPIKE-LOG, spike-results | Claude Opus 5.5 | full gate (see Gate results) |
| 87d397d22 | 2026-10-16 | M5c | OR-1 to OR-6, plan M5c/M5d/M11b, phase metrics backfill | Claude Sonnet 5.5 | docs only |
| f1eac295f | 2026-10-16 | M5c | token shell (elevation, fonts, comp.*, four modes), style-guard step, element fixes | Claude Sonnet 5.5 | fast GREEN (4273, with the next commit) |
| fc32e0b72 | 2026-10-16 | M5c | fail-loud schemas, tagged error node, registry-vs-YAML test, snapshot, exporter requirements | Claude Sonnet 5.5 | fast GREEN (4273) |
| (this commit) | 2026-10-16 | M5c | SPIKE-LOG M5c, metrics row, gate rows | Claude Sonnet 5.5 | full gate (see Gate results) |

## M5 notes (elements and templates)

Deviations from the spec definitions (all in `:ux-elements`, none needs a decision now):
1. **Heading level.** Compose has one `heading()` flag; h1 versus h2 is not expressible. `EL-text` title and heading, and the `EL-status-hero` title, set it.
2. **`EL-status-hero` pending** keeps the title a heading inside the polite live region (the spec's HTML does the same); the boundary's node `a11y.heading` omits pending. Runtime decides focus, not the element.
3. **No link role in Compose.** `EL-link` is a click target named by label plus `external_label`.
4. **Icons and illustrations are text glyphs** (no brand asset table yet); `art` is accepted and ignored. The webfont is not bundled.
5. **Extra prop `popup_state_label`** on `EL-button` (the spec's `popup` needs a spoken state text). `EL-attribute-list` items take resolved label text, not `label_copy`; the copy lookup is the interpreter's (M5b).
6. **Inert appbar** is `clearAndSetSemantics` plus a tap-taking scrim; Compose has no `inert`/`aria-hidden`. The dialog is a traversal group with `paneTitle` = the h1 text (`dialogLabel`), not a true modal role.
7. **Registry** keyed by `EL-*`/`TPL-*` id (SPIKE-PLAN §1.1) is not built here; the interpreter owns it in M5b. Elements take typed plain props only.
8. `EL-progress-steps` (TPL-progress `steps` region) is a plain slot, since it is outside the nine.
9. `ui-test-manifest:1.12.1` is pinned by coordinate for the Android host (registers `ComponentActivity` for `runComposeUiTest`); no catalog touch. Tests use `androidx.compose.ui.test.v2.runComposeUiTest` (the v1 one is deprecated in 1.12).
10. Templates provide the action region full-width through `LocalUxFullWidthActions`; bare buttons are intrinsic width.

`TPL-sheet` layouts: one `layout: UxDecisionSteps` parameter (default = the brand's `layouts`), all difference inside `TplSheet`. Gallery layout has a doubled top inset under the picker; cosmetic, debug only.

## M5b notes (interpreter)

Design: `ScreenCatalog` reads the node trees, `declared_by` and the theme reference from `bundle.raw`; per status a
`ScreenFrame` resolves props (`{copy,args}` through `CopyResolver`: brand override first, then base, `%n$s`;
`{bind}` from the context; literals), applies the screen rules (`hide`/`show`/`enable`/`disable`/`set_copy`; later
wins), and marks a node disabled when a rule disables it or the state does not accept one of its events now. The
template and element adapters come from `UxElementRegistry` (`:ux-elements`, keyed only by `TPL-*`/`EL-*`); an unknown
part draws a tagged placeholder. An element raises its own event (`press`, `toggle` + `{item_id}`), the node's `emits`
goes to `FlowEngine.dispatch` with the status `gen`. A hook-supplied `FlowScreens.custom` map (screen id →
composable) overrides a screen; its `ScreenScope` carries the consent snapshot (context paths whose `declared_by`
names the screen), copy, `isAccepted` and `send` (intent only). After each state entry settles (AnimatedContent
`Visible`, plus one frame), `StateChangeA11y` focuses the `a11y.heading` node or leaves the live region and calls
`AccessibilityBridge` (interface; M7 adds the iOS notification). FlowHost wraps each state in `ux-state-<id>`.

Tests: (i) `ProvisionalInterpreterScenario` (iOS sim + Robolectric): connecting and outcome-expired drawn from bundle
data only (every visible node id, every copy text, live region / heading focus from the bundle a11y, no placeholder),
plus rules/bindings (verification-code, CONFIRM disabled while pending), hand-written screen snapshot + intent,
unknown element, registry coverage of all 16 screens; `ProvisionalCopyResolverTest`; (ii) the added-state test on the
offline-loading TEST copy (build-time fixture `offlineLoadingBundleFixture`, sha-checked); (iii) `kc1-guard` gate step:
the plan grep (`SCR-|"pres-`, untracked files included) plus every bundle state id as a quoted literal in the main
sources of `ux-*` (contract excluded), `iosUxSpikeMain` and `DebugFlowHook.kt`; it requires the one exception to exist.
Mutation check: a planted `"sending"` in `:ux-elements` and `SCR-` in a runtime test both turned it red.

Changes beyond the interpreter: the QR redirect passed the state name `scan` as the entry name (an unknown entry since
M4; now `FlowEngine.DEFAULT_ENTRY`); the gallery page keys no longer spell `"scan"`; the M4 FlowHost back test presses
node ids instead of placeholder buttons; `ui-test` moved to `:ux-runtime` commonTest.

Exits (hooks, typed through `PresCrossDeviceExit.of`): done/closed → Home; add-document → Home then
`AddDocumentRoute(ExtraDocument)`; device-settings → Home then `PlatformScreenActions.openAppSettings()` (no
security-settings action exists, nor an iOS link); to-help → Home and `GAP to-help` logged (no help route in the app).

Findings for week 0 / the owner (not decided here):
1. **`declared_by` became semantic** for hand-written screens (the consent snapshot is built from it). Week 0 should
   either bless it or emit an explicit `snapshot` path list per custom screen.
2. **`/links/help` has no source.** Nothing assigns it, and EL-link draws nothing without an href, so the HELP link of
   the six outcome screens never shows and `to-help` is reachable only by Back policy, not by the user.
3. **`connecting` and `auth` can be shorter than a frame** with immediate fakes; the screen is then never composed (the
   engine state still runs). Not a runtime defect; drills that must see `auth` use `slow`.
4. **iOS focus timing.** `requestFocus` right at the AnimatedContent settle is accepted but not applied on iOS; one frame
   later it is. Kept generic in `FlowHost` (M7 re-checks with VoiceOver).
5. **D8 on the device needs the contract to grow.** The fake decodes results with the sealed contract serializers, so a
   new result (`Offline`) cannot be played from a script alone (R11); test (ii) runs on a JSON port.
6. The interpreter log line is written at state entry, so an internal `ui_state_when` change (verification-code
   `no-url` → default after `Ready`) is drawn but not re-logged.
7. Boundary §2.5 sample amended (`encodeDefaults = true`); the generated invoker must pass `ProvisionalInvokerDefaultsTest`.

## M7 notes (accessibility)

**Bridge per platform** (`ux-runtime/src/*/a11y/AccessibilityBridge*.kt`; `FlowScreens.accessibility` overrides it, null
= the platform's):
- **When.** FlowHost waits for the AnimatedContent transition to settle, plus one frame, then `StateChangeA11y` reads
  the screen's bundle `a11y`: `focus-heading` → `requestFocus` on the h1 target, then `bridge.screenChanged(screen,
  h1 node, h1 text, dialog)`; `announce-live` → `bridge.announce(screen, live node, text)`. Texts are the node's
  `{copy}` props (after `set_copy`), so the runtime names no screen. One call per state entry (keyed on `gen`).
- **Crossfade.** The leaving state's content is wrapped in `clearAndSetSemantics {}` as soon as the flow moved on, so
  during the fade a screen reader sees one h1 and at most one live region, and no focus can stay on an old node.
- **iOS.** `ScreenChanged` with the h1's UIKit element. CMP 1.12 has no public handle from a semantics node to its
  element, so the actual walks the key window's accessibility tree for the element whose `accessibilityIdentifier` is
  the h1 node's test tag (or `<node>.<part>`) and that has the Header trait, for up to 30 frames while VoiceOver runs.
  CMP itself posts a focus notification when the focused node changes (seen in the klib: `Focused` →
  `UIAccessibilityScreenChangedNotification`), so the bridge waits 3 frames and posts nothing if VoiceOver is already
  on the element (no double speech); no element → it posts the heading text. Progress steps: one
  `UIAccessibilityAnnouncementNotification` with an `NSAttributedString` carrying
  `UIAccessibilitySpeechAttributeQueueAnnouncement = YES`.
- **Android.** `View.announceForAccessibility` (deprecated from API 36, kept with a suppression) for progress steps and
  for the h1 of non-dialog screens; TPL-sheet dialogs are announced by their pane title. TalkBack is M11 (D7-Android).

**Elements.** TplSheet dialog node has `dialog()` semantics + traversal group + pane title. Attribute groups are
traversal groups with `CollectionInfo`, rows carry `CollectionItemInfo`. Mandatory rows are a checked checkbox with no
toggle action instead of a disabled toggle (audit: `Selected`, no `NotEnabled`, so VoiceOver does not say "dimmed").
`EL-link` text no longer repeats its content description (the audit tree showed the name twice).

**Tests.** The same-node state-change tests of M5 (sheet, outcome, scan) were removed; `ProvisionalStateChangeA11yScenario`
(iOS sim + Robolectric, 3 tests) drives real transitions through FlowHost with a recording bridge; `IosAccessibilityBridgeTest`
(6, iOS) checks the actual's posts with UIKit replaced by recorders and once inside FlowHost; `AndroidAccessibilityBridgeTest`
(2). Template tests keep static semantics only (dialog, live region, focus target).

**Audit.** `spike-evidence/tools/ios-a11y-audit.sh` runs `XCUIApplication.performAccessibilityAudit(for: .all)` (the
Inspector's audit) from a standalone XCUITest runner on the running app. Results in `spike-evidence/M7/`: request 1
(trust badge hit area), verification-code 1 (URL label not human-readable, by design), sending 0, success 0.

**Remaining gaps (not fixable in the runtime/elements with CMP 1.12.2):**
1. **No `accessibilityViewIsModal`** for TPL-sheet on iOS: modality rests on the cleared backdrop (app bar and scrim are
   not in the tree), so VoiceOver cannot reach them, but there is no modal container and no escape semantics of its own.
2. **No link trait.** CMP iOS maps `OnClick` to Button and has no `UIAccessibilityTraitLink` (klib trait list); `EL-link`
   is read as a button. Android: no link role either.
3. **Heading levels** (h1/h2) are not expressible (one Header trait).
4. **The h1 is `focusable()`** to take the runtime focus, which adds a keyboard tab stop (review finding 3).
5. **Single speech on the device is unproven**: the identifier lookup and the "already focused" check depend on CMP's
   lazy accessibility sync while VoiceOver runs, which no simulator test exercises. Q-D7-iOS decides it.
6. Android `announceForAccessibility` is deprecated; TalkBack following input focus may double-speak (M11).

**KC3.** No kill-signal: every fix sits in `:ux-runtime` or `:ux-elements`, nothing screen-specific (kc1-guard green).
The iOS element lookup is a generic workaround for a missing CMP API, inside the runtime actual. The verdict waits for
the owner's walk.

## M5c notes (styling hardening, OR-2)

**Style guard** (`spike-gate.sh` step `style-guard`, also `./spike-gate.sh --style-guard` for the two guards alone). Scans
`ux-elements/src/*Main/**/*.kt` (untracked files too; comments and imports skipped) for: a `<n>.dp` or `<n>.sp` literal,
`Dp(`/`TextUnit(`, `Color(`, a named `Color.X` (except `Transparent`/`Unspecified`), a numeric alpha, any `FontWeight`, and
named numeric constants (`private const val X = 0.4f`). Exceptions only through `ux-elements/style-guard.allowlist`
(`path|line|reason`; a stale entry fails; empty now). Not covered, by design: layout ratios inside draw code (`/ 2f` to
centre, `1f` direction signs) and `weight(1f)`; those are geometry, not styling values.
- Hits on the M5b tree: 14 findings on 13 source lines (`spike-evidence/M5c/logs/style-guard-proof.log`): `UxAppBar` badge
  offset 10.dp and size 8.dp, `UxButton` 0.dp, DISABLED_ALPHA 0.4 and `1f`, `UxLink` 4.dp, `UxQrViewfinder` DIMMED 0.35,
  ARM_DIVISOR 5 and `1f`, `TplScan` 240.dp, `TplSheet` SCRIM_ALPHA 0.4, FontWeight in `UxRpIdentity`, `UxNotice`,
  `UxAttributeList`. All moved to tokens: `comp.app-bar.badge-*`, `comp.link.gap`, `UxEffects.{disabled,scrim,dimmed}Alpha`,
  `UxScanTokens.cameraMinHeight`, `UxQrViewfinderTokens`, `comp.rp-identity.name-weight`, `sys.font.weight.semibold`,
  `comp.attribute-list.value-weight`. The `size.focusRing / 3` hairline trick became `UxEffects.hairline`.
- Mutation proof: a planted `Color(0xFF123456)` and `7.dp` in `UxText.kt` turned `style-guard` red (exit 1, both lines
  named); removed, green. Log in the same file.

**Fail loudly.** `wireEnum` returns the default only for an absent (null) value and throws `UxUnknownVariantException`
for a value that names no variant (was: silent default). Each element and template has a schema in the registry
(`UxElementSchema`, `UxTemplateSchema`: props with their enum keys taken from the Kotlin enums, events, slots, item
fields, regions). The interpreter checks every node before drawing it and draws a tagged `ux-error.<id>` text node
(`⟦error EL-x id: unknown prop 'p'; ...⟧`) and logs it; the state-entry log line carries the screen's schema problems.
Tests: unknown variant, unknown prop and unknown region draw the tagged node on both targets (`ProvisionalInterpreterScenario`,
+3 per target); the PROVISIONAL bundle and the offline-loading copy have 0 schema problems and a planted copy reports
each problem (`SchemaCheckTest`); adapters read only declared props (`RegistryAdapterScenario`). Decision: render-time error
node rather than a refusal at bundle load, because the loader (`:ux-runtime` bundle package) has no registry and a
refusal would blank the whole flow for one bad node; the same check is available at load time as `SchemaCheck.problems`.

**Registry versus library YAML** (`RegistrySchemaTest`, snapshot `ux-elements/spec-snapshot/ux-library-subset.json`,
spec commit `ec5e58e3def93cfbd3ba846005ccd3e2c13f53ba`, library clean, embedded as a build-generated test string so the
repo builds without the spec repo; refresh with `spike-evidence/M5c/tools/snapshot-library.py`). Result: prop names, enum
variants (normalised), events, slots and template regions equal the YAML for all nine elements and four templates, except
six differences, all listed with their reason in the test: `EL-button.popup_state_label` (renderer-owned extra, M5 deviation 5),
record fields `id`, `label_copy`, `copyable`, `media` of `EL-attribute-list.items` (boundary 1.7, not element props), and
the library's per-item `requirement_label`, which the registry does not draw (**GAP**: list-level labels are used). No variant
mismatch was found.

**Token shell** (`:ux-theme`). New: `UxElevations` (8 `sys.elevation.*`, layered shadows with inset and spread), `UxFonts`
(`sys.font.family.*`, `sys.font.weight.*`), `UxComponentTokens` (`comp.app-bar`, `button`, `link`, `sheet`, `rp-identity`,
`attribute-list`, plus NOT-IN-SPEC `scan` and `qr-viewfinder`), `UxEffects` (NOT IN SPEC: opacities, hairline), and
`UxMode` = Light, HighContrast, Dark, DarkHighContrast with the resolver's fallbacks in `UxBrand`. The PROVISIONAL eudiw_v1 file
is a hand-written transcription of the resolved spec tokens for all four modes (940 keys resolved per mode with
`spike-evidence/M5c/tools/resolve-theme.py`). **Elevation is drawn**: `Modifier.uxElevation` is applied for `comp.app-bar.shadow`
(bar divider), `comp.sheet.shadow` (the sheet), and the icon and floating button shadows; rings and inset rings are exact
(an iOS pixel test checks the inset ring), a blurred layer is the platform shadow of half the blur (offsets are not
honoured by Compose; declared approximation). Side effects of following the spec values: the app bar is 64 dp high (was 58),
button labels are Bold (was SemiBold, `comp.button.font-weight` is 700), section and display styles are Bold. Not run on a
device this milestone; no screenshot evidence of the shadows yet. Dark and high contrast are token-level only: `FlowHost` maps
`theme.default_mode` onto the four modes (`wireEnum`), nothing follows the system setting (F-dev-1 unchanged).

Exporter requirements for week-0 batch 3: `spike-evidence/M5c/exporter-requirements.md` (token groups, the four modes,
per-mode resolved emission, the NOT-IN-SPEC list). The orchestrator forwards it.

## Device runs (owner, iPhone Air, iOS 27.0.1, team D2YJRYD9TA)

DEVICE-TEST-1 on `d1ec06cf5` (M4 placeholders) and DEVICE-TEST-2 on `e837f53a7` (M5b screens), PROVISIONAL bundle,
Debug build from the device worktree. Rows `D1-iOS-device`, `D4-iOS-device`, `D6-iOS-device-*` in `spike-results.md`
(all preliminary, owner-run). Checklists and the raw log: `spike-evidence/device/`.

- B (happy), C (expired), D1-D3 (swipe in request/auth/sending) ok on both builds. E (kill/relaunch) and F (switch off)
  were skipped on the device; the simulator covers them (M4). D3-device stays open (Q-003).
- The first D3 report (swipe in `sending` "went back to the reference screens") was a misread; on retry `sending` stayed
  blocked and reached `success` on its own. No policy violation.

**F-dev-1: the M4 placeholders were unreadable in system Dark Mode.** The owner switched the phone to Light mode and
continued. Dark mode is out of brief scope. Check on the M5b theme: `UxTheme` does **not** follow the system mode.
`FlowHost` passes `mode = theme.default_mode` from the bundle (`"light"` in the PROVISIONAL bundle, Light as fallback),
and the PROVISIONAL brand has no dark token set (`UxBrand.dark = null` → light). The templates paint an opaque
`surfaceDefault` and every `UxText` sets a token colour, so the interpreted screens and `PresRequestScreen` render in
Light whatever the system mode: no readability risk inside the flow. Residual: the M4 `StatePlaceholder` (still used
for an undrawable state) uses `BasicText` with no colour or background, so it stays unreadable in dark; and the system
status bar follows the system mode over a light flow surface. Not fixed (out of scope).
