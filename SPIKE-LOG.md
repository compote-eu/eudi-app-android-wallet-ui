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
| M5d | done (probe, OR-1, not counted toward the verdict) | 2026-10-17 | 2026-10-17 | Sonnet | see Commits | SCR-home drawn from generated fragment bundles by the generic interpreter (`--ux-spike gallery-home[-<ui state>|-full|-high-contrast]`, spike files only). Coverage of 10 parts: 3 present, 2 partial, 5 missing; about 1.7 k LOC to close for Home alone. `spike-evidence/M5d/`. No pre-spike file touched. Gate: see Gate results. |
| M6 | M6a, M6b done (2026-10-17) | 2026-10-17 | — | Opus | see Commits | **M6a**: first real export synced (sync commit `7a24efacf`, ux@3d6ecb6, spec `sha256:88e2b611…02d0`, exporter 0.3.0, 19 files, lock check green); provisional contract, theme, copy, bundle and scripts deleted; one-time consumer list of DEC-075 applied; every provisional assertion re-run on the export (8 divergences, all resolved on the spike side, no exporter change); KC1 guard on the real bundle; ⟲ no movement (`b23f6a1f5`); full gate GREEN 4279; release isolation clean. See "M6a notes". **M6b**: formal iOS drills D1-iOS, D3-sim, D4-iOS, D5, D6-iOS all passed on the real export (`spike-results.md`, `spike-evidence/M6b/`); no kill signal; gate GREEN 4279. |
| M7 | in progress (walk queued) | 2026-10-16 | — | Opus | see Commits | `AccessibilityBridge` expect/actual in `:ux-runtime` (iOS ScreenChanged on the h1 element + queued announcements; Android view announcements), settle-time focus/announce from the bundle a11y, leaving state cleared during the crossfade, TplSheet dialog semantics, list/group semantics, mandatory items not "dimmed", link label once; FlowHost-level D7 tests; scripted Inspector audit; Q-D7-iOS queued (device rebuild needed). No KC3 kill-signal. ⟲ at end not done (no rebase in this run). |
| M8 | done (all 4 D8 rows run; all 4 **failed**; KC1 signal on D8-logo, to be re-derived) | 2026-10-17 | 2026-10-17 | Opus | see Commits | **D8 baseline SHA: app `93c2b9931`, spec `dvm-week0` `874b4f9`** (exporter 0.3.0). ⟲ `origin/feature/kmp` still `b23f6a1f5`, no rebase. Pre-D8 grep 0. Every `spike/d8-n` diff is `ux-spec/**` only (0 hand-written lines) and uxspec-lock is green, but the full gate is red on all four: a spike test fixture pinned to the baseline export (all four), pinned counts (offline), the element schema (logo). Behaviour changed for offline, brand, copy; not for logo (element gap + hand-written consent screen: KC1 kill-signal candidate). See "M8 notes", `spike-evidence/D8/README.md`. |
| M8r | done (remediation; rework) | 2026-10-17 | 2026-10-17 | Opus | see Commits | Independent verifier ruled (B), KC1 **not hit**; D8 attempt 1 stays **failed** (rows unchanged). Three code commits, all generic and pre-baseline: M8r-1 tests un-pinned (expectations from `ux-spec/` at test time; added-state copy built at test time with `_test` names), M8r-2 `EL-rp-identity.logo` (Coil 3, decorative, hidden from a11y), M8r-3 `ScreenScope.Node` (week-0 F3) used by `PresRequestScreen` for `rp-trusted`/`rp-untrusted`. Gate: see Gate results. **Attempt-2 baseline: tag `d8-attempt2-baseline` = the M8r docs commit** (code tree = `2487ff3c3`). Protocol: SPIKE-PLAN §5 "D8 attempt 2". |
| M8b | done (D8 attempt 2, plan M8-2: 4 known rows **passed**, D8-unseen **failed** on a test pin; no KC1 signal) | 2026-10-17 | 2026-10-17 | Opus | see Commits | From tag `d8-attempt2-baseline` (`6946d80c5`), spec `dvm-week0` `874b4f9` (RULING-M8b-1). Each `spike/d8b-n` diff is `ux-spec/**` only (0 hand-written lines). Gate GREEN on d8b-1..4, RED on d8b-5 (2 tests, `InterpreterScenario` expects every node of an interpreted screen drawn and ignores the new hide rule: test pin). Behaviour changed as specified on all five. Rows: `spike-results.md` D8-* (attempt 2). Evidence: `spike-evidence/D8-attempt2/`. |
| M6c | done (sync exporter 0.5.0 + one-time adoption) | 2026-10-17 | 2026-10-17 | Opus | see Commits | Sync commit `bad2c1871` (ux@`b55be65`, spec `sha256:845b7121…29f2`, exporter 0.5.0, services contract 1.0.0 `none`; 70 files, lock green), after one test-only prep commit (`0d3c9f201`, RULING-M6c-2). One-time adoption: drawables and `ux-assets.json` / `ux-library.json` / `ux-tokens.json` wired (every Unicode glyph placeholder replaced; 18 asset ids drawn for eudiw_v1, 13 for idsk), `RegistrySchemaTest` on `ux-library.json`, the fake engine implements `PresCrossDeviceResultDeclaration` (conformance test proven red once). D8-unseen test pin fixed (`2bd7a5e9a`; the row stays failed). ⟲ `b23f6a1f5`, no movement. Gate GREEN 4300; release isolation clean. See "M6c notes". |
| M5e | done (Home parts, OR-8; a probe, not counted toward the verdict) | 2026-10-17 | 2026-10-17 | Sonnet | `79ac208e4`, `82ce17ba3`, log commit | EL-status-chip, EL-document-card, EL-bottom-bar, EL-segmented-switch, EL-trust-mark and TPL-home built from the export's `comp.*` tokens (registry, schema, a11y, tests on both targets, KNOWN_GAPS empty); app-bar gaps closed; avatar and lock icons drawn; `comp*` colour, size, weight and shadow readers beside `compDp`; the Home probe reads the exporter's SCR-home probe (gating, oracle). 56 simulator shots with spec-renderer references. Gate GREEN 4360. See "M5e notes". |
| M9 | done (D9-brand: 0 lines, flow passed, gate red 12 on Home-probe test pins; D9-sheet-page: 0 TplSheet lines, KC4 signal on 1 sheet-pinned runtime test) | 2026-10-17 | 2026-10-17 | Sonnet | `0f5d5fbdb`, `bbc37a14a`, `997f47a26`, log commit | D9 baseline SHA: `646fea3b8` (tag `d9-baseline`). Spec `dvm-week0` `b55be65`. Option commits applied in place on this branch (no branch switch, per the run's rule) and the baseline `ux-spec/` restored after; numstat per option is `git diff --numstat 646fea3b8..<option commit>`. See M9 notes. |
| M9r | done (rework, test harness: test-pin sweep, regen-smoke gate step, TplSheet sheet top inset) | 2026-10-17 | 2026-10-17 | Opus | `a652b1565`, `069e596ac`, `391edd84e`, log commit | Verifier ruled D9 = (C): both options pass at 0/0 lines, KC4 not hit, §6.5 breached by test pins. Spike rule T1 (SPIKE-PLAN §0.2b). Full gate GREEN 4360/0 incl. regen-smoke 9/9. See "M9r notes". |
| M10 | done (harness preliminary; D10 device runs queued as Q-D10) | 2026-10-17 | 2026-10-17 | Sonnet | see Commits | `UxDcApiSubflowViewController` (+ `uxDcApiSubflowEnabled`) in `iosUxSpikeMain`, T5 in `ConsentHost.swift` (import and branch inside `#if DEBUG`, +12 lines), in-app harness `--ux-spike dcapi-harness`, D10 instrumentation (`UX-SPIKE-D10` via NSLog), HTTPS test page `spike-evidence/M10/dcapi-test-page/`. Only T5 touched (T1-T5 used, cap 8). Gate GREEN 4273. ⟲ at end not done. |
| M11 | not started | — | — | — | — | ⟲ at start; needs emulator/device |
| M12 | not started | — | — | — | — | ⟲ final |
| M11 | done (2026-10-17): Android drills D1, D2(a), D2(b-approx), D4, D6, D7 passed; real LMK and real-phone TalkBack queued | 2026-10-17 | 2026-10-17 | Sonnet | see Commits | Dev Debug APK on emulator `spike_pixel` (API 37, arm64), sk, Light. P2 (the hook module loads in the first activity, `UX-SPIKE: hook module loaded`) and P6 (`UxFlowRoute` + snapshot restore after process death) verified on Android; Release APK isolation clean again. 0 of 3 hard states needed a Back or restore handler outside the bundle policy: no KC2 signal from Android. No KC3 signal (emulator, events only). Findings F-D2-1, F-M11-1..4 in "M11 notes". Gate GREEN 4360. |

## Rulings

| Id | Date | Question | Minimal-footprint view | Faithful-to-brief view | Judgement | Touch list widened? |
|---|---|---|---|---|---|---|
| RULING-1 | 2026-10-16 | M1a review panel (brief-compliance + technical reviews, merged) on `SPIKE-PLAN.md` | technical review: hook renamed to `DebugFlowHook` (release grep with no exemption); `#if DEBUG` around the ConsentHost import; one `backHandler(` call; KMP skeleton details; ui-test without a catalog touch; QrScan redirect null for Issuance/Signature; F2 run-once guard; no `simctl defaults write`; Koin hook-absent/present test; iOS Gradle-graph + Android dev/demo classpath checks | brief-compliance review: verdict needs D4, D5, D6 on both platforms and D7-Android; KC3 per the brief (VoiceOver); every code milestone closes with a §7 drill (preliminary allowed, not counted); KC1 fails on any non-`ux-spec` D8 diff unless the owner ruled in advance, data-driven scripts; wider KC1 and D1 greps; pre-D8 baseline grep, brand-selection path; graph-only Android check = unresolved, APK required; extra ⟲ after M7 and before M11, named D8 baseline; rows split; D3 device queued; D10 not run = Incomplete; D9 common lines count per platform; `--diff-filter=MDRT`; ≤ 1 file per ruling | all 21 points accepted and folded in; M1b defaults adopted (boundary §7 Q1–Q17); refinements F1–F5 accepted | no (5 planned + 3 reserved, cap 8) |
| RULING-M6a-1 | 2026-10-17 | The sync commit (ux-spec/ only) would flip `:ux-contract` off the provisional copy (keyed on the lock) before the consumer is adopted: a red commit. | key the provisional srcDir on its own directory in a prep commit | the sync commit stays verbatim | Prep commit `2da2ca566` keys it on `provisional/kotlin`; the sync commit adds ux-spec/ only and is green; the adoption commit deletes the directory. | no |
| RULING-M6a-2 | 2026-10-17 | V29: the P3 probe file `files/ux-probe.txt` no longer ships with `customDirectory`. | drop it | keep it elsewhere | Dropped with `UxContractModule.PROBE_PATH` (nothing read it in code); the exported bundle read at run time proves the resource crossing. | no |
| RULING-M6a-3 | 2026-10-17 | The fake engine reads scripts from `:ux-spike` resources; the export puts them in `ux-spec/fixtures/scripts` (outside `ux-spec/resources`). | Sync task copies them into build/ beside the module's own resources | — | `uxSpikeComposeResources` (Sync: `src/commonMain/composeResources` + the exported scripts) is the `customDirectory`; ux-spec/ is never edited. The host also refuses a script whose `spec_hash` differs from the bundle's (V23). | no |
| RULING-M6a-4 | 2026-10-17 | The M5d Home probe's copy came from the deleted `ProvisionalBundleCopy`. | keep the probe readable | — | `UxHomeProbe` reads the exported strings first, then the fragment's own `_sk` texts (probe data, not an export); its marker is "Probe data (M5d)". | no |
| RULING-M6a-5 | 2026-10-17 | `git grep PROVISIONAL` clean (boundary §6.6) vs the permanent H7 guard and the M5d probe data. | exclude both | — | Clean with `':!spike-evidence' ':!*.md' ':!ux-spike/src/commonMain/composeResources/files/home-probe'` and the identifier `FlowBundle.PROVISIONAL_PREFIX` (H7 stays a runtime guard; `ProvisionalNotAllowed` likewise). | no |
| RULING-M6a-6 | 2026-10-17 | The registry-vs-YAML snapshot shows the library's new `EL-rp-identity.logo` (week-0 X15 / RULING-W1). | list it as a GAP | add the prop now | GAP until M8 D8-logo: adding and drawing it is what that drill measures; no node of the export binds it. | no |
| RULING-M6a-7 | 2026-10-17 | The snapshot tool records the spec repo HEAD (874b4f9), the export's spec commit is 3d6ecb6. | record 3d6ecb6 | — | `git diff 3d6ecb6 874b4f9 -- ux/library` is empty; the snapshot records `source_commit` 3d6ecb6. | no |
| RULING-M6a-8 | 2026-10-17 | The exporter's model fixture (V6/R5) has no consumer yet. | add one replay test | — | `ExportedModelReplayTest`: the 84 sequences replay to the same states in this engine and the plain invariants hold (+3 tests per target). | no |
| RULING-2 | 2026-10-16 | M3 finding 3: what does a service that throws mean? | — (orchestrator ruling, no panel) | — | A service that throws, or a coroutine failure other than cancellation, is converted into the `ServiceResult` type `Error` with payload `{class: unexpected}` and follows the bundle's declared error branch. If a state declares no error branch, the bundle is refused at load. Implemented in M4: `FlowEngine.callPort` (a `CancellationException` while the state job is still active counts as a failure); `BundleValidator.requireErrorBranch` requires, for every **state-scoped** invoke, an `Error` result on the service and an `invoke.on.Error` mapped to an event of the state's `on` (detached invokes are exempt: their results are ignored). `Error` becomes the single engine-convention name, exempted in `ProvisionalRuntimeLiteralGuardTest`. Tests: `ProvisionalRuling2Test` (9). Queued for week 0 / owner as Q-004 with this default. | no |
| RULING-M8-1 | 2026-10-17 | Order of the drills. Analysis before running showed D8-logo needs a Kotlin edit (element and hand-written screen), and the M8 brief says to STOP on a screen-code signal; the plan (§5) says a kill signal is re-derived independently before anything stops. | run 1 first and stop | run all four | The four rows are independent (same two baselines), so they ran in the order 3, 4, 2, 1: the STOP point is the last drill, and all rows exist for the independent re-derivation. No Kotlin edit was made anywhere. | no |
| RULING-M8-2 | 2026-10-17 | D8-offline first try: `make test` red, E12 (the week-0 rehearsal of the same four changes) overlays `connecting.on.SYS_OFFLINE → connecting-offline` and so replaced the drill's own `SYS_OFFLINE → offline-loading` (G4: unreachable). | — | — | The drill's event is named `SYS_NETWORK_LOST` (a name, not a weaker check); then `make fast check test` is fully green. Recorded as a week-0 finding: E12 hard-codes event and field names that a real change may reuse. | no |
| RULING-M8-3 | 2026-10-17 | D8-logo: `make test` red, E12 expects `logoUrl` to be *added* by its overlay, but the tree already has it. The field name is fixed by the brief. | — | — | Not weakened, not renamed. `make fast` and `make check` are green and the exporter (which runs every gate) accepted the tree, so the export proceeded; the red E12 is recorded in the row and README. | no |
| RULING-M8-4 | 2026-10-17 | The spec repo's rules make copy and new screens "owner first" (provisional DEC). | — | — | The worktrees are throwaway and never committed, so no DEC/CAN record was written; the spec changes follow the existing record conventions (copy provenance `authored … decision: DEC-005`, sources, satisfies) and pass every gate. | no |
| RULING-M8-5 | 2026-10-17 | Classification of the red gates. The M8 brief's taxonomy has screen/runtime (kill signal), fake engine (OR-3), element/template gap (failed). The pinned TEST fixture (`spike-evidence/M6a/…offline-loading.TEST.json`, read by `:ux-runtime` commonTest) and the pinned counts (`HandshakeTest.h1` 25 states, `FakeScriptsTest` six names) fit none. | — | — | Strict D8 rule (RULING-1): the gate is red without a non-ux-spec edit, so all four rows are **failed**; classified as *spike test-side pins* (test code only, no screen, runtime-main, element or fake-engine code), not as KC1 product signals. Not fixed here (no rebase during M8; the baseline is fixed). Fix candidate: derive the added-state fixture at build time from `ux-spec/` and assert counts from the bundle; then re-run D8 from a new baseline (owner). | no |
| RULING-M8r-1 | 2026-10-17 | Was RULING-M6a-6 (keep `EL-rp-identity.logo` a GAP "until M8 D8-logo") right? | — | — | **No, it was a mistake.** It left an element capability gap open on purpose until the drill that measures regeneration, so D8-logo could only fail on the element. A library prop the spike already knows must be implemented before the D8 baseline. Corrected in M8r-2; SPIKE-PLAN §0.2a. | no |
| RULING-M8r-2 | 2026-10-17 | Week-0 F3 / RULING-W1 (`week0-plan.md:307-308, :418`: "the hand-written consent screen draws its EL-rp-identity bundle node through the interpreter") is absent from SPIKE-PLAN. | — | — | Missing from the plan; added to §1.4 and §0.2a. Implemented generically as `ScreenScope.Node(id)` (ux-runtime, via `ScreenTree.node`, no screen code); `PresRequestScreen` draws `rp-trusted` / `rp-untrusted` through it; the consent snapshot still comes from the flow context. | no |
| RULING-M8r-3 | 2026-10-17 | `logo` empty or absent: the library anatomy says "absent = the icon" (a generic organisation icon), the M8r brief says "nothing is drawn". | — | — | Nothing is drawn (brief). `UxRpIdentity` never drew the generic avatar icon (a pre-existing, separate anatomy gap, not touched here). The avatar box is `size.target` (48 dp) = library `comp.rp-identity.avatar-size` (48 px; not exported to Kotlin, so not read from a token). | no |
| RULING-M8r-4 | 2026-10-17 | How to build the added-state copy "from ux-spec/ at test time" so that it survives a spec that itself adds states. | a Gradle task running the M6a Python script | build it in Kotlin commonTest | `AddedStateFixture` (commonTest, host + iOS) builds it from the embedded export (`exportedBundleFixture`, checked against UX_SPEC.lock). Every added name starts with `_test` (state, screen, event, result, copy ids, resource names), which kmp-bundle.schema.json forbids in an export, and the builder checks absence. The host state is the first state that invokes a service; its screen is cloned with test-only copy keys; every emitted event and the back event return to the host. The M6a TEST.json stays in `spike-evidence/M6a` as evidence; its Gradle task is gone. | no |
| RULING-M8r-5 | 2026-10-17 | Does the KC1 guard flag the node ids in `PresRequestScreen`? | — | — | No: `rp-trusted` / `rp-untrusted` are neither `SCR-` / `"pres-` literals nor state names, and the file is excluded as the declared exception anyway. Guard green ("0 hits (25 state names checked)"). One hit during M8r was a `^SCR-…` regex quoted in a test KDoc (`InterpreterSupport.kt`), rewritten to `^SCR…`. | no |
| RULING-M8r-6 | 2026-10-17 | D8 attempt-2 protocol. | — | — | New named baseline: tag `d8-attempt2-baseline` = the last M8r commit (code tree `2487ff3c3`). Attempt 2 is **not blind** for the four known drills (patches public, causes known; the M8r agent also ran the spike-module tests against the D8-1 and D8-2 attempt-1 exports once, green, then restored `ux-spec/`). One additional **UNSEEN** drill, chosen by a separate agent after the baseline; the M8r agent did not anticipate it. Corrected D8-logo pass condition: an image node hidden from accessibility, with the name still naming the party. SPIKE-PLAN §5. | no |
| RULING-M8b-1 | 2026-10-17 | Spec baseline for attempt 2: the app's `UX_SPEC.lock` records ux@`3d6ecb6`; the brief names `874b4f9`. | — | — | `874b4f9` (dvm-week0) descends from `3d6ecb6` with docs only (`week0-plan.md`, `DEC-075.yaml`); all 77 lock sources match byte for byte at both; exporter 0.3.0 at `874b4f9`. Used `874b4f9`, as in attempt 1 (not `spike-week0` HEAD). | no |
| RULING-M8b-2 | 2026-10-17 | `ux_dirty`: the gate's uxspec-lock step accepts `ux_dirty: true` only on `spike/d8-*`; the attempt-2 branches are `spike/d8b-*` (brief). | export with `ALLOW_DIRTY=1` (gate red by branch name) | commit the patch in the throwaway worktree | The patch was committed on a **detached HEAD** in each throwaway worktree (on no branch, never pushed, dropped with the worktree; SHAs in `D8-attempt2/<n>/spec-commit.txt`), so the lock says `ux_dirty: false` and records that SHA. The spec repo's branches are untouched. This is the option the D8-unseen README names. | no |
| RULING-M8b-3 | 2026-10-17 | D8-logo spec gate: E12 red again (expects `logoUrl` to be new). | — | — | Same resolution as attempt 1 (RULING-M8-3): E12 rehearses this very change, so it cannot pass on a tree that contains it; `check` (uxcheck 0 errors, a11y 0 violations) and the other 47 export checks are green and the exporter accepted the tree. Not weakened. | no |
| RULING-M8b-4 | 2026-10-17 | First spec-gate run (all five in parallel): all five red in `make check`'s a11y step (`Page.goto: Timeout 30000ms`), load average 237 (5 gates + Spotlight indexing the new worktrees + simulator). | — | — | Infrastructure, not spec: rerun one at a time, all as expected (1: E12 only; 2-5 fully green). First-try logs kept as `spec-gate-try1-parallel-timeout.log`. | no |
| RULING-M8b-5 | 2026-10-17 | How to show on iOS that the logo is an image node hidden from accessibility: `maestro hierarchy` lists every node with a test tag, accessibility elements or not. | — | — | Use the XCUI audit tree (`spike-evidence/tools/ios-a11y-audit.sh`): `rp-trusted.logo` is type `Other` (not `Image`), no label, and `performAccessibilityAudit(.all)` reports nothing on it (an exposed unlabelled image would be flagged), like the non-element container `rp-trusted`. Plus layout: the name moves right by the 48 pt box. The unit-level semantics (`hideFromAccessibility`) is the M8r-2 test, green in the gate. | no |
| RULING-M8b-6 | 2026-10-17 | D8-unseen gate red: `InterpreterScenario.outcomeExpiredIsDrawnFromTheBundleAndFocusesItsHeading` asserts every node of SCR-pres-outcome-expired is drawn when fetchRequest answers `Expired`; the spec's new `is_empty` rule hides `verifier` exactly there. | — | — | Strict D8 rule: the row **fails** (green needs an edit outside `ux-spec/`). Classification **test pin**: the test's expectation is not derived from the screen's rules (helper `assertDrawnFromBundle` excludes only unlinked EL-links). The runtime is right (condition 4 of the drill holds on the simulator). Not generated, interpreted-screen or runtime screen code, so **not a KC1 signal**. Not fixed here (no edit on drill branches; the baseline is fixed). Fix candidate for the owner: evaluate the screen's rules in the helper (expected hidden = rules true for the context). | no |
| RULING-M6c-1 | 2026-10-17 | The export in `dvm-week0/ux/build/kmp-export/ux-spec` records ux@`d40f842`, not `b55be65`, and its changelog's `previous` is the 0.4.0 export (c17fcde), which this app never had. | copy it as is | regenerate | Regenerated at `b55be65` (sources equal to `d40f842`: docs only between them) with `make -C ux export-kmp OUT=<scratch> PREVIOUS_LOCK=<this repo's ux-spec/UX_SPEC.lock>`, so the services changelog classifies against the 0.3.0 export the app has (1.0.0, `none`, previous 3d6ecb6). Differs from the build export only in `ux_commit`, headers and the changelog's `previous`. dvm-week0 untouched. | no |
| RULING-M6c-2 | 2026-10-17 | The sync alone turned `CopyResolverTest.theBrandOverrideWinsOverTheBaseKey` red: it took the first strings.xml resource ending in `__<brand>`; the Home probe copy (`cpy_home_card_valid__eudiw_v1`) now sorts first. | — | — | A test pin. Fixed in a prep commit before the sync (`0d3c9f201`: the expectation is the bundle's `brand_overrides` `res`), green on the 0.3.0 and 0.5.0 exports, so the sync commit stays `ux-spec/**` only and green. No build wiring was needed: `:ux-contract`'s `customDirectory` already is `ux-spec/resources`, so `drawable/` became `Res.allDrawableResources` with no change. | no |
| RULING-M6c-3 | 2026-10-17 | Where the element-to-asset mapping lives. | a Kotlin `when` per element (as the glyphs were) | read the library data | Elements ask `UxLibraryAssets` (`ux-library.json` element `assets`: fixed id, prop-value map, `kind`) by element, part and prop value, and `UxAssets` (`ux-assets.json`) per brand; only part names are Kotlin literals, checked against the library by `RegistrySchemaTest.everyAssetPartTheElementsDrawIsInTheLibrary`. An id the brand does not bind draws nothing (DEC-080: no art invented), except the status hero, whose unbound illustration falls back to the tone glyph in a circle (`ux-assets.json` `unbound`). | no |
| RULING-M6c-4 | 2026-10-17 | SVG drawables (`document-art.pid`, four eudiw_v1 illustrations) cannot be loaded by Compose MP on Android. | — | — | `UxAssets.asset` hides a `.svg` where `svgDrawablesSupported` is false (`:ux-theme` expect/actual: Android false, iOS true); the hero then draws its glyph fallback. iOS draws the SVGs (shot). Exporter gap for Android, as the export's own note says. | no |
| RULING-M6c-5 | 2026-10-17 | Which `ux-tokens.json` values the app reads now. | none (the shell suffices for drawn parts) | every key | Only the sizes of the parts this milestone draws, by exact key with the shell value as fallback: `comp.notice.icon-size`, `comp.button.icon-glyph-size`, `comp.attribute-item.tick-size`, `comp.status-hero.icon-size`, `comp.status-hero.art-min-size`. The 8 RULING-W3 tokens already fill shell fields through the generated theme (values unchanged); the shell KDocs that still said NOT IN SPEC now name them. Colours stay from the shell. The Home parts are the main consumer. | no |
| RULING-M6c-6 | 2026-10-17 | The library's parts with no element yet in the registry (EL-bottom-bar, EL-document-card, EL-segmented-switch, EL-status-chip, EL-trust-mark, TPL-home) once `RegistrySchemaTest` reads `ux-library.json`. | — | — | Listed as `KNOWN_GAPS` (stale entries fail), for the Home parts milestone. Also not drawn yet, though in the library: EL-rp-identity "organisation avatar" (`icon.apartment`) and EL-attribute-item "mandatory lock" (`icon.lock`): no glyph placeholder existed, so adding them is new layout, left to that milestone. | no |
| RULING-M6c-7 | 2026-10-17 | How the fake engine declares its results. | derive from the scripts | list them | Listed by name (DEC-081 wants an explicit declaration): a script may name any result. Consequence: a spec change that adds a sealed result (as D8-offline did) now turns `FakeResultDeclarationTest` red until one line is added to the fake (an OR-3 fake-engine cost, by design of DEC-081). | no |
| RULING-M5e-1 | 2026-10-17 | Where the part values come from. | Kotlin constants | read every value from `ux-tokens.json` by exact key (`UxComp`), the shell's `sys.*` role only as fallback | `UxComp(group).dp/color/sp/weight/shadow(name, fallback)`. No `.dp`, `Color(`, alpha or weight literal in the parts (style guard 0 hits, nothing allowlisted); `FontWeight` is named only through `typealias UxFontWeight` in `:ux-theme`. | no |
| RULING-M5e-2 | 2026-10-17 | The brand's part styles (chip mark and ring, card face, switch selected style) are not in the generated `UxBrandConfig`. | wait for the exporter | read `ux-library.json` `brand_selections` (`UxBrandSelections`) | Elements ask `UxThemeAccess.selection("EL-status-chip.mark")` etc.; absent = the library default. Exporter note: the reverse-coverage rule keeps these out of `UxStyles`. | no |
| RULING-M5e-3 | 2026-10-17 | KC1 guard versus the probe file name `SCR-home.probe.json`. | allow the literal in one file | the build copies `*.probe.json` to `files/probes/` renaming by `substringAfter('-')`; the Kotlin reads `files/probes/<name>.probe.json` | No screen id in any scanned file; the guard's M5d data exclusion is deleted (stricter). | no |
| RULING-M5e-4 | 2026-10-17 | The probe holds only the default brand's gated screen (`brands: ["eudiw_v1"]`). | refuse other brands | gating of the probe's brands applied as exported; for another brand the library's variant `omits` for its `brand_selections` | idsk draws eudiw_v1's screen minus the bottom bar. It cannot get back `switch`, `trustmark`, `present` (dropped from the probe). Exporter requirement: unfiltered nodes with per-brand `hidden`. | no |
| RULING-M5e-5 | 2026-10-17 | The title (h1) in TPL-home. | draw it in the content | with an app bar, in the bar's band between the controls (the renderer's `el-app-bar--untitled` rule), via `LocalUxBarTitle` | `EL-text variant=title` takes the bar's title style there; heading semantics and focus unchanged. | no |
| RULING-M5e-6 | 2026-10-17 | Values the spec CSS hard-codes (aspect ratio 1250:788, `letter-spacing .12em`, upper-case card titles, inactive art opacity .5, 88 % frosted band). | put them in Kotlin | not drawn / approximated from existing tokens: the ratio is the loaded artwork's intrinsic size (none without art: content height), inactive art uses `sys.opacity.dimmed`, band opaque | Listed as exporter requirements (tokenise them). | no |
| RULING-M5e-7 | 2026-10-17 | The `open` event needs `interactive`; the screen nodes do not set it. | require it | default true as the library says | Registry: `interactive = flag != false`. | no |
| RULING-M5e-8 | 2026-10-17 | `trust-mark.colour` is bound for no brand in `ux-assets.json`. | invent art | the library's fallback: the framed placeholder | Finding for the exporter. | no |

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
| 2026-10-17 | 2487ff3c3 | M8r | full `./spike-gate.sh` | 4283 (was 4279 at `93c2b9931`: +4 = the new logo semantics test ×2 targets in ux-elements, and the new `ScreenScope.Node` interpreter test ×2 targets in ux-runtime; the three un-pinned tests are changed, not added; ux-elements 32/33, ux-runtime 90/92, ux-spike 12/4) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN (kc1-guard 0 hits, 25 state names; style-guard 0; uxspec-lock green) |
| 2026-10-17 | a89e594bd | M8b (spike/d8b-1, logo) | full `./spike-gate.sh` | 4283 | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-17 | bb82450b1 | M8b (spike/d8b-2, offline) | full `./spike-gate.sh` | 4283 | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-17 | ae755998e | M8b (spike/d8b-3, brand) | full `./spike-gate.sh` | 4283 | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-17 | 236ad3417 | M8b (spike/d8b-4, copy) | full `./spike-gate.sh` | 4283 | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-17 | 075c2be9d | M8b (spike/d8b-5, unseen) | full `./spike-gate.sh` | 4283 | 2 (`InterpreterScenario.outcomeExpired…` host + iOS, RULING-M8b-6) | 0 | pass | pass | Debug and Release build | GATE RED (gradle-tests only) |
| 2026-10-17 | 2bd7a5e9a | M6c | full `./spike-gate.sh` (5 min 23 s) | 4300 (was 4283: ux-elements +4 ×2 (3 asset scenario, 1 registry asset parts), ux-runtime +1 ×2 (pin regression), ux-spike +2 iOS / +5 host (conformance 2, exported resources 3)) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-17 | 82ce17ba3 | M5e | full `./spike-gate.sh` (about 5 min) | 4360 (was 4300: +60 over both targets: the 20 Home-part tests and the list-item registry test of `:ux-elements`, the probe and oracle tests of `:ux-spike`, the reader tests of `:ux-theme`) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-17 | 0f5d5fbdb | M9 D9-brand | full `./spike-gate.sh` (6 min 3 s) | 4360 | 12 (10 `HomeParts*Test`, 2 `ScreenProbeTest`: Home-probe pins to eudiw_v1) | 0 | pass | pass | Debug and Release build | GATE RED (gradle-tests only) |
| 2026-10-17 | bbc37a14a | M9 D9-sheet-page | full `./spike-gate.sh` (6 min 37 s) | 4360 | 2 (`InterpreterScenario.rulesAndBindingsFollowTheContext` host + iOS: sheet `app-bar` pinned) | 0 | pass | pass | Debug and Release build | GATE RED (gradle-tests only) |
| 2026-10-17 | 997f47a26 | M9 (baseline `ux-spec/` restored) | full `./spike-gate.sh` (6 min 57 s) | 4360 | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-17 | 069e596ac + TplSheet fix (committed as 391edd84e) | M9r | full `./spike-gate.sh` (13 min 51 s; regen-smoke 491 s: baseline, d8b-1..5, m9-1-brand, m9-2-sheet-page, m9-2b-page-idsk each 358/0 host + iOS) | 4360 | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

| 2026-10-16 | 700787c3c | M4 | `./spike-gate.sh --fast` (RULING-2) | 4117 (ux-runtime host 57, iOS 56; rest as M3) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | b754b7543 | M4 | `./spike-gate.sh --fast` (host, fake engine) | 4145 (ux-runtime 66/61, ux-spike 11/3, ux-contract 2; rest as M3) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | b754b7543 + FlowHost back test (this commit) | M4 | full `./spike-gate.sh` | 4148 (shared-logic iOS 832, shared-ui iOS 648, host 166/562, ux-runtime 66/64, ux-spike 11/3, ux-contract 2, module unit tests as M0) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

| 2026-10-16 | f34c35dc0 | M5b | `./spike-gate.sh --fast` (+ kc1-guard) | 4220 (ux-runtime 76/74, ux-contract 4/2, ux-elements 23/23, ux-spike 12/4; rest as M4) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | f34c35dc0 + drill evidence | M5b | full `./spike-gate.sh` (+ kc1-guard) | 4220 (as the fast run) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

| 2026-10-16 | adb3e33b1 | M7 | `./spike-gate.sh --fast` (+ kc1-guard) | 4234 (ux-runtime 81/83, ux-elements 23/23; rest as M5b) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | adb3e33b1 + link label (this commit) | M7 | full `./spike-gate.sh` (+ kc1-guard) | 4234 (as the fast run) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

| 2026-10-16 | f1eac295f + fc32e0b72 (working tree, same content) | M5c | `./spike-gate.sh --fast` (+ kc1-guard, style-guard) | 4273 (ux-elements 31/32, ux-runtime 86/88, ux-theme 6/6, +39 over M7; rest as M7) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-16 | fc32e0b72 + this commit (docs) | M5c | full `./spike-gate.sh` (+ kc1-guard, style-guard) | 4273 (as the fast run) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-17 | f0c52dc83 | M5d | `./spike-gate.sh --fast` (try 1 on the working tree was RED: kc1-guard hit `SCR-` in the fragment bundle data; fixed in `spike-gate.sh`) | 4273 (ux-spike 4/12, rest as M5c) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-17 | f0c52dc83 + this commit (docs) | M5d | full `./spike-gate.sh` | 4273 (as the fast run) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-17 | ab7011ada | M10 | `./spike-gate.sh --fast` | 4273 (as M5c; no new tests) | 0 | 0 | pass | pass | Debug build | GATE GREEN |
| 2026-10-17 | ab7011ada + this commit (docs) | M10 | full `./spike-gate.sh` | 4273 (as the fast run) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

| 2026-10-17 | 909ccf460 | M6a | full `./spike-gate.sh` (+ kc1-guard on the real bundle, style-guard, new uxspec-lock) | 4279 (ux-runtime 89/91, ux-contract 4/2, ux-elements 31/32, ux-spike 12/4, ux-theme 6/6; rest as M10) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-17 | 8830b6fe7 | M6b | full `./spike-gate.sh` (no code change; Maestro flow, tool and evidence only) | 4279 (as M6a) | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |
| 2026-10-17 | f223fad7e (`spike/d8-3`, deleted) | M8 D8-brand | full `./spike-gate.sh` (11 min 33 s) | 4279 | 4 (pinned M6a TEST fixture, host + iOS) | 0 | pass | pass | Debug and Release build | GATE RED |
| 2026-10-17 | 0541d2e5e (`spike/d8-4`, deleted) | M8 D8-copy | full `./spike-gate.sh` (8 min 31 s) | 4279 | 4 (as D8-brand) | 0 | pass | pass | Debug and Release build | GATE RED |
| 2026-10-17 | c12e0e2ca (`spike/d8-2`, deleted) | M8 D8-offline | full `./spike-gate.sh` (6 min 33 s) | 4279 | 7 (fixture 4, `HandshakeTest.h1` 2, `FakeScriptsTest` 1) | 0 | pass | pass | Debug and Release build | GATE RED |
| 2026-10-17 | 8fd57b22d (`spike/d8-1`, deleted) | M8 D8-logo | full `./spike-gate.sh` (5 min 16 s) | 4279 | 6 (`SchemaCheckTest` 2 tests × host/iOS: `unknown prop 'logo'`; `InterpreterScenario` × 2: pinned fixture) | 0 | pass | pass | Debug and Release build | GATE RED |
| 2026-10-17 | 25e2e31dc (+ M11 evidence, docs only) | M11 | full `./spike-gate.sh` (no code change; the run includes regen-smoke) | 4360 | 0 | 0 | pass | pass | Debug and Release build | GATE GREEN |

M6a test delta, 4273 → 4279 (+6): `ExportedModelReplayTest` adds 3 tests on each target. Nothing else changed in count:
the 2 `ProvisionalBundleResourceTest` tests became the 2 `BundleResourceTest` tests; 17 `Provisional*` runtime classes,
`ProvisionalInvokerDefaultsTest` and `ProvisionalFakeScriptsTest` were renamed with the same test methods; one theme test was
rewritten in place (V19). No test was deleted.

M6a release-isolation check (⟲): iOS `CONFIGURATION=Release :shared-ui:dependencies` 0 `project ':ux-` lines (control without
it: 168); Release `EudiWallet` and `.appex` binaries `nm -gU` 0 and `strings` 0 (spike patterns plus `UxBrandConfig`,
`UxThemeSpec`, `PresCrossDevice`, `UX-SPIKE`); `.app`/`.appex` compose resources only `eu.europa.ec.shared.resources`.
Android `devReleaseRuntimeClasspath` / `demoReleaseRuntimeClasspath` 0 (control `devDebugRuntimeClasspath` 8);
`assembleDevRelease` with a throwaway keystore: `apkanalyzer` dex 0, files 0, no `ux-spike-init` provider (control dev
debug APK: 7574 dex hits, 24 files, provider present). Behaviour-off screenshot not retaken: no pre-spike file changed.

M10 Release spot check: `strings` on the Release app and extension binaries finds 0 patterns (`UxDcApi`, `UX-SPIKE`, `uxDcApiSubflow`, `IosUxSpike`).

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
| M5d | runtime probe (generator, hook, coverage; elements not implemented) | about 50 min (first to last commit is shorter; most of it is two xcodebuilds, 8 simulator runs and the gate) | ux-spike 87/0 (`UxHomeProbe.kt` 81 incl. licence header, `UxFlowEntry.kt` 6); root 3 (`spike-gate.sh`) | 0 (the 15 fragment bundles, 7 of them packaged, are generated by a scratchpad script `spike-evidence/M5d/tools/make_home_fragment.py`, about 190 lines Python, not counted) | 1 spike file (`UxFlowEntry.kt`), `spike-gate.sh`; 0 pre-spike | 2 (fast RED on the KC1 guard, then fast, full) | 1: the KC1 guard grepped `SCR-` in the fragment bundle data under `ux-spike`; the guard now skips that data directory |
| M10 | track C host (view controller, instrumentation, harness, test page) | about 35 min (two xcodebuilds, a log-stream crash from `NSLog` varargs, simulator drive, gate) | shared-ui iosUxSpikeMain 217 (3 files incl. licence headers, hook +4); ConsentHost.swift +12; test page 120 HTML/JS | 0 | 1 pre-spike (`ConsentHost.swift`, T5), 1 spike file (`IosUxSpikeHook.kt`) | 2 (fast 4273, full) | 2: `return` missing in the Swift edit, `NSLog` vararg crash |
| M6a | sync / one-time consumer adoption | 14 min first to last code commit (09:05 to 09:19), about 1 h incl. gate and release check (since M10: 34 min to the first commit) | **one-time adoption (prep + 3 commits): 480 added / 1002 deleted** — ux-contract 11/59 main/test (−411 provisional), ux-theme 20/11 (−389 `ProvisionalEudiwV1`), ux-runtime 74/159 (main 4 = BundleParser; 70 = build.gradle.kts fixture task), ux-spike 72/15, tool 59 (`make-offline-loading-bundle.py`); DEC-075 list proper (commit `19776bbc2`) 154 added / 929 deleted; gate step `uxspec-lock` 33; re-run renames, snapshot and KC1 61/49 | 12 698 lines synced (Kotlin 510 contract + 1 200 theme; bundle, strings, scripts, model, lock) | 0 pre-spike files | 1 full (4279) plus module runs | 0 redone work; 2 small loops: `allStringResources` needs its extension import; the rename script also renamed `ProvisionalNotAllowed` in main sources, reverted before commit |
| M8 | regeneration drills (verification; spec + sync only) | about 1 h 15 min first worktree (10:05) to the last drill (11:18) incl. parallel spec gates; per mutation (spec edit + spec gates + export + sync + full gate + simulator drill, approximate): D8-logo ~16 min (spec gates 7 min 17 s, app gate 5 min 16 s), D8-offline ~25 min (spec gates 6 min 8 s red + 4 min 39 s rerun, app gate 6 min 33 s), D8-brand ~20 min (4 min 33 s, 11 min 33 s), D8-copy ~22 min (4 min 31 s, 8 min 31 s; 3 Maestro tries) | **0 hand-written Kotlin per mutation** (diff outside `ux-spec/**`: 0 lines on all four). Spec LOC (patch +/−): logo +5/−1 (3 files), offline +61/−0 (7 files, 1 new screen), brand +1/−1 (1 file), copy +8/−8 (1 file). Tools only (evidence, not app): `spike-evidence/D8/tools/` (2 Maestro flows ~90 YAML lines, helpers 13 shell lines, pixel sampler 9 Python lines) | generated delta per mutation (`git diff --numstat`, all files / Kotlin): logo +204/−61 / +15/−11; offline +441/−64 / +21/−11; brand +411/−411 / +361/−361; copy +59/−59 / +11/−11 (the −11 Kotlin lines are the regenerated header and SPEC_HASH of the 11 Kotlin files) | 0 | 4 full (all RED, see Gate results) + 5 spec-gate runs | 2 loops: D8-offline renamed its event after E12 collided (RULING-M8-2); D8-copy Maestro flow fixed twice (Maestro refuses absolute screenshot paths; Confirm tapped before the code loaded). No app rework. |
| M8r | **rework** (runtime + elements + tests; remediation after D8 attempt 1) | about 45 min (brief to the baseline commit, incl. one full gate of ~8 min and a spike-module test rehearsal on two D8 exports) | ux-runtime main +44/−10, test +182/−32 (incl. build.gradle.kts +2/−9); ux-elements main +63/−19 (incl. build.gradle.kts +4), test +22/−3; ux-spike main +11/−14 (`PresRequestScreen`), test +17/−2 | 0 | 0 | 1 full (GREEN 4283) + module test runs | **1 rework loop, cause: baseline incompleteness and test pins** (RULING-M6a-6 left the `logo` element gap open until D8; week-0 F3 was missing from the plan; tests pinned the baseline export). Within M8r: 1 guard hit (a `^SCR-` regex in a test KDoc), fixed before commit. |
| M8b | regeneration drills, attempt 2 (verification; spec + sync only) | about 1 h 10 min (12:02 first worktree to 13:10 last drill, plus ~11 min lost to the parallel spec-gate timeout, RULING-M8b-4). Per drill (spec gates + export + sync + full gate + simulator, approximate): **1 logo ~25 min** (spec 5 min 42 s, export 38 s, gate 9 min 41 s, simulator + a11y audit ~9 min), **2 offline ~22 min** (spec 7 min 58 s, gate 11 min 11 s, simulator ~3 min), **3 brand ~14 min** (5 min 55 s, 5 min 50 s, ~2 min), **4 copy ~17 min** (8 min 29 s, 5 min 22 s, ~3 min), **5 unseen ~25 min** (8 min 13 s, 5 min 40 s, simulator ~5 min + old-app runs ~6 min) | **hand-written LOC: 0 on every drill** (no file outside `ux-spec/**` on any `spike/d8b-n`). Spec LOC (patch +/−): 1 +5/−1 (3 files), 2 +61/−0 (7, 1 new), 3 +1/−1 (1), 4 +8/−8 (1), 5 +21/−2 (3, 1 new). Generated delta (`ux-spec/**`, all / Kotlin): 1 +195/−52 / +15/−11; 2 +434/−57 / +21/−11; 3 +401/−401 / +361/−361; 4 +49/−49 / +11/−11; 5 +88/−50 / +11/−11. Evidence tooling (not product): `D8-attempt2/tools/` (2 Python asserts ~90 lines, 2 shell ~45 lines, 1 Maestro YAML) | generated only (see LOC) | 0 | 5 full (4 GREEN 4283/0, 1 RED 4283/2) + 10 spec gate runs (5 timed out in parallel) | 1 loop: spec gates run in parallel timed out (infrastructure, RULING-M8b-4). D8-unseen failed on a test pin (RULING-M8b-6), not reworked (strict D8 rule). |
| M6b | formal iOS drills (verification) | about 1 h 50 min (build, 4 drills with video, D5 mutation suite, gate) | 0 Kotlin; tool: `ios-edge-swipe.sh` +8 (`-t` tap-then-swipe), `m5b-happy.yaml` +7 YAML, `m6b-qr-entry.yaml`, `spike-evidence/M6b/drill-helpers.sh` + `d3-run.sh` (about 45 shell lines) | 0 | 0 pre-spike files | 1 full (4279) plus one Debug xcodebuild | 1 loop: the swipe tool starts slower than the 1.5 s `authenticate` delay, so the first `auth` swipe landed in `verification-code`; fixed in the tool (`-t`), not in the app. A D3 watcher replaced Maestro timing for the same reason |
| M6c | sync / one-time adoption (exporter 0.5.0) | about 25 min first to last code commit (13:16 sync to 13:29 pin fix), about 45 min incl. re-export, gate (5 min 23 s), release check and simulator shots | **one-time adoption (prep + 2 commits): main 436 added / 81 deleted, test 296 / 9** (licence headers of 6 new files ≈ 90 of it). Main: ux-theme 231/8 (`UxSpecResources` 162, expect/actual 39), ux-elements 118/64 (glyphs out, `PartIcon`/`AssetImage`; build 20/6), ux-runtime 8/1, ux-spike 79/8 (resource loading 41, fake declaration 38). Test: ux-elements 159/8, ux-spike 133/0, ux-runtime 4/1 (prep). Data: M5c snapshot −315 lines (deleted). **Pin fix** (separate): test 78/7 | sync 28 554 added / 59 deleted (Kotlin 147/13; 46 drawables) | 0 pre-spike files | 1 full (4300) + module runs | 1 small loop: the new regression test first pinned "the planted rule hides exactly its node", red on the D8-unseen export (its own rule hides `verifier` too); fixed before commit. |
| M5e | elements + templates (Home parts) + probe from the export | about 2 h 45 min (13:35 first read to 16:20 log), of which about 1 h 10 min is simulator shots (56 + 4) and builds; code commits are the last third | ux-elements main 1 703 (parts 1 177, `UxComp` 105, registry +155, app-bar/avatar/lock/title +94, build +172 of which fixture task 112) / test 515 (`HomePartsScenario` 423, support 93, `RegistrySchemaTest` +16); ux-theme 96 main (readers 66, access 27, alias 3) / 56 test; ux-spike 360 main (`ScreenProbe` 218, probe host 79 net, gallery +46, entry +8, build +60 incl. fixture) / 215 test; root 5 (`spike-gate.sh`) | 0 (the probe and tokens are the M6c export; 145 KB of tokens and assets embedded for tests at build time, not counted) | 0 pre-spike files (spike files: 3 existing elements, 4 existing tests, build files, gate) | 3 full-ish (module tests several times, one full gate GREEN 4360) | 2 small loops: (1) the band card collapsed its body without artwork (`weight(1f)` under a minimum height), found on the idsk shots, fixed with a regression assertion; (2) the shots were first taken with the selections missing from `exportedSpecResources()` (centered face and dot mark not applied) |
| M9 | drills (track B, verification; spec + sync only) | about 1 h 40 min (3 spec worktrees, 3 exports, 4 simulator runs, 3 full gates of 6 to 7 min, one spec gate of 4 min 24 s) | 0 Kotlin; tools: `spike-evidence/M9/run-variant.sh`, `drill-helpers.sh` | generated: `ux-spec/` +774/−985 (option 1), +69/−75 (option 2) | 0 | 3 full (4360/12, 4360/2, 4360/0) | 0 rework; 2 test-pin findings (RULING-M9-2) |
| M9r | **rework** (test harness; independent-verifier remediation of D9 = (C)) | about 55 min (15:43 first re-export to 16:36 shot; commits 16:19 to 16:34), incl. one full gate of 13 min 51 s (regen-smoke 8 min 11 s) and two regen-smoke runs before the fix (7 min 28 s all, 2 min 10 s host) | tests +1 573/−955 (ux-runtime +1 288/−630 incl. `SyntheticFlow` 293, `ExportWalk` 237, `ExportedPoliciesTest` 186; ux-elements +181/−141; ux-spike +230/−130; ux-contract +4/−80); main: `TplSheet` +11/−1, `FakePresCrossDeviceServices` +17/−27 (results declared from the generated table); build +91/−17 (5 `build.gradle.kts`: `uxSpecDir`, own build dir, `ux.spec.dir`, scripts fixture); root +71/−3 (`spike-regen-smoke.sh` 55, `spike-gate.sh` 16); tool `reexport-d8.sh` 22 | 0 (5 re-exports archived as evidence, not committed to `ux-spec/`) | 0 pre-spike files | 1 full (GREEN 4360/0, regen 9/9) + 3 regen-smoke runs + module runs | 3 small loops: FlowModelCheck variant claimed every invariant breaks without its guard (only L58 does), recovery-outcome close node picked an unlinked help link, the main-path walk awaited a state a service leaves in one frame |
| M11 | Android drills (verification; no Kotlin change) | about 1 h 55 min (build and install, onboarding, D4, D6, D1 x6, D2 x6, D7, Release isolation check; gate 21 min on top) | 0 Kotlin; tools: `spike-evidence/M11/helpers.sh` (about 35 shell lines), `pin.yaml` +7, `a11y-trace-events.py` +17 | 0 | 0 pre-spike files | 1 full (4360), 1 `assembleDevDebug`, 1 `assembleDevRelease` | 3 loops, all drill timing or tooling, none in the app: (1) `auth` is 1.5 s long and an adb HOME key arrives about 2 s after the tap while `screenrecord` runs, so D2(b) auth hit `verification-code` three times and was repeated without video; (2) `cmd activity kill-all` does not reclaim the wallet, so D2(b) uses root `kill -9` under `memory-factor CRITICAL`; (3) the a11y trace with `IAccessibilityServiceConnection` kept only the last 5 s, `-t IAccessibilityServiceClient` alone keeps all events |

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
| f0c52dc83 | 2026-10-17 | M5d | Home probe: fragment generator, bundles, `gallery-home` hook, KC1 guard data exclusion, coverage, screenshots | Claude Sonnet 5.5 | fast GREEN (4273) |
| (this commit) | 2026-10-17 | M5d | SPIKE-LOG M5d, metrics row | Claude Sonnet 5.5 | full gate (see Gate results) |
| 2da2ca566 | 2026-10-17 | M6a | prep: provisional contract keyed on its directory | Claude Opus 5.5 | module tests |
| 7a24efacf | 2026-10-17 | M6a | **sync commit**: ux-spec/ from ux@3d6ecb6 (spec 88e2b611…02d0, exporter 0.3.0) | Claude Opus 5.5 | lock check |
| 04515c33f | 2026-10-17 | M6a | uxspec-lock gate step + mutation log | Claude Opus 5.5 | lock check, 4 mutations red |
| 19776bbc2 | 2026-10-17 | M6a | one-time consumer changes (DEC-075), provisional contract/theme/copy/bundle deleted | Claude Opus 5.5 | module tests, lint |
| c202d0c68 | 2026-10-17 | M6a | fake engine plays the exported scripts; 8th test fixed per V23 | Claude Opus 5.5 | module tests, lint |
| 6cd58495b | 2026-10-17 | M6a | handshake on the real SPEC_HASH; runtime tests on the export; model replay | Claude Opus 5.5 | all ux tests, lint |
| e1ccc57e3 | 2026-10-17 | M6a | provisional test names dropped; registry snapshot at 3d6ecb6 | Claude Opus 5.5 | all ux tests, lint |
| 909ccf460 | 2026-10-17 | M6a | KC1 guard on the exported bundle | Claude Opus 5.5 | full gate GREEN (4279) |
| 8830b6fe7 | 2026-10-17 | M6b | formal iOS drills, rows, notes, evidence, tool `-t` mode | Claude Sonnet 5.5 | full gate GREEN 4279 |
| (this commit) | 2026-10-17 | M6a | SPIKE-LOG M6a, metrics row, rulings | Claude Opus 5.5 | — |
| ab7011ada | 2026-10-17 | M10 | track C view controller, T5, harness, instrumentation, test page, Q-D10 | Claude Sonnet 5.5 | fast GREEN (4273) |
| (this commit) | 2026-10-17 | M10 | SPIKE-LOG M10, metrics row, results row | Claude Sonnet 5.5 | full gate (see Gate results) |
| f223fad7e, 0541d2e5e, c12e0e2ca, 8fd57b22d | 2026-10-17 | M8 | D8 sync commits on `spike/d8-3`, `-4`, `-2`, `-1` (ux-spec/ only; branches deleted after the evidence was kept; diffstats in `spike-evidence/D8/<n>/`) | Claude Opus 5.5 | full gate RED ×4 |
| (this commit) | 2026-10-17 | M8 | D8 evidence, four D8 rows, SPIKE-LOG M8 | Claude Opus 5.5 | docs only |
| bc2301ca5 | 2026-10-17 | M8r | M8r-1 tests un-pinned (counts, script names, added-state copy from ux-spec/ at test time) | Claude Opus 5.5 | module tests, lint |
| 32be5d22a | 2026-10-17 | M8r | M8r-2 `EL-rp-identity.logo` in `UxRpIdentity` and the registry | Claude Opus 5.5 | module tests (host + iOS), lint |
| 2487ff3c3 | 2026-10-17 | M8r | M8r-3 `ScreenScope.Node` (week-0 F3); `PresRequestScreen` draws rp-identity nodes through it | Claude Opus 5.5 | full gate GREEN (4283) |
| (this commit, tag `d8-attempt2-baseline`) | 2026-10-17 | M8r | SPIKE-PLAN §0.2a/§1.4/§3/§5, SPIKE-LOG M8r, results placeholders; **D8 attempt-2 baseline** | Claude Opus 5.5 | docs only (code tree = `2487ff3c3`) |
| (this commit) | 2026-10-17 | M8b | D8 attempt 2: evidence `spike-evidence/D8-attempt2/`, five attempt-2 rows, SPIKE-LOG M8b; drill branches `spike/d8b-1..5` (`a89e594bd`, `bb82450b1`, `ae755998e`, `236ad3417`, `075c2be9d`) deleted after the evidence was kept | Claude Opus 5.5 | docs and evidence only |
| 0d3c9f201 | 2026-10-17 | M6c | prep: CopyResolverTest reads the override's own resource name (RULING-M6c-2) | Claude Opus 5.5 | ux-runtime tests on both exports, lint |
| bad2c1871 | 2026-10-17 | M6c | **sync commit**: ux-spec/ from ux@b55be65 (spec 845b7121…29f2, exporter 0.5.0, services contract 1.0.0) | Claude Opus 5.5 | lock check, spike module tests |
| 348a025fc | 2026-10-17 | M6c | adoption: drawables, ux-assets/ux-library/ux-tokens wired; glyph placeholders replaced; RegistrySchemaTest on ux-library.json | Claude Opus 5.5 | spike module tests, lint, guards |
| df6ed44d1 | 2026-10-17 | M6c | adoption: the fake implements PresCrossDeviceResultDeclaration; conformance test + proof | Claude Opus 5.5 | spike module tests |
| 2bd7a5e9a | 2026-10-17 | M6c | InterpreterScenario evaluates the screen rules (D8-unseen pin, RULING-M8b-6) + regression test | Claude Opus 5.5 | full gate GREEN (4300) |
| (this commit) | 2026-10-17 | M6c | SPIKE-LOG M6c, metrics row, rulings; D8-unseen row note; evidence | Claude Opus 5.5 | docs and evidence only |
| 79ac208e4 | 2026-10-17 | M5e | Home parts: EL-status-chip, EL-document-card, EL-bottom-bar, EL-segmented-switch, EL-trust-mark, TPL-home; `comp.*` readers; app-bar gaps; avatar and lock icons; registry, schemas, tests | Claude Sonnet 5.5 | module tests (host), lint, guards |
| 82ce17ba3 | 2026-10-17 | M5e | the Home probe reads the exporter probe (gating, oracle); M5d fragments removed; gallery `parts` page; guard exclusion deleted | Claude Sonnet 5.5 | full gate GREEN (4360) |
| (this commit) | 2026-10-17 | M5e | SPIKE-LOG M5e, metrics rows, evidence `spike-evidence/M5e/` | Claude Sonnet 5.5 | docs and evidence only |
| 0f5d5fbdb | 2026-10-17 | M9 | D9-brand: ux-spec sync, default_brand idsk | Claude Sonnet 5.5 | full gate RED (4360/12) |
| bbc37a14a | 2026-10-17 | M9 | D9-sheet-page: ux-spec sync, decision-steps page | Claude Sonnet 5.5 | full gate RED (4360/2) |
| 997f47a26 | 2026-10-17 | M9 | baseline ux-spec restored; baseline shots | Claude Sonnet 5.5 | full gate GREEN (4360) |
| (this commit) | 2026-10-17 | M9 | SPIKE-LOG M9, spike-results D9 rows, evidence | Claude Sonnet 5.5 | docs and evidence only |
| a652b1565 | 2026-10-17 | M9r | regen-smoke step, `uxSpecDir`, D8 re-exports, proof red before the fix | Claude Opus 5.5 | regen-smoke red (proof) |
| 069e596ac | 2026-10-17 | M9r | test-pin sweep (rule T1): synthetic bundle, ExportWalk, ExportedPoliciesTest, fake declarations | Claude Opus 5.5 | module tests green; regen-smoke host 9/9 |
| 391edd84e | 2026-10-17 | M9r | TplSheet sheet top safe-area inset; shots; gate log | Claude Opus 5.5 | full gate GREEN (4360/0, regen 9/9) |
| (this commit) | 2026-10-17 | M9r | SPIKE-PLAN §0.2b (rule T1), SPIKE-LOG M9r, spike-results D9 rows | Claude Opus 5.5 | docs only |
| (this commit) | 2026-10-17 | M11 | formal Android rows (D1, D2 a/b-approx, D4, D6, D7), SPIKE-LOG M11, HUMAN-QUEUE Q-D2-LMK and Q-D7-Android, evidence `spike-evidence/M11/` and `M11-prep/` | Claude Sonnet 5.5 | full gate GREEN 4360 |

## M5e notes (Home parts, OR-8; a probe, not counted toward the verdict)

Built from the export only (`ux-tokens.json`, `ux-library.json`, `ux-assets.json`; the spec YAML and `base.css` read as the reference, never copied). Every part
reads its values through `UxComp("comp.<group>")` by exact key; the shell's `sys.*` role is the fallback when the table has no key. Style guard: 0 hits, nothing
allowlisted. KC1: 0 hits (the guard's M5d data exclusion is gone). No pre-spike file touched.

**Per-part cost** (feeds the dashboard estimate). Main = the part's file(s); registry/schema = adapter and schema lines in `UxElementRegistry` (estimated from the diff:
155 added lines incl. 15 of imports and helper); token lines = `comp.*` read sites in the part; export tokens = keys in the group (eudiw_v1 light; 0 hand-written
token values, 0 generated Kotlin); test = the part's section of `HomePartsScenario` (shared scenario, run on both targets). Wall time per part was not measured (one
session, parts interleaved); the share of the 2 h 45 min is about 10 % chip, 25 % card, 10 % bar, 10 % switch, 7 % trust mark, 13 % template, 5 % app bar and icons, 20 % probe.

| Part | Main LOC | Registry + schema LOC | Token read sites | Export `comp.*` keys | Test LOC | Notes |
|---|---|---|---|---|---|---|
| EL-status-chip | 138 | 8 | 14 | 29 | 62 | mark `icon`/`dot`, ring, tone map, decorative mode |
| EL-document-card (full band + centered, mini, hero, holder) | 504 | 39 | 70 | 91 | 83 | four variants, two card faces, inactive art, one-button semantics |
| EL-bottom-bar | 168 | 28 | 19 | 22 | 42 | list `tabs`, `select`/`action`, presence flag for the template |
| EL-segmented-switch (segmented, chips) | 205 | 26 | 29 | 34 | 36 | radio group, accented/plain, disabled with reason |
| EL-trust-mark | 136 | 21 | 13 | 17 | 31 | link or static, official art or placeholder |
| TPL-home | 179 | 18 | 13 | 42 (`comp.screen`) | 42 | ten regions, band title, insets, floating actions |
| App-bar gaps (padding, column gap, centred title, ringed badge) | +25 | 0 | 13 | 17 | 18 | |
| Avatar (`icon.apartment`) + lock (`icon.lock`) | +55 | 0 | 6 | rp-identity 41, attribute-item 36 | 14 | requirement tag drawn as a pill with the lock |
| Shared: `UxComp`, asset fill, hooks | 105 | 0 | n/a | n/a | 93 (support) | |
| Theme readers (`color`, `sp`, `weight`, `shadow`, selections) | 96 | 0 | n/a | n/a | 56 | `:ux-theme` |
| Probe (`ScreenProbe`, host, selection) | 297 | 0 | n/a | n/a | 169 + 2 platform shells | `:ux-spike` |

Sum of the six new parts: 1 330 main, 142 registry/schema, 158 read sites, 235 export tokens, 296 test: about 220 main LOC per part against the M5d estimate of
about 160 (940 for the parts plus icons), because each part needs its brand variants and its token reads.

**Probe.** `--ux-spike gallery-home[-<ui state>][.<brand>][.hc]` builds a one-state bundle from the export's home probe (`ScreenProbe`): context examples overridden by the
ui state's data, the brand's gating, the probe's rules, copy table and a11y block as exported, the spec hash of the probe through the normal handshake (flow id, spec hash, theme
hash). The visibility oracle (`expected.<brand>.<ui state>.visible`) is a test on both targets: for all 14 ui states of eudiw_v1 the nodes the generic host draws are
exactly the oracle's set, and for idsk the oracle minus the omitted bar (`ProbeOracleScenario`). The M5d fragments, their generator (marked SUPERSEDED) and the guard
exclusion are gone; `gallery-parts[.brand][.hc]` is a gallery page for the parts the probe never draws.

**Evidence** `spike-evidence/M5e/`: `shots/` (56 probe screens + 4 parts), `spec-ref/` (the library's renderer, same names), `coverage.md` (visible differences, brand findings), `tools/`.
Visible differences in short: upper-case and letter-spaced card titles are not drawn (CSS-only values), the webfont is not bundled, links carry the library chevron, frosted band
and PID gradient are approximated, idsk lacks switch, trust mark and present button (the probe carries only eudiw_v1's gating) and several icons and the card art (not bound for idsk).

**Findings for the exporter** (also in `coverage.md`): (1) per-brand `hidden` over the unfiltered node list for every exported brand; (2) `trust-mark.colour` unbound; (3)
tokenise the CSS-only values: card aspect ratio, `letter-spacing`, title case, inactive art opacity, band alpha, badge ring width; (4) brand selections in `UxBrandConfig` (or
accept `ux-library.json` as the source, as done here); (5) `ux-tokens.json` has no dark modes, so the parts follow the resolver fallback.

**Remaining gaps**: card `synthetic` art emblem, mini rows grouped into one card, the chip row's dashed disabled outline, chips scroll padding, `popup`-style expanded state of the bar action
(Compose has an expand action only), per-item `requirement_label` (M5c), dark modes, the Source Sans webfont, `icon.arrow-back-ios`/`icon.help`/`icon.search` (unbound).

## M10 notes (track C, DC-API extension probe)

- `UxDcApiSubflowViewController(request, onDecision)` (`shared-ui/src/iosUxSpikeMain/.../ios/`) starts Koin itself (same call as
  `DcApiConsentViewController`), hosts the generic `UxFlowEntry` (FlowHost + interpreter, fake engine, script from the app-group
  switch, `happy` by default) with a no-op back handler. `ConsentHost.swift` (T5) calls it when `uxDcApiSubflowEnabled()`, which is the
  existing app-group `ux-spike` default (`--ux-spike <script>`); import and branch are inside `#if DEBUG`. Switch off: the extension
  is unchanged. Release isolation: the gate's Release build and `strings` check (see Gate results).
- **Ruling M10-1 (conservative):** the sub-flow enters at `deeplink` (bundle entry, `connecting` then `request` ...) since the bundle has
  no entry that starts at `request`; the 4 s `connecting` state is part of the measured time. The extension's request is not mapped into
  the fake engine (it plays the script). **Ruling M10-2:** `onDecision(null)` (refusal) on every exit, so no wallet data leaves the
  device through a fake consent; Safari sees `NotAllowedError`. **Ruling M10-3:** the flag is the existing `ux-spike` default, no second key.
- Instrumentation: `NSLog` lines tagged `UX-SPIKE-D10` (`println` does not reach the unified log in an extension): `created`,
  `first-frame` (`start_to_first_frame_ms`, from view-controller creation to the first frame callback after composition, not process
  launch), `complete`, each with `phys_footprint` in MB (`task_info` TASK_VM_INFO) and `wall_epoch_ms`, `host=extension|in-app`.
  First attempt used `NSLog("%@", kotlinString)` and crashed (Kotlin String is not an ObjC vararg object); fixed by passing the message as the format.
- Harness (simulator, preliminary, not D10): `--ux-spike dcapi-harness` embeds the same view controller in the app (`UIKitViewController`)
  with a scripted PID request, and shows a result line on completion. Rendered at first frame (79 ms creation to first frame, 69.9 MB;
  64.4 MB at creation), completed after Continue and Confirm (outcome `Done`, 85.9 MB at completion), then `decision=refused`.
  Evidence `spike-evidence/M10/` (7 screenshots, 3 videos, `harness-d10-log-excerpt.txt`). The harness shows the fake script's request
  screen, not the scripted request's claims.
- Not covered by the harness: extension process, ExtensionKit scene, no `NavDisplay`, Koin start in a cold process, real DC API delivery.
- Test page: `spike-evidence/M10/dcapi-test-page/index.html` (+ README): builds a CBOR DeviceRequest and EncryptionInfo in the page; not run
  against a device (HTTPS and iOS Safari DC API needed). Device steps: Q-D10.

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

## M5d notes (Home-screen probe, OR-1; not counted toward the verdict)

Spec id: **SCR-home** (`ux/experience/screens/SCR-home.yaml`, template TPL-home, brief BRF-006, referenced by
`FLW-dashboard` states `home*`). 28 nodes, 15 ui_states, 19 rules, 9 distinct elements. The product profile PRF-eudiw-v1
turns the storage switch and the Trust Mark entry off and the bottom-nav variant `bar` replaces the present button, so the
wallet-as-designed draws 26 of the 28 nodes. Full table: `spike-evidence/M5d/coverage.md`.

How it was built. `spike-evidence/M5d/tools/make_home_fragment.py <ux> <out>` (beside the M1b generator's approach, read-only on
the spec) writes one single-state fragment bundle per ui_state (profile applied) plus `full` (every node) and `high-contrast`.
Context is the screen's `data_model` examples as static values, overridden by the ui_state's `data`; every emitted event is a
stay-in-place branch so nodes are enabled; no services. `UxHomeProbe` (`:ux-spike`) loads
`files/home-probe/home-<name>.bundle.json`, hashes taken from the file, and hosts it with the generic `FlowHost` (no engine
or screen code added). `--ux-spike gallery-home` is the default state, `gallery-home-<ui state>` any packaged one
(default, offline, empty, dl-expiring, id-revoked, notifications-unread, full, high-contrast); it is checked before the
template gallery. Rules (`hide` effects) and ui_states needed no runtime change.

Result (iPhone 17 simulator, light; HighContrast also drawn): the screen is drawn from bundle data with no crash. The app bar,
title, heading, links and (offline) notices are real elements with brand copy ("eDoklady", "Moje eDoklady", "Všetky doklady",
"História", the offline notices); the template, the cards and the bottom bar are tagged placeholders. A missing part does
**not** draw the M5c `ux-error.<id>` node: the registry has no entry, so it draws the M5b tag (`ux-unknown-element.<id>` /
`ux-unknown-template.TPL-home`). The `ux-error` node is the schema-violation path only. Both are visible and logged, neither
crashes. A flow-level screen the registry cannot draw degrades to a readable list, which is the measurement we wanted.

Coverage (10 parts: 9 elements + TPL-home): **present 3** (EL-text, EL-link, EL-notice), **partial 2** (EL-app-bar: no icon
assets, menu and bell are text glyphs, the bell draws as a note; EL-button: `qr-code` icon is a placeholder glyph),
**missing 5** (TPL-home, EL-document-card, EL-bottom-bar, EL-segmented-switch, EL-trust-mark), plus the implicit EL-status-chip
inside the card and the icon/art asset table (declared gap since M5). Estimate to close Home: about 940 main LOC for the parts
(TPL-home 190, document-card 330, status-chip 90, bottom-bar 140, segmented-switch 120, trust-mark 70), about 150 icons and
art, about 100 `comp.*` tokens in four modes: about 1.2 k main, about 0.5 k test, about 1.7 k in total (calibration: M5 was
about 115 main LOC per part, M5c about 30 token lines per part). Comparable app components exist for the bottom bar
(`BottomNavigation.kt`), the chip (`WrapChip`), notices (`InlineSnackbar`, `AcknowledgeWarningCard`); none for the art
document card (the app lists documents as `WrapListItem` rows), the segmented switch (`WrapSwitch` is a toggle) or the
template (a scaffold, not a template).

Implication for the dashboard estimate. The 19 screens of FLW-dashboard use 7 templates and 18 elements; the registry has
1 template (TPL-outcome) and 7 elements (button, app-bar, text, notice, status-hero, link, attribute-list). Missing: 6
templates (dialog, list, task, detail, static, home) and 11 elements (list-item, document-card, trust-mark, dialog,
segmented-switch, status-chip, action-menu, action-item, text-input, snackbar, bottom-bar). Home is 5 + 1 of those 17, with
the heaviest one (document-card, used by 3 screens). At the Home rate (about 1.7 k per 6 parts incl. icons) the dashboard
needs roughly 17 parts, about 4 to 5 k LOC main plus test and the icon table, before any screen with a flow of its own
(states, services, `invoke`s for 173 states) is wired. Nothing in the probe contradicts the engine or interpreter design:
no engine, interpreter or contract change was needed, only parts.

Findings.
1. **Profile gating is not in the bundle.** `features.*.on` (profile) and the variant axes (`bottom-nav`, `card-face`) change
   which nodes exist; the generator applied them. The exporter (week 0) must either filter nodes or emit the profile and a
   rule; the interpreter has no notion of a profile.
2. **Rule-driven visibility works unchanged** (hide of 11 nodes at `empty`, notices at `offline`, the dl-expiring card).
3. **Missing parts and the `ux-error` node**: if a "no silent gaps" check is wanted at load time, `SchemaCheck.problems`
   would have to report unknown elements and templates too (today it reports schema violations only). Not changed.
4. **Region stacking without a template**: the unknown-template placeholder draws the regions unpadded, unscrolled and in
   order; it is a debugging aid, not a layout.
5. **`CPY-home-title`** resolves through `brand_overrides` as designed ("eDoklady" over "Moje eDoklady").
6. **Heading level**: the screen's h1 is the title node; the heading row uses `heading` (h2). Compose cannot express the level (M5).

Trivial prop additions: none were needed. Gate: the first fast run was RED on the KC1 guard (it grepped `SCR-` in the fragment
bundle data), fixed by excluding `ux-spike/**/composeResources/files/home-probe/**` from the `SCR-`/`"pres-` grep only; code
paths stay scanned (`spike-gate.sh`, 3 lines).


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

## M6a notes (first real export; boundary §6 re-check)

**Input.** `dvm-week0/ux/build/kmp-export/ux-spec`, clean export of ux@`3d6ecb6` (dvm-week0 HEAD `874b4f9` differs only in
two docs; all 77 lock sources re-hashed equal to the tree, so not stale; no regeneration). Copied verbatim
(`diff -r` empty) in sync commit `7a24efacf`. **Lock check** (`spike-gate.sh` step `uxspec-lock`): 18 files match sha256
and bytes, none missing or unlisted, lock `spec_hash` = bundle = `PresCrossDeviceSpec.SPEC_HASH` = `UxThemeSpec.SPEC_HASH`,
`ux_dirty` false. Mutations (edit, lock hash, unlisted file, dirty) each red: `spike-evidence/M6a/uxspec-lock-mutations.log`.

**Shape diff** (boundary §6.2, week-0 `tests/kmp_paths.py`, ids folded): 197 provisional paths, 231 exported, 195 same;
2 absent + 36 added, every one listed in `divergences.yaml` (V4, V5, V6, V8, V12, V14–V19, V30, V31), none unlisted, none
stale: `spike-evidence/M6a/shape-diff.txt`. **API diff** (§6.3): 120 provisional declarations, 165 generated, 106 same name;
the differences are exactly V14 (nullable verifier fields, `documentName`), V25 (`PresCrossDeviceEvent`, `Sys*`), V26
(`VerifierData`/`VerifierPayload`, `RequestAttributeData`/`…Payload`, enum `Channel`, alphabetical constructor order):
`spike-evidence/M6a/api-diff.txt`. The export passed detekt and ktlint in the gate (0 findings, as F4 said).

**Divergences found by re-running every provisional assertion** (classification of boundary §6.5; all (a), none (b) or (c)):

| # | V | Found by | Resolution |
|---|---|---|---|
| 1 | V25/V26 | `InvokerDefaultsTest` did not compile (`Verifier`, `RequestedAttribute`, positional `(verifier, request)`) | named arguments and the generated names (RULING-W4) |
| 2 | V14 | `PresRequestScreen` (`verifier.name` nullable) | `.orEmpty()` |
| 3 | V12 | the loader refused `services.send.args.selection` in object form | `BundleParser.argType` keeps a scalar or the object's JSON text |
| 4 | V9/V27/V29 | the generated theme fills a 34-role `UxColors`; resources now from `ux-spec/resources` | `UxColors` +9 roles; `customDirectory`; probe file dropped (RULING-M6a-2) |
| 5 | V19 | `UxTokensShellTest` asserted four distinct modes | the export binds light and high-contrast; dark falls back (test rewritten to assert the fallback) |
| 6 | V15 | copy text came from `_sk` annotations (app and tests) | app: `Res.allStringResources` → `CopyProvider`; tests: strings.xml embedded by the fixture task |
| 7 | V23 | `FakeScriptsTest` test 1 (the 8th): `doc-missing` scripts only `fetchRequest` | in the test: authentication is checked only where scripted; the fake throws for an unscripted service (RULING-2 path) |
| 8 | — (new, informational) | `StateChangeA11yScenario` took a node's *first* copy prop as its h1 text; the export writes props in sorted key order (`text` before `title` in EL-status-hero) | the check matches any of the node's copy texts. No runtime change; not a boundary break (the boundary never promised prop order). Candidate note for week 0's §6 table, no exporter change |
| 9 | X15 | `RegistrySchemaTest` against the 3d6ecb6 library: `EL-rp-identity.logo` | GAP until M8 D8-logo (RULING-M6a-6) |

Everything else held unchanged on the real bundle: H1–H12 (H7 by a planted prefix), the M3 engine tests (async, semantics,
L58 model check incl. the pre-fix counterexample, RULING-2, back/restore, V21 now closed by RULING-W2, V31's constant `set`),
M4 host and P4 round trips (both targets), the M5b interpreter incl. the bundle-only render and the added-state copy
(re-derived from the export: `spike-evidence/M6a/make-offline-loading-bundle.py`), M7 a11y, M5c schema check (0 problems on
the export) and the token shell values (light, high-contrast, elevation, fonts, `comp.*`), the six exported scripts through
the real engine, and the new model replay (84 sequences). **No exporter change is needed** and no generated file was edited.

**KC1.** The guard reads its 25 state names from `ux-spec/…bundle.json`: 0 hits; a planted state literal turns it red
(`spike-evidence/M6a/kc1-guard-mutation.log`). `RuntimeLiteralGuardTest` now checks `:ux-runtime` against the real bundle:
green. No screen-specific code was added; the only screen file touched is `PresRequestScreen.kt` (one `.orEmpty()`). No KC1
signal.

**⟲** `git fetch origin feature/kmp`: still `b23f6a1f5`, no rebase. No pre-spike file touched by M6a.

## M6b notes (formal iOS drills on the real export)

Build: Debug of `ac563c8db` (`xcodebuild … -derivedDataPath DerivedData`), installed on iPhone 17 / iOS 27.0, `simctl ui appearance light`,
`-AppleLanguages "(sk)" -AppleLocale sk_SK` on every launch (the wallet's own screens stay English: the app ships no sk resources;
the spike screens show the generated strings.xml copy in Slovak). Helpers: `spike-evidence/M6b/drill-helpers.sh`, `d3-run.sh`.
All five drills passed; no KC2 signal (0 of 3 hard states needed a Back or restore handler outside the bundle policy).

| # | Ruling or finding | Resolution |
|---|---|---|
| RULING-M6b-1 | D6 happy path with the exported `happy` script draws neither `connecting` nor `sending` (no `delay_ms`, M6a), so "every screen from the bundle" cannot be shown with it. | The formal happy run uses script `slow` (same data plus delays); `happy` was run first and reached the code screen. |
| RULING-M6b-2 | The exported code is `482 913`, the provisional `4711` was asserted in `m5b-happy.yaml`. | Flow asserts `482 913` (tool file only). |
| RULING-M6b-3 | `auth` (1.5 s) and `sending` (3 s) are shorter than the swipe tool's startup, and Maestro adds seconds. | D1: `ios-edge-swipe.sh -t <id>` taps inside the same XCUITest and swipes at once. D3: a log watcher kills the app the moment the state's marker appears in the app log. Both are tools, no app change. |
| RULING-M6b-4 | A URL cold start cannot capture stdout. | D4 link consumption is shown by Maestro (connecting asserted, then request, no restart), the video, and one `start deeplink` per link in the D3 relaunch logs. QR entry: "Authenticate > Online" (`QrScanRoute`, Presentation) redirects to state `scan`; no camera on the simulator, so no real code was scanned. |
| RULING-M6b-5 | D5 "read-only" vs the mutation suite writing temp trees. | `run_model_check.py` run as is (`PYTHONDONTWRITEBYTECODE`); `run_mutations.py` run with `UX_MUTATION_TMP` outside the repo; dvm-week0 `git status` stayed clean. |
| Finding | macOS `git grep -E` does not support `\b`: the plan's D1 pattern finds nothing. | Run with `-P` (same regex), recorded in `logs/d1-grep.log`. Candidate fix for the plan text. |
| Finding | The M4 XCUITest edge swipe is reliable on iOS 27 here: it reaches FlowHost's one adapter in all three states. | none |

## M8 notes (D8 regeneration drills)

**Baseline.** App `93c2b9931` (= M6b; ux-spec = export of ux@3d6ecb6), spec `dvm-week0` `874b4f9` (exporter 0.3.0). ⟲
`git fetch origin feature/kmp`: `b23f6a1f5`, unchanged, no rebase. Pre-D8 grep (`logo_url|logoUrl|idsk` outside ux-spec, md,
evidence) on `93c2b9931`: **0**. During M8 `spike-week0` moved to `445e38c` (batch 5.x, exporter 0.4.0, another lane); the drills
stayed on `874b4f9` as briefed. Evidence and reproduction: `spike-evidence/D8/README.md`.

| n | Spec change (worktree of 874b4f9, never committed) | Spec gates | Diff outside ux-spec | Gate | Behaviour | Row |
|---|---|---|---|---|---|---|
| 1 logo | `/verifier/logo_url` in context, fetchRequest payload, request data_model, fixture; `logo` bound on `rp-trusted` | check green; test: E12 red (RULING-M8-3) | 0 | RED 6 | not changed | failed |
| 2 offline | result `Offline`, event `SYS_NETWORK_LOST`, state `offline-loading`, screen SCR-pres-offline-loading, 4 copy keys, script `offline` | green (2nd try) | 0 | RED 7 | changed | failed |
| 3 brand | `kmp.yaml` `default_brand: idsk` | green | 0 | RED 4 | changed | failed |
| 4 copy | 5 strings (4 interpreted, 1 consent) | green | 0 | RED 4 | changed | failed |

**What the drills show.** The regeneration path itself held for three of four changes: one sync commit of `ux-spec/` only,
lock verified, detekt/ktlint 0 on the export, Debug and Release builds green, and the new state, brand and copy appear on the
simulator with no Kotlin change. The fake engine needed no change (the `offline` script is exported from the SVC fixtures; OR-3
did not have to be invoked). The runtime and the interpreter drew the new screen, the new brand and the new copy from data.

**Why every row is still failed (RULING-M8-5).** The full gate is part of the KC1 procedure and it is red on every branch, because
spike *tests* pin the baseline export: the M6a added-state TEST fixture (a hand-derived copy of the baseline bundle, refused by
spec hash after any re-export), and in D8-offline the state count and script-name set. These are test-side pins, not
screen, runtime-main, element or fake-engine code, so they are not KC1 product signals; but making the gate green needs an edit
outside `ux-spec/`, which the D8 rule does not allow. Fix candidate for the owner: derive the added-state fixture at build time from
`ux-spec/` and read counts from the bundle; then re-run D8 from a new named baseline.

**KC1 signal (D8-logo), for independent re-derivation.** The logo is not drawn. Showing it needs (1) an `EL-rp-identity` image
capability in `:ux-elements` (`UxRpIdentity` has no image parameter or loader; the registry schema rejects `logo`, which is what
turned `SchemaCheckTest` red: an **element capability gap**, so D8-logo failed), and (2) an edit to the hand-written consent screen
`PresRequestScreen.kt`, which builds `UxRpIdentity` from the typed `PresRequestData` and ignores the new `verifier.logoUrl`: a
**hand-edit in screen code for a regeneration** (brief §1 goal, §8 KC1; the M8 brief classifies screen code as a kill signal). The
consent screen is hand-written by design (brief §6.6), so any new field on SCR-pres-request will need such an edit; whether that
counts against KC1 or is an accepted cost of §6.6 is for the independent check and the owner. M8 stopped here (D8-logo was the last
drill, RULING-M8-1); no Kotlin was edited. Also: the library declares `logo` decorative (alt empty), so the brief's "logo with an
accessibility label" conflicts with the spec.

**Real `Services` adapter (OR-3) cost per row.** No adapter exists yet (M11b). Logo, brand, copy: no compile break (new fields
have defaults). Offline: a real adapter mapping app failures to `FetchRequestResult` would compile, but an exhaustive `when` over the
result (if any) would gain a branch; recorded as the expected cost.

**Spec-side findings for week 0.** E12 hard-codes the names of the four D8 changes, so it goes red on a tree that already contains
one of them (logo) or reuses a name (offline, `SYS_OFFLINE`). G2 reads an example URL ending in `.png` as an asset path (DEC-014).

**Clean-up.** Branches `spike/d8-1..4` deleted (SHAs above), spec worktrees `/Users/roman/code/dvm-d8-1..4` removed, scratch
exports in `$TMPDIR/d8` only (archived as `export.tar.gz`). A stray app process had appended 8 lines to the tracked
`spike-evidence/M6b/logs/d4-qr.log`; restored from the index.


## M8r notes (remediation after D8 attempt 1)

**Trigger.** An independent verifier ruled (B), **KC1 not hit**: D8 attempt 1 failed on all four rows for remediable
reasons (`spike-evidence/D8/README.md`). Attempt 1 stays on record as **failed** with its causes; its four rows in
`spike-results.md` are unchanged. All M8r work is generic and lands before the attempt-2 baseline. No pre-spike file, no
generated file, no fake-engine file touched; the wallet logic is untouched.

| Item | Commit | What changed | LOC (+/−, main / test) |
|---|---|---|---|
| 1 un-pin the tests | `bc2301ca5` | `HandshakeTest.h1`: state and screen key sets read from the exported JSON (was 25 / 16), hash and load checks kept. `FakeScriptsTest`: script names from `UX_SPEC.lock` `fixtures/scripts/` (was six pinned names). `SchemaCheckTest` and `InterpreterScenario` test (ii): `AddedStateFixture` builds the added-state copy at test time from the embedded export, with `_test` names no export can carry (RULING-M8r-4); the `offlineLoadingBundleFixture` Gradle task (pinned M6a TEST.json, pinned sha) is gone. `SchemaCheckTest`'s live export-vs-registry check kept. | gradle +2/−9; test +134/−23 |
| 2 `EL-rp-identity.logo` | `32be5d22a` | `UxRpIdentity(logo)`: a non-empty URL is drawn as an avatar (48 dp = `size.target` = library avatar-size) with Coil 3 `AsyncImage` (`libs.coil.kt.compose`, already in the catalog and in `:shared-ui`; network fetchers come from the app), `contentDescription = null`, semantics cleared and `hideFromAccessibility()`; empty/absent draws nothing (RULING-M8r-3). Registry maps and accepts `logo`; RULING-M6a-6's GAP entry removed from `RegistrySchemaTest` (RULING-M8r-1). New semantics test (host + iOS). | main +63/−19 (incl. gradle +4); test +22/−3 |
| 3 week-0 F3 | `2487ff3c3` | `ScreenScope.Node(id)` (ux-runtime): renders a node of the screen's tree by id through the interpreter (`RenderNodeById` → `ScreenTree.node`), with bindings, copy, rules, schema check, node a11y; unknown id draws nothing and is logged. `FlowScreen` builds one `ScreenFrame` for both paths. `PresRequestScreen` draws `rp-trusted` / `rp-untrusted` through it (no verifier fields read for the identity); snapshot unchanged. New interpreter test: a `logo` planted on the consent tree's EL-rp-identity nodes is drawn, decorative, through an unchanged custom screen; exactly the rule-selected node draws. | main +55/−24; test +48/−0 |

**Logo grep.** `git grep -n -i -E 'logo_url|logoUrl|idsk' -- ':!ux-spec' ':!*.md' ':!spike-evidence'` → **0** (also with
`--untracked`). The prop is `logo`, as the library names it.

**Rehearsal disclosure.** Before the gate, the spike-module tests (`:ux-runtime/:ux-spike/:ux-elements/:ux-contract/:ux-theme
allTests`) were run once with `ux-spec/` replaced by the D8-1 and D8-2 attempt-1 exports: both green. `ux-spec/` was then
restored from git (and the stray `fixtures/scripts/offline.json` removed). This is why attempt 2 is declared not blind for the
four known drills (RULING-M8r-6).

**Housekeeping.** A still-running simulator app appended lines to the tracked `spike-evidence/D8/1/logs/d8-1.log` during M8r;
restored from the index, not committed. `spike-evidence/M11-prep/` (untracked, another lane) left alone.


## M8b notes (D8 attempt 2, plan M8-2)

**Baselines.** App tag `d8-attempt2-baseline` = `6946d80c5` (code tree `2487ff3c3`); spec `dvm-week0` `874b4f9`
(RULING-M8b-1), exporter 0.3.0, as attempt 1. No rebase. Pre-D8 grep (`logo_url|logoUrl|idsk` outside ux-spec, md,
evidence): 0 (kc1-guard green on all five branches). Attempt 2 is **not blind** for drills 1-4 (patches public; the
M8r agent ran the module tests once on attempt-1 exports, RULING-M8r-6); drill 5 is **unseen** (chosen by a separate
agent; its README and patch are copied unchanged to `D8-attempt2/5-unseen/`). Reproduction: `spike-evidence/D8-attempt2/README.md`.

| n | Spec change | Spec gates | Diff outside ux-spec | Gate | Behaviour | Row |
|---|---|---|---|---|---|---|
| 1 logo | `logo_url` bound to `EL-rp-identity.logo` | check green; test: E12 only (RULING-M8b-3) | 0 | GREEN | changed: hidden image node, name intact | **passed** |
| 2 offline | state `offline-loading`, new screen, script | green | 0 | GREEN | changed | **passed** |
| 3 brand | `default_brand: idsk` | green | 0 | GREEN | changed | **passed** |
| 4 copy | 5 strings | green | 0 | GREEN | changed | **passed** |
| 5 unseen | `verifier` notice + hide rule on SCR-pres-outcome-expired | green | 0 | RED 2 (test pin) | changed (conditions 2-6 hold; old app fails 2+3) | **failed** |

**What it shows.** The M8r remediation closed the three attempt-1 causes for the known drills: the un-pinned tests read
states, scripts and the added-state copy from `ux-spec/`, the element draws `logo`, and the consent screen draws its
rp-identity node through `ScreenScope.Node` (the logo reached the hand-written screen with no screen edit). The blind
drill shows the product path also holds for an unforeseen change (new node, new copy, new rule on an interpreted
screen: runtime, registry and fake engine untouched), but it found one more test-side assumption: the interpreter
scenario expects every node of an interpreted screen to be drawn, so a spec rule that hides a node in the test's
context turns the gate red (RULING-M8b-6). Not a KC1 signal (classification: test pin). KC1 step 9 (review of
`:ux-runtime`): no runtime file changed on any branch, so there is nothing new to review beyond M8r.

**Clean-up.** Branches `spike/d8b-1..5` deleted (SHAs above); spec worktrees `/Users/roman/code/dvm-d8b-1..5` removed (the
throwaway spec commits are unreachable; SHAs in `<n>/spec-commit.txt`); exports kept as `export.tar.gz`.
`/Users/roman/code/eudi-kmp-device` and `spike-evidence/M11-prep/` not touched.

## M6c notes (sync of exporter 0.5.0, one-time adoption)

**Input.** `dvm-week0` `b55be65` (clean), regenerated per RULING-M6c-1; copied verbatim (`diff -r` empty) in `bad2c1871`.
Lock check: 70 files, spec `845b7121…`, ux@`b55be659455f`, exporter 0.5.0. Flow bundle unchanged apart from hash and
provenance (E8); services contract 1.0.0, change `none` against the 0.3.0 export. New: `SCR-home.probe.json`,
`ux-library.json`, `ux-tokens.json`, `ux-assets.json`, `drawable/` (46), `services-contract.changes.json`,
`PresCrossDeviceServiceResults.kt` (object + `PresCrossDeviceResultDeclaration`), Home copy in strings.xml.

**Adoption.**
- *Assets.* `:ux-theme` `UxSpecResources` (assets, library part rules, `comp.*` table, painter by resource name) is
  provided by `UxTheme` (with `LocalUxMode`); `FlowScreens.resources` carries it; `:ux-spike` reads the three JSON files
  and `Res.allDrawableResources` from `:ux-contract` (`exportedSpecResources()`, also for the gallery and the M5d probe).
  `:ux-elements` `PartIcon` / `AssetImage`: mask tinted with the part colour, image as is, hidden from accessibility
  (`UxAssetDrawn` semantics key as test hook). All Unicode glyph placeholders are gone: app bar leading/trailing, attribute
  tick and copy, button icon, link marker (now also chevron-right on internal links, as the library says), notice tone
  icon, trust badge, status hero art (illustration, or tone glyph in a circle). Drawn ids: 18 for eudiw_v1 (13 icons + 5
  illustrations, 4 of them SVG = iOS only), 13 for idsk; unbound in both: `icon.arrow-back-ios`, `icon.help`,
  `icon.search` (no exported node draws them, so nothing is drawn). Shots: `spike-evidence/M6c/shots/` (iOS gallery
  outcome: SVG illustration and tinted info icon; sheet: verified-user badge, ticks, open-in-new).
- *Library.* `RegistrySchemaTest` embeds `ux-library.json` (sha-checked against the lock); `ux-elements/spec-snapshot/`
  deleted. RULING-M6c-6 gaps.
- *Tokens.* RULING-M6c-5.
- *Conformance.* `FakePresCrossDeviceServices` implements `PresCrossDeviceResultDeclaration`;
  `FakeResultDeclarationTest` (common, host + iOS): `undeclared(fake)` empty, and a declaration omitting
  `FetchRequestResult.Expired` is reported. **Proof**: with `SendResult.Expired` removed from the fake, both tests failed
  (`expected:<{}> but was:<{send=[Expired]}>`), then restored: `spike-evidence/M6c/conformance-proof.log`, `-junit.xml`.
- *Tests added:* `AssetScenario` (host + iOS), `ExportedSpecResourcesTest` (host: every bound asset is a shipped drawable
  looked up by its resource name, icons are masks, SVG withheld on Android; library rules; `comp.*` with mode fallback).

**Pin fix (RULING-M8b-6).** `assertDrawnFromBundle` takes the engine's context in the state (a parallel `FlowEngine`
run on the same results and launch inputs), evaluates the screen's rules on it (`NodeEffects`) and expects every
rule-hidden node and its subtree absent. Regression test `aNodeTheRulesHideInTheTestContextIsExpectedAbsent`: a test
copy adds an `is_empty /verifier/name` hide rule on a body node of the expired outcome. Rehearsal: `:ux-runtime` green on
the D8-unseen export and on this export (`spike-evidence/M6c/pin-fix-rehearsal.txt`). The D8-unseen row stays
**failed** ("test pin found by the unseen drill; fixed afterwards").

**⟲** `git fetch origin feature/kmp`: `b23f6a1f5`, unchanged, no rebase. No pre-spike file touched.

**Release isolation.** iOS `CONFIGURATION=Release :shared-ui:dependencies` 0 `project ':ux-` lines (control 168);
Release app and `.appex` binaries: byte grep of `UxSpecResources|UxAssets|UxLibraryAssets|PartIcon|UxAssetDrawn|
ResultDeclaration|PresCrossDevice|UX-SPIKE|exportedSpecResources|ux_eudiw_v1` 0 / 0 (control, Debug dylib: 921);
`.app` / `.appex` compose resources only `eu.europa.ec.shared.resources` (Debug: also `ux.contract`, `uxspike`; 62
`ux_eudiw_v1_*` files). Android `devReleaseRuntimeClasspath` / `demoReleaseRuntimeClasspath` 0 (control dev debug 8);
`assembleDevRelease` (throwaway keystore): `apkanalyzer` dex 0, files 0, no `ux-spike-init` provider (control dev debug
APK: dex 8397, files 76, provider present).

**For the Home parts milestone.** `SCR-home.probe.json` is synced but unread (the M5d fragments still drive
`gallery-home`). Missing registry parts: RULING-M6c-6 `KNOWN_GAPS` (remove each entry as its element lands; the test
fails on a stale one). Their assets are already resolvable through `partAsset` (EL-document-card art/storage/chevron,
EL-status-chip tone glyph; EL-bottom-bar tab icons are a `kind` rule on the list item's `icon` field: the caller passes
the item's value), and their sizes and colours are in `ux-tokens.json` (`compDp` reads dimensions only; colours and
other types need a reader). `document-art.pid` is SVG: Android shows nothing (RULING-M6c-4). The library's
`variant_axes` `omits` and `brand_selections` are in `ux-library.json` but not read yet.

## M9 notes (track B, D9)

**Baseline.** `646fea3b8` (tag `d9-baseline`), spec `dvm-week0` `b55be65`, exporter 0.5.0. Counting (plan §5 KC4): `git diff
--numstat 646fea3b8..<option commit>`, commonMain counts toward each platform. Both option commits are verbatim `ux-spec/` syncs
(`diff -r` against the export empty); the baseline `ux-spec/` is restored in `997f47a26` (final full gate GREEN 4360/0).

| Option | Spec change (lines) | Hand-written lines, Android = common + androidMain | iOS = common + iosMain | Files outside `ux-spec/`, `spike-evidence/` | Inside TplSheet? | Gate | Row |
|---|---|---|---|---|---|---|---|
| 1 brand (`0f5d5fbdb`) | +1/−1 (`default_brand: idsk`) | 0 + 0 = 0 | 0 + 0 = 0 | 0 | n/a, 0 lines | RED 12 (Home-probe pins) | passed on the criterion; test-pin finding |
| 2 sheet to page (`bbc37a14a`) | +3/−0 (two brand files) | 0 + 0 = 0 (Page branch written in M5) | 0 + 0 = 0 | 0 | yes, 0 lines | RED 2 (sheet-pinned runtime test) | KC4 signal (test), flagged |

| Ruling | Decision |
|---|---|
| RULING-M9-1 | The run may not switch branches, so options are committed in sequence on `spike/ux-runtime` and measured against the baseline SHA; the baseline `ux-spec/` is restored afterwards. Option 2's second brand combination (idsk + page) is an evidence-only build of a throwaway export (not committed). |
| RULING-M9-2 | **Reversed by RULING-M9r-1 (verifier ruling D9 = (C)).** Test-pin failures are not fixed in this milestone (plan: a change outside TPL-sheet is reported, not worked around). Both are tests asserting the baseline's default brand or sheet layout, not product code; the independent KC4 re-derivation (M12) decides whether a test pin counts as "changes outside TPL-sheet" (I read the plan as yes for D9-sheet-page, since the fix lands outside TplSheet). |
| Finding | Option 1: 10 `HomeParts*Test` + 2 `ScreenProbeTest` (M5e) pin eudiw_v1 as the default brand; the flow's own tests (runtime, elements templates, spike) all pass on idsk. Option 2: `InterpreterScenario.rulesAndBindingsFollowTheContext` expects the sheet's `app-bar` to be drawn. |
| Finding | `TplSheet` `page` is complete against the spec: no scrim or backdrop, same dialog semantics, all-side safe-drawing insets, back stays the flow policy. The sheet-versus-page request step: shots `M9/0-baseline`, `1-brand`, `2-sheet-page`, `2b-page-idsk` (`shots/request.png`, `flow.mp4`). |

Gate rows: `0f5d5fbdb` RED 4360/12; `bbc37a14a` RED 4360/2; `997f47a26` (baseline restored) GREEN 4360/0, Debug and Release build.

Phase-metrics row: M9 | drills (track B, verification; spec + sync only) | about 1 h 40 min (3 spec worktrees, 4 simulator runs, 3 full gates of 6 to 7 min and one spec gate of 4 min 24 s) | 0 Kotlin; tools: `spike-evidence/M9/run-variant.sh`, `drill-helpers.sh` | generated: `ux-spec/` +774/−985 (option 1), +69/−75 (option 2) | 0 | 3 full (4360/12, 4360/2, 4360/0) | 0 rework; 2 test-pin findings.

## M9r notes (rework: test harness, after the verifier's D9 = (C) ruling)

Brief: an independent verifier ruled D9 = (C): both options pass at 0 hand-written lines and KC4 is not hit, but the option
commits `0f5d5fbdb` (12) and `bbc37a14a` (2) breached brief §6.5 through test pins (the same cause as D8 attempt 1 and
D8-unseen). Spike rule T1 is in SPIKE-PLAN §0.2b.

| Ruling | Decision |
|---|---|
| RULING-M9r-1 | RULING-M9-2 is reversed: a test pin is a harness defect, not "a change outside TPL-sheet", and is fixed. The D9 rows read "passed, 0/0 lines, KC4 not hit; §6.5 breached on 0f5d5fbdb (12) and bbc37a14a (2) by test pins; harness fixed in M9r"; the D8 rows are unchanged. |
| RULING-M9r-2 | The archived D8-attempt2 exports (exporter 0.3.0, 18 files) cannot build the current modules at all (no `ux-library.json`, `ux-tokens.json`, `ux-assets.json`, probe, drawables, conformance table; M5e/M6c consume them). regen-smoke therefore uses each drill's archived `spec.patch`, unchanged, re-exported on the current spec baseline `dvm-week0` `b55be65` (exporter 0.5.0; drill 3 applied 3-way, its context line moved; drills 2 and 5 fell back to a direct apply), throwaway commits, archived in `spike-evidence/M9r/regen/d8b-*/` (`tools/reexport-d8.sh`). The spec gates were not re-run for these exports (the export itself validates); they are test inputs, not drill rows. |
| RULING-M9r-3 | regen-smoke runs host + iOS (`allTests` of all five spike modules) per export: 43-59 s each, 491 s for nine in the gate; host only is 12-15 s each, 130 s (`./spike-regen-smoke.sh host`). The full gate includes it; `--no-regen` / `SPIKE_REGEN=0` skips it and such a run is not a full gate. Builds go to `<module>/build/regen-smoke/` via `-PuxSpecDir`, so the tracked build and the gate's counts are untouched. |
| RULING-M9r-4 | The spike's fake engine declared its results by hand (`FakePresCrossDeviceServices`), so a spec that adds a result (d8b-2, offline) turned `FakeResultDeclarationTest` red: a harness pin in main source. It now declares every result from the generated `PresCrossDeviceServiceResults.results` (the fake plays whatever a script names). The DEC-081 check keeps its purpose for a real adapter. |
| RULING-M9r-5 | Literals kept, as runtime invariants or code-owned names (rule T1): one h1, one focused node, at most one live region, the StateChangeA11y/TemplatesSemantics counts; the registry element `EL-rp-identity` and its `logo` prop (F3 route); generated contract members (`fetchRequestResults`); the code-owned status-to-tone table of `UxStatusChip`; the `comp.*` token keys the elements read; test-owned tags and labels that happen to equal spec words (`hero`, `title`, `Expired`). The InvokerDefaultsTest of `:ux-contract` (compile-time pins on generated payload types) moved to a data-driven check in `FakeScriptsTest`. |
| RULING-M9r-6 | Tests that need a spec feature to exist (a main-path step Back undoes, a recovery outcome of the entry call, an internal result, an `EL-rp-identity` node, a guard that keeps an invariant) skip with a printed note when the export has none, instead of failing: requiring the feature would be a pin. With the current export each of them runs. |

**Proof.** Before the fix (`a652b1565` infrastructure on `6d958dfc4` tests): `m9-1-brand` 358/12 (10 HomeParts host+iOS, 2
ScreenProbe), `m9-2-sheet-page` 358/2 (`rulesAndBindingsFollowTheContext` host+iOS), the verifier's numbers;
also `d8b-2` 4 (FakeResultDeclaration), `d8b-3` 12, `m9-2b-page-idsk` 14; baseline, d8b-1, d8b-4, d8b-5-unseen green
(`spike-evidence/M9r/logs/regen-smoke-before-fix-all.txt`, `regen-smoke-proof-before-fix.txt`). After: 9/9 green in the full
gate (`logs/gate.log`).

**Pins fixed, per test (before → after: what it proves).**

| Test | Before | After |
|---|---|---|
| EngineAsync, BackRestore, EngineSemantics, Ruling2, FlowViewModelSavedState, FlowHostBackIos, Handshake h8-h10 | engine semantics proven on the export's `request`/`auth`/`sending`… names, payloads and paths | the same semantics (cancel on leave, late result, double tap, pending gate, ui_state_when, every back and restore policy kind, entries, exits with carries, RULING-2, refusal paths) on the synthetic bundle |
| ExportedPoliciesTest (new) | (brief §5 decisions were asserted by name) | every non-final state of the export restores (both platforms) and takes Back exactly as its own `restore`/`back`/`process_death` say; no non-idempotent service is called again |
| FlowModelCheck | L58 hard-coded (`auth`, `selected_count`), a hand-built pre-fix variant | every invariant of the bundle holds within the bound, each is reachable, and a guard-stripped variant breaks one with a path (L58 today) |
| Interpreter | states, nodes (`continue`, `CONFIRM`, `4711`, `hero`, `actions`), sheet layout pinned | entry state, recovery outcome, internal-result state, consent screen and planted sites read from the export; expected regions follow the brand's `decision-steps` option (`layouts` `arranges`); bound values and rule-hidden nodes from the context the main path produces |
| StateChangeA11y, IosAccessibilityBridge | the request-to-success path by name | the export's main path (main branches, scripts' payloads); one call per entry, crossfade, Back refocus |
| SchemaCheck, ExportedModelReplay, Ruling2 export check | `connecting`/`hero`/`cancel`, ≥ 80 sequences, ≥ 4 invokes | planted sites from registry + export; no spec counts |
| HomeParts, HomeSupport | eudiw_v1/idsk, asset ids, Slovak copy, `home.appbar` list | every exported brand, expected assets = library rule ∩ brand binding, TPL-home regions from the library, test-owned labels |
| ScreenProbe, ExportedSpecResources | brands, gated nodes, ui states, `/network/online`, asset ids, 22 dp | gating and variant omits checked from the probe and library; every ui state's data vs examples; every library asset rule; the default brand's first dimension token |
| FakeScripts, FakeScriptPlayer, FakeResultDeclaration | per-script paths by state name, `SendResult`, `Expired` | every exported script settles where the bundle lets it, along bundle edges, non-idempotent services at most once, only scripted services; invoker encodes every declared field; test-owned result type |

Counts: brand ids 8; asset ids 11 (+ every library rule now checked); state names about 125 (runtime about 96, FakeScripts 26,
Handshake 4); node/event/service/result/data literals about 60 (beyond the verifier's 31: services and results in the
runtime and the spike tests); copy about 30 (HomeParts, added-state text); layout 2; spec counts 2 removed (the
StateChangeA11y and TemplatesSemantics counts kept); compile-time pins 3 (InvokerDefaults, FakeScriptPlayer, FakeResultDeclaration)
and 1 main-source harness pin (the fake's result declarations).

**TplSheet insets.** In sheet mode the sheet was as tall as its content with no top inset, so on the request screen the title
drew under the status bar (`spike-evidence/M9r/shots/request-sheet-before-646fea3b8.png`). The sheet surface now pads
`WindowInsets.safeDrawing.only(Top)` outside its elevation and clip, inside `TplSheet` only
(`shots/request-sheet-after.png`: the scrim shows above the sheet, the body scrolls, the actions stay pinned).

## M11 notes (formal Android drills on the real export)

Build: `./gradlew :androidApp:assembleDevDebug` of `25e2e31dc`, installed on the headless emulator `spike_pixel` (API 37, arm64,
setup in `spike-evidence/M11-prep/README.md`), package `eu.compote.euidi.dev`, `cmd locale set-app-locales … sk` (the wallet's own
screens stay English, the spike screens show the Slovak copy, as on iOS), `cmd uimode night no`. The switch is the file
`files/ux-spike.on` (`AndroidUxSpikeHook.script()`), script `slow`. Helpers: `spike-evidence/M11/helpers.sh`, `pin.yaml`.
All six formal rows passed on the emulator; no KC2 or KC3 signal. Open for a device: the real low-memory kill (Q-D2-LMK) and the
optional real-phone TalkBack walk (Q-D7-Android).

**P2 (Koin).** `UX-SPIKE: hook module loaded` appears in logcat in the first activity of every process, after the Koin start and
before the first `UxFlowRoute` entry; `loaded` guard held across 40+ process starts (one line each). **P6 (Android restore).** The
standalone `UxFlowRoute` survives a real process death and the engine restores from its snapshot (`restore request -> snapshot`);
no `AppRoute` edit. **Release isolation** (`logs/p2p6-release-isolation.log`): `devRelease`/`demoRelease` runtime classpaths 0 `ux-`/`uxspike`
lines (debug 8); `assembleDevRelease` APK (throwaway keystore): 0 spike dex packages, 0 files, no `ux-spike-init` provider; control, the
debug APK: 9418 package lines, 85 files, provider present.

| # | Ruling or finding | Resolution |
|---|---|---|
| RULING-M11-1 | `run-as … 'echo > files/ux-spike.on'` fails until `files/` exists, and needs `adb shell "run-as …"` quoting. | Switch written after the first launch with `mkdir -p files`. Tool only. |
| RULING-M11-2 | `auth` (1.5 s) and `sending` (3 s) are shorter than adb latency plus Maestro. | D1/D2 chain the tap and the Back/HOME key in one `adb shell "…; …"`; the state is read from the `UX-SPIKE` log, not by polling (a `uiautomator dump` also suppresses TalkBack while it runs, so D7 never dumps). |
| RULING-M11-3 | D2(b): a real lmkd kill cannot be provoked on this emulator as shell (10 apps under `memory-factor CRITICAL`: lmkd killed other cached apps, not the wallet; `cmd activity kill-all`: wallet survived twice; RAM filler: shell cannot write `/dev`). | D2(b-approx) = `memory-factor set CRITICAL` + `am send-trim-memory COMPLETE` + root `kill -9` (lmkd's own mechanism), relaunch from recents. Not a real LMK: the row says so and Q-D2-LMK stays open. |
| RULING-M11-4 | D2(a): the recents relaunch of a task whose root is the deep-link intent re-dispatches the link. | F-D2-1. The drill task root is the launcher intent (MAIN launch, PIN, then the link as `onNewIntent`); the link-rooted case is kept as `d2a-request-linkroot.*` evidence. |
| RULING-M11-5 | TalkBack on the image asks for the notification permission; a double tap is needed to activate. | `pm grant com.google.android.marvin.talkback android.permission.POST_NOTIFICATIONS`; activation by two `input tap`s (works). Events from `cmd accessibility start-trace -t IAccessibilityServiceClient` (TYPE_ANNOUNCEMENT, WINDOW_STATE_CHANGED pane events, ACCESSIBILITY_FOCUSED), parsed by `a11y-trace-events.py`; no audio headless. |
| F-D2-1 | A link-rooted task is not restored from the snapshot after process death: the relaunch re-opens the link and starts a new flow at `connecting` (old snapshot unused; for `sending` this means no second send either, as the flow starts again from `connecting`). | Reported, no change: this is the app's intent redelivery for any deep link, not a flow handler. Candidate follow-up for the app team (clear or consume the launch intent). Not counted as a hard-state handler (KC2). |
| F-M11-1 | `cancel#1` on the `request` Back fails in the fake (`script 'slow' has no step for cancel`, mapped to Error, result ignored); state is still `cancelled`. | Same as iOS (script data); no impact on the pass condition. |
| F-M11-2 | After a process death and recents relaunch the app showed no PIN gate. | Not investigated (app unlock policy, outside the flow). |
| F-M11-3 | The snapshot is saved at stop: in D2 `sending` the flow had already reached `success` in the background, and the restore still gave `error` (state at stop). | As designed; sending restores to error. |
| F-M11-4 | Compose virtual nodes produce `ACCESSIBILITY_FOCUSED` events with empty text, so the trace alone does not name the focused node. | The focus target is shown by the screenshots (ring on the heading) and the app's `focus-heading … (focused)` log. A swipe walk of the old state's reachability is left to the real-phone walk. |
