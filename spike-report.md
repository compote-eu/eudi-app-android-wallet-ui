# Spike report: the flow runtime and the typed seam (`spike/ux-runtime`)

Final report of the spike for the owner team (Roman Hauptvogel, Stanislav Cug) and the app team (Martin). Brief:
`dvm-edoklady-eudiw-analysis/ux/evaluation/kmp-ui-from-spec/spike-brief.md`. Plan: `SPIKE-PLAN.md`. Drill rows:
`spike-results.md`. Run log: `SPIKE-LOG.md`. Owner steps: `HUMAN-QUEUE.md`. Evidence: `spike-evidence/`.

Branch `spike/ux-runtime` in `compote-eu/eudi-app-android-wallet-ui`, based on `origin/feature/kmp` @ `b23f6a1f5`
(unchanged at M12, ⟲ checked). Code tree at close: `04e4ba57c` (the M12 commits change documents and evidence only).
Never pushed. Written by the M12b agent (Claude Opus 5.5) from the independent M12a verification (Opus, read-only, at
`04e4ba57c`) and the operating-model evaluation; the numbers are re-derived where the report says so.

## 1. Executive summary

The spike asked one question: can **one hand-written flow runtime run `FLW-pres-cross-device` from the `ux/` spec on
Android and iOS, with app logic connected only through generated typed interfaces, so that regenerating from the spec
never needs a hand edit in screen code?**

What the spike shows:
- **The runtime works on both platforms.** A pure-Kotlin engine, a host and a generic screen interpreter run the flow
  from the exported bundle. Seven of the eight screens are drawn from the bundle; the consent screen
  (`SCR-pres-request`) is hand-written against the generated contract, as the brief requires. Back, process death,
  cold start, deep links, the happy path and the expired path behave as the brief's §5 says, on the iOS simulator and
  the Android emulator (D1, D2-am kill, D3-sim, D4, D5, D6 all passed formally).
- **Spec-only changes regenerate with no hand-written lines.** Adding a field and binding it to an element, adding a
  state and a screen, switching the brand, rewording copy, and switching a sheet to a page each needed only a
  `ux-spec/` sync (0 hand-written lines; D8 attempt 2, D9). The one blind drill (D8-unseen) behaved correctly but
  failed formally on a spike test that pinned the baseline export.
- **The typed seam is real but the app's interactors do not fit it yet.** A real `Services` adapter on the app's
  presentation interactors (M11b) maps 5 of 21 results cleanly, 10 lossily and 6 not at all. There is no typed
  `Expired`, no verification-code source (a real adapter cannot reach `sending`), and `transactionData` is dropped.
- **The verdict is not given yet.** Device evidence the brief requires is missing (VoiceOver on an iPhone, TalkBack
  and a real low-memory kill on an Android phone, iOS cold start on the phone, the DC-API extension runs) and the app
  team's estimate (KC5) is missing. No kill criterion has been hit so far.

**Verdict: Incomplete: awaiting evidence.** If the missing rows pass, the result is **Go with exceptions** until the
app team's estimate arrives, and **Go** if that estimate is at most 2× the spike. Details in §2.

Cost in one line: the whole spike wrote about 11.0 k hand-written main lines and 7.5 k test lines (most of it the
one-time runtime, elements, theme and harness); a flow's own app-side cost is a Services adapter (about
560 main / 470 test lines for this flow), its consent screen (about 170 lines) and its new parts; a UI-only change costs
0 hand-written lines and about 15 to 25 minutes of agent time, most of it gates (§9).

## 2. Verdict

**Incomplete: awaiting evidence** (SPIKE-PLAN §0.5: KC2 and KC3 are unresolved, KC5 is missing, D10 is not run, and the
D7-Android precondition rests on a check weaker than the brief).

### 2.1 Missing evidence and who supplies it

| Missing row | Why it is needed | Who | Queue entry | Build |
|---|---|---|---|---|
| D7-iOS: VoiceOver walk `request` → `success` on the iPhone | KC3 (the only evidence row for KC3) | Owner team | Q-D7-iOS, checklist `spike-evidence/device/DEVICE-TEST-3-VOICEOVER.md` | device build at HEAD (§2.4) |
| D3-device: iOS cold start after a kill on the iPhone | KC2 (both platforms) | Owner team | Q-003 | device build at HEAD |
| D2 real low-memory kill on an Android phone (launcher root) | KC2; the emulator row is an approximation (root `kill -9` under memory-factor CRITICAL) | Owner team | Q-D2-LMK | `assembleDevDebug` at HEAD |
| D7-Android: TalkBack walk on a real Android phone | verdict precondition (§0.5); the emulator row has events but no speech and no focused-node text, so it is unresolved | Owner team | Q-D7-Android (REQUIRED) | `assembleDevDebug` at HEAD |
| D10 runs 1–5: the request sub-flow in the DC-API extension on the iPhone | track C; not run means Incomplete | Owner team | Q-D10 | device build at HEAD |
| KC5: the app team's estimate | brief §8.5 | Martin (app team) | Q-KC5 | — |
| Touch-list approval T1–T5, T9 | brief §6.4 | Owner team | Q-TOUCH | — |
| Confirmation of the agent rulings on KC1, KC4, KC2 | SPIKE-PLAN §0.5 forbids agents to rule on kill criteria | Owner team | Q-KC-CONFIRM | — |

### 2.2 Conditional verdicts

| If … | Then |
|---|---|
| all device rows pass, KC5 still missing | **Go with exceptions** (brief §8: without KC5 at most "go with exceptions") |
| all device rows pass, KC5 ≤ 2× the spike | **Go** |
| D10 runs and fails, everything else passes | the extension becomes a **declared exception**; result as the two rows above (at most Go with exceptions) |
| D7-iOS fails and the runtime cannot fix it (KC3 confirmed) | **Fall back** |
| D3-device or the real LMK needs a handler outside the policy in more than 1 of 3 hard states (KC2 confirmed) | **Fall back** |
| KC5 > 2× the spike | **Fall back** |
| D7-Android on the phone fails | the verdict precondition fails, but no kill criterion names TalkBack; the rules give no verdict, so **the owner decides** |
| the owner overrules an agent KC ruling (Q-KC-CONFIRM) | re-derive that criterion from its rows; a confirmed kill means **Fall back** |

### 2.3 Drills (from M12a, with the M12 re-run)

F = formal (counts), P = preliminary (does not count). Platform: sim = iPhone 17 simulator iOS 27.0; emu = emulator
`spike_pixel` (API 37); device = iPhone Air iOS 27.0.1.

| Drill | iOS | Android | Status for the verdict |
|---|---|---|---|
| D1 Back | F passed (sim; swipe synthesised by XCUITest); device rows P (owner swipes) | F passed (emu; system Back and predictive Back) | passed |
| D2 process death | n/a (iOS abandons by §5) | `am kill`, launcher root and link root: F passed at `8ff589aca` and **re-run at HEAD in M12** (6 of 6 runs at `04e4ba57c`: request → snapshot, auth → request, sending → error, no new send); link root failed before the T9 guard (F-D2-1). LMK: **approximation** passed (root `kill -9` / memory-factor), **real LMK not run** | unresolved (real LMK missing) |
| D3 iOS cold start | F passed (sim, `simctl terminate` ×3 + `kill -9`); **device not run** | n/a | unresolved (device missing) |
| D4 deep-link cold start | F passed (sim); device P | F passed (emu), again after T9 | passed |
| D5 model check | F passed (fails on pre-fix L58, passes fixed; 84 sequences replay in the Kotlin engine) | same | passed |
| D6 happy + expired | F passed (sim) | F passed (emu) | passed |
| D7 screen reader | **VoiceOver on the iPhone not run**; P: M7 audit and bridge tests | emulator events only: **unresolved (weaker than brief)**; real phone not run | unresolved |
| D8 regeneration | attempt 1 ×4 **failed** (test pins, the logo element gap, consent screen); attempt 2 logo/offline/brand/copy **passed, not blind**; logo image not rendered (example URL serves HTML), a11y-hidden inferred; **D8-unseen (the only blind row) failed** on a test pin, behaviour correct, diff `ux-spec/**` only | (shared code; Android not run for D8) | see KC1 |
| D9 track B | brand and sheet → page: passed, 0/0 hand-written lines; both option commits had red gates (test pins); Page layout written in M5 before the baseline, so its cost is not in the count; Android never rendered for D9 | — | see KC4 |
| D10 track C | harness in the app P passed (79 ms to first frame, 69.9 MB); **device runs 1–5 not run** | n/a | not run → Incomplete |

### 2.4 Kill criteria (from M12a)

| KC | Status | Evidence and caveat |
|---|---|---|
| KC1 regeneration needs a hand edit in generated or interpreted screen code, or screen code in the runtime | **passed (with caveat)** | attempt-2 rows pass with 0 hand-written lines; the runtime grep and the KC1 gate guard are clean; the only blind row failed on a test file (not a KC1 trigger, but it fails the D8 drill's own criterion). Agent ruling M8r (B) awaits owner confirmation (Q-KC-CONFIRM) |
| KC2 more than 1 of 3 hard states needs a handler outside the policy | **unresolved** | 0 of 3 so far on sim and emu; D3-device and the real LMK are missing; the T9 guard is state-agnostic intent handling (RULING-W6, agent ruling, owner to confirm) |
| KC3 VoiceOver focus and announcements | **unresolved** | D7-iOS (device) missing; automated evidence (M7) shows one bridge call per settled state and no old-state node during the crossfade |
| KC4 track B outside TPL-sheet or > 150 lines | **passed** | 0/0 lines for both options; the red gates were test pins (RULING-M9r-1, agent ruling, owner to confirm) |
| KC5 app-team estimate > 2× the spike | **missing** | Q-KC5 (Martin) |

Device build for the owner's walks: ⟨DEVICE⟩.

## 3. The central claim, stated honestly (M12a)

**Proven.** Spec-only changes regenerate with no hand-written lines, on the simulator and the emulator, for: a new
bound field (logo), an added state and screen (offline-loading), a brand switch, reworded copy, and sheet → page.
Formally this was shown only at exporter 0.3.0 (D8 attempt 2) and 0.5.0 (D9). At HEAD it rests on the gate's
`regen-smoke` step: the spike module tests on host and iOS run against the re-applied drill patches, with no app run.

**Standing exceptions.**
1. The consent screen is hand-written. Only its rp-identity nodes go through the interpreter (`ScreenScope.Node`);
   any other structural change to `SCR-pres-request` needs a hand edit.
2. Spike rule T1 (tests derive spec-owned values from `ux-spec/`) is required. Without it every sync turns the gate red
   (D8 attempt 1, D8-unseen, D9).
3. A new element kind, prop or variant needs Kotlin (a part costs 115 to 280 main lines, §9).

**Services coupling (M11b, §4).** The generated contract is stricter than the app's interactors: 5 of 21 results map,
10 are lossy, 6 are impossible.

**Other limits.** Profile gating is resolved at export time (the Home probe draws the default brand only); SVG art is
not drawn on Android; dark mode is not exported; motion is a declared gap.

## 4. M11b: the real Services adapter (OR-5, a probe)

`InteractorPresCrossDeviceServices` implements the generated `PresCrossDeviceServices` and
`PresCrossDeviceResultDeclaration` on the app's real `PresentationRequestInteractor`, `PresentationLoadingInteractor`
and `BiometricInteractor`. No interactor, wallet-core or Multipaz file changed. Report:
`spike-evidence/M11b/adapter-report.md`.

| Finding | What it means |
|---|---|
| Counts | 21 generated results: 5 mapped, 10 lossy, 6 impossible. Undeclared: `authenticate=[Expired]`, `fetchRequest=[DocInvalid, Expired]`, `send=[Expired]`, `verificationCode=[Ready, Expired]` |
| F-M11b-1 placement | the adapter cannot live in `:ux-spike` commonMain (dependency cycle with `:shared-ui`); in the app it belongs beside the interactors, depending on `:ux-contract` |
| F-M11b-2 typed errors | every expiry arrives as an untyped `Failure(String)`, so no service can return `Expired`; Android folds revoked documents into `NoData`, so `DocInvalid` has no source |
| F-M11b-3 verification code | the app has no source; `verificationCode` can only return `Error`, so **the real adapter can never reach `sending`**. Needs a spec or product decision |
| F-M11b-4 request model | `isRequired` is false on every path; `label_copy` is a key but the app has display titles; the app ticks every row, the spec wants optional rows unticked; alternatives, several documents and **`transactionData` are dropped (a security gap for signing or payment)** |
| F-M11b-5 trust | the app's `VerifierNotTrusted` is a hard block, mapped to `Error`; `RpUntrusted` from `!isFullyVerified` with optional-only rows breaks `RUL-pres-request-has-mandatory` |
| F-M11b-6 authentication | the spec authenticates before `send`; the real key-bound authentication happens inside the send (Android: a second prompt). The PIN path has no contract counterpart; `PlatformContext` is injected outside the contract |
| F-M11b-7 lifecycle | the contract has no teardown; the adapter implements `AutoCloseable` and the entry closes it. `cancel` is fire-and-forget |
| Cost | 562 main / 468 test lines (code lines 366 / 362), wiring +87/−5, about 42 min of agent time (half of it the gate); 0 Kotlin compile breaks, 2 build-configuration breaks |

Upstream finding for the app team (F-D2-1, M11c): `EudiComponentActivity` re-delivers the launch intent after any
process death; for an OPENID4VP link with documents it pops to Dashboard and loses the restored back stack (any
in-progress presentation, not only the spike's). Proposed fix and analysis: SPIKE-LOG "M11c notes".

## 5. Home probe (OR-1, OR-8; not counted toward the verdict)

M5d drew `SCR-home` from generated fragments: of 10 parts, 3 present, 2 partial, 5 missing; estimate to close about
1.7 k lines. M5e then built the missing parts from the export's `comp.*` tokens and drew the exporter's Home probe for
all 14 eudiw_v1 ui states (the visibility oracle matches on both targets). No engine, interpreter or contract change was
needed; only parts.

Per-part cost (M5e, measured; wall-time shares of 2 h 45 min estimated):

| Part | Main | Registry + schema | Token read sites | Test | Share of time |
|---|---|---|---|---|---|
| EL-status-chip | 138 | 8 | 14 | 62 | ~10 % |
| EL-document-card (4 variants) | 504 | 39 | 70 | 83 | ~25 % |
| EL-bottom-bar | 168 | 28 | 19 | 42 | ~10 % |
| EL-segmented-switch | 205 | 26 | 29 | 36 | ~10 % |
| EL-trust-mark | 136 | 21 | 13 | 31 | ~7 % |
| TPL-home | 179 | 18 | 13 | 42 | ~13 % |
| App-bar gaps, avatar and lock icons | +80 | 0 | 19 | 32 | ~5 % |
| Shared (`UxComp`, theme readers) | 201 | 0 | — | 149 | — |
| Probe host (`ScreenProbe`) | 297 | 0 | — | 169 | ~20 % |

Six new parts: 1 330 main, 142 registry/schema, 296 test, about **220 main lines per part** (M5d estimated 160; M5's
simpler parts were about 115). Remaining visible differences (CSS-only values, the webfont, idsk gating, unbound
icons) and the exporter findings are in SPIKE-LOG "M5e notes" and `spike-evidence/M5e/coverage.md`.

## 6. Constraint audit (brief §6)

| # | Constraint | Status | Evidence |
|---|---|---|---|
| 1 | Generated code is never edited by hand; one sync commit | **holds** | all five `ux-spec/` sync commits on the branch (`7a24efacf`, `bad2c1871`, `0f5d5fbdb`, `bbc37a14a`, `997f47a26`) touch `ux-spec/**` and evidence only; the gate's `uxspec-lock` step checks every file's sha256 against `UX_SPEC.lock` |
| 2 | The runtime contains no screen-specific code | **holds** | KC1 guard (gate step): no `SCR-`/screen literal and no state name of the exported bundle in any spike module's main sources, except the declared consent screen |
| 3 | Real wallet logic untouched; debug only; app as before with the switch off | **holds, partly evidenced** | Android: release classpaths and release APKs at HEAD carry no spike package, file or provider (M12, §6.1). iOS: Release graph, binaries and resources at HEAD (M12, §6.1; the last broad iOS check before M12 was M6c). T9's guard code ships in Release, inert (no hook bound). Android switch-off is shown by a host test only (`LaunchIntentGuardTest`: no hook, hook off, hook on), not by an app run |
| 4 | Small footprint; touch list approved at plan review | **within cap, over the estimates, not approved** | 6 existing files (cap 8): T1 `settings.gradle.kts` +6; T2 `shared-ui/build.gradle.kts` +18 (plan +8–12); T3 `SharedEntries.kt` +18 (plan +10–16); T4 `androidApp/build.gradle.kts` +2 (plan +1); T5 `ConsentHost.swift` +13/−1 (plan +10–14); T9 `EudiComponentActivity.kt` +32/−1 (RULING-W6). Owner approval never given: Q-TOUCH |
| 5 | Quality bar (detekt, ktlint, unit tests) on every commit | **breached** | red full gates on branch commits: `0f5d5fbdb` (12 failed), `bbc37a14a` (2), `a652b1565` (red on purpose, as proof before the fix); red on deleted drill branches: `spike/d8-1..4` and `075c2be9d` (d8b-5). About 20 commits ran partial checks only (module tests, lint or a lock check), e.g. `7a24efacf`, `bad2c1871`, `79ac208e4`, `069e596ac`, `8ff589aca`, `88a79d4ec`; each milestone ended on a green full gate |
| 6 | Consent screens hand-written against the generated contract; UI sends intent only | **holds** | `PresRequestScreen.kt` (171 lines) draws only the rp-identity nodes through `ScreenScope.Node`; the consent snapshot comes from the flow context |
| 7 | Rebase on `feature/kmp` at marked milestones | **honoured in substance** | `feature/kmp` never moved (`b23f6a1f5` at every check). The check was logged at M2, M6a, M8, M6c and M12; not at M7-end, M10-end or M11-start |

### 6.1 Release isolation at HEAD (M12)

Script `spike-evidence/M12/release-isolation.sh`, log `spike-evidence/M12/release-isolation.log`, at `04e4ba57c`,
after the full gate's Release build. Patterns: the plan's list plus every spike class, package and log tag
(`DebugFlowHook`, the declared seam, is not a pattern).

| Platform | Check | Result | Control |
|---|---|---|---|
| iOS | Gradle graph, `CONFIGURATION=Release :shared-ui:dependencies` | 0 `project ':ux-…'` lines in shipping configurations; 5 lines, all in test-only configurations (`androidHostTest*`, `allTestSourceSets*`: the M11b adapter tests) | Debug graph 173 |
| iOS | symbols and strings, Release simulator build: `EudiWallet`, `EudiWalletDocumentProvider.appex`, `SharedKit` | `nm -gU` 0 / 0 / 0, `strings` 0 / 0 / 0 (SharedKit 114 167 symbols, as at M2 + 3 for `DebugFlowHook`) | — |
| iOS | resources in `.app` and `.appex` | only `eu.europa.ec.shared.resources`; 0 spike files (bundle, tokens, library, assets, probe) | — |
| Android dev | `devReleaseRuntimeClasspath` | 0 | `devDebugRuntimeClasspath` 8 |
| Android dev | `assembleDevRelease` APK (throwaway keystore): dex packages, files (assets, composeResources), manifest | 0, 0, no `ux-spike-init` provider | dev debug APK: 9 663 dex lines, 85 files, provider present |
| Android demo | `demoReleaseRuntimeClasspath` | 0 | `demoDebugRuntimeClasspath` 8 |
| Android demo | `assembleDemoRelease` APK: dex, files, manifest | 0, 0, no provider | — |

T9 (`EudiComponentActivity`, Android only) ships in both release APKs by design; with no `DebugFlowHook` bound its guard
is inactive and the upstream logic runs (`LaunchIntentGuardTest`). Behaviour with the switch off on Android is shown by
that host test, not by an app run.

## 7. Lessons learned (operating-model evaluation, 2026-10-17)

**What worked.**
- Executing the spec finds defects that reading misses: the dead `CONTINUE` when an optional item is pre-ticked (V21),
  the same in `request-untrusted`, no path for untrusted + optional-only, `/links/help` with no source (so HELP never
  shows), and 24 agreement findings waived in other flows (20 same-device, 4 dashboard).
- Agreeing the boundary before the exporter existed paid off: 9 divergences at the first real export, all fixed on the
  app side, no exporter change. Provisional-then-sync was cheap: the one-time adoption was +480/−1 002 lines in about
  14 minutes of commit span.
- Hard gates with mutation tests are trustworthy: the KC1, style, lock and L58 guards trip on planted defects. The style
  guard and the fail-loud schemas turned silent drift into visible errors.

**What did not work or surprised.**
- Some findings only appear on a device: the placeholders were unreadable in dark mode; iOS needed one extra frame
  before focus took.
- Tooling gaps cost time: Maestro cannot drive the iOS edge swipe (a custom XCUITest runner was built); macOS `git grep`
  has no `\b`; M0 waited for an Xcode install.
- The mutation suite needs maintenance (cases re-targeted several times).
- Governance is expensive: about 2.8 k lines of plan and log prose against about 6.8 k hand-written main lines, about 90
  recorded rulings; token and dollar cost were not recorded.
- Week-0 sizing was badly off: planned 8–10 agent-days, steps 1–8b ran in about 3.5 hours of commit time.
- App tests pinned to spec content caused the red gates of D8 attempt 1, D8-unseen and D9. The fix was spike rule T1
  and the `regen-smoke` gate step; a production repo needs the same rule in CI.

**Operating-model verdict.** "Flows final, then the app team works independently" holds only partly: screen, rule and
copy changes with existing events and results stay UI-only; a new service call or argument breaks the adapter compile
(visible); a new result used to fail silently (now closed by the conformance test, exporter 0.5.0); exits (help,
settings) are a second seam; the typed-error gaps are real. "Elements final, then UI only regenerates" holds narrowly:
token values, copy, brand overrides, composition of existing parts, rules and ui states regenerate; new elements, props,
variants, `comp.*` tokens, dark mode, motion, profile gating and platform limits need code. The bundle is compiled in
and `SPEC_HASH` is in Kotlin, so every copy fix is an app PR, CI run and store release. Martin proposed compile-time
generation; the spike chose runtime interpretation, so his buy-in is part of KC5.

**Must solve first (ranked).** (1) Services contract governance (partly done: version, changelog, conformance test).
(2) Android, the real adapter and KC5 (Android and the adapter done in the spike). (3) Executable flows for same-device,
issuance and the dashboard (173 states) with back, restore, services and the model check. (4) An element definition of
done and the remaining dashboard parts. (5) Reverse token coverage and the style guard in app CI (done in the spike).
(6) Profile gating and the asset pipeline (done for Home). (7) The sync-PR flow into the app repo (push consent, CI,
merge owner, release train). (8) The exporter as a product (CI, owner, semver, refusal rules). (9) Accessibility proof
on devices. (10) The design-tool path. (11) Dark mode and motion as spec decisions. (12) The open decisions (§12).

**Recommended operating model.** The design team authors `ux/` inside the gates. A standing UI-platform team owns
executable flows, the exporter, the runtime, the element library and the consent screens. The app team owns the
Services adapters, exits, entry wiring and releases, and co-owns and signs off the Services contract. Handoffs: a gated
spec PR, then a `ux-spec/` export with its lock, then a versioned Services contract with a changelog (additive-safe,
needs-adapter, breaking). Cadence: a weekly sync PR; contract changes by RFC before export; releases ride the app train.

## 8. Phase metrics (OR-6)

Sources: the SPIKE-LOG "Phase metrics" table (wall time, rework, gate runs) and `git show --numstat` per milestone on
`b23f6a1f5..04e4ba57c`, re-derived in M12. **Main** = `.kt`/`.kts`/`.sh`/`.swift`/`.py` outside test source sets,
build scripts and the gate included; **test** = test source sets; **generated** = `ux-spec/**`; documents, evidence,
Maestro YAML and the PROVISIONAL/fragment JSON are excluded. Wall time is the agent's (a lower bound from commit
timestamps, or the milestone's own measurement); owner waits are excluded. Week 0 (spec and exporter) ran in the
analysis repo and is reported from the operating-model evaluation.

| Phase | Milestones | One-time / recurring | Agent wall time | Hand-written main +/− | Test +/− | Generated +/− | Rework loops and causes |
|---|---|---|---|---|---|---|---|
| Spec + exporter (week 0, analysis repo) | week-0 steps 1–8b, batches | one-time (exporter); recurring per flow (spec) | ~3.5 h commit span for steps 1–8b (planned 8–10 agent-days) | exporter + gates tools 3 320 | 1 880 | — | mutation cases re-targeted repeatedly |
| Runtime (skeletons, engine, host, interpreter) | M2, M3, M4, M5b | one-time | ~2 h (34 + 9 + 30 + 45 min; upper bound with waits ~4 h); M5b's main includes the consent screen (256) | 812/2 + 1 551/0 + 1 073/79 + 1 430/41 = **4 866** | 101 + 1 339 + 897 + 544 = **2 881** | 0 | 4: Koin DSL in a test (KOIN-D002); RULING-2 (throwing service); QR entry name; back test re-pointed |
| Accessibility bridge | M7 | one-time | 13 min | 322/50 | 471/32 | 0 | 1: link label spoken twice |
| Elements + templates (9 + 4, theme shell) | M5, M5c | one-time (shell), recurring per part | 11 min + ~1 h 10 min | 2 003/6 + 932/120 = **2 935** | 644 + 540 = **1 184** | 0 | 1: full-width actions; 1 re-scope |
| Elements (Home parts, probe) | M5d, M5e | recurring per part (probe) | ~50 min + 2 h 45 min | 99 + 2 246/73 = **2 345** | 838 | 0 | 3: KC1 guard on fragment data; card collapse; missing selections |
| Hand-written screen (consent) | M5b (part), M8r-3 | recurring per consent screen | inside M5b | 256 (+11/−14 in M8r); 171 lines today | 0 | 0 | 1: rp-identity nodes moved behind `ScreenScope.Node` (M8r) |
| Track C host | M10 | one-time | ~35 min | 320/1 | 0 | 0 | 2: Swift `return`, `NSLog` vararg crash |
| Sync + one-time adoption | M6a, M6c | one-time adoption; the sync itself recurring | ~1 h + ~45 min | 213/895 + 436/81 | 219/96 + 374/16 | **12 698** + **28 554/59** | 2 small loops each (imports, rename script; a first pin) |
| Services adapter | M11b | recurring per flow | ~42 min | 664/9 (adapter 562 + wiring) | 499 | 0 | 0 (2 build-configuration breaks) |
| Drills (verification) | M6b, M8, M8b, M9, M11 | recurring per flow and per release | 1 h 50 + 1 h 15 + 1 h 10 + 1 h 40 + 1 h 55 min ≈ **7 h 50 min** | 0 (tools only) | 0 | M9: 1 835/1 835 (applied and restored) | timing and tooling loops only (swipe tool, adb latency, LMK provocation, a11y trace, Maestro paths) |
| Rework | M8r, M9r, M11c | not planned | 45 + 55 + 70 min ≈ **2 h 50 min** | 120/52 + 190/48 + 28/1 | 204/26 + 2 024/1 121 + 36/0 | 0 | causes: baseline incompleteness and test pins (M8r); test pins against spec content (M9r); upstream intent re-delivery (M11c) |

Totals on the branch (`git diff --numstat b23f6a1f5..04e4ba57c`, net): hand-written main **10 996** (ux-runtime 3 238,
ux-elements 3 928, ux-spike 1 868 incl. the adapter, ux-theme 768, ux-spike-android 243, ux-contract 104, shared-ui 556
(hooks, T2, T3), gate and smoke 238, other pre-spike files 53: T1, T4, T5, T9), test **7 466**; generated `ux-spec/` 48 036 lines in 71 files (Kotlin 1 844).
71 commits over about 23.5 hours of wall clock (2026-10-16 19:12 to 2026-10-17 18:41, with owner waits and a night).

## 9. Cost model (OR-6)

Assumptions, for every row below: the runtime, the interpreter, the theme shell, the accessibility bridge, the
exporter and the gates exist (one-time, paid); agent time is wall time of one agent (Claude Opus or Sonnet) on this
machine, gates included; the full gate takes 5 to 21 minutes; owner device time and app-team review are not included;
lines are hand-written lines in the app repo unless stated.

### 9.1 (a) A new flow

| Item | Lines (main / test) | Agent time | Basis |
|---|---|---|---|
| Spec work (flow, screens, services, rules, copy) in `ux/` | not measured in this repo | not measured (week 0 mixed it with the exporter) | gap: measure on the next flow |
| Services adapter | 300–600 / 300–500 (≈110 main and ≈95 test per service) | 40 min–2 h | M11b: 5 services, 562 / 468 |
| Entry and exit wiring | 30–100 | 15–30 min | M11b wiring +87/−5; T3 redirect +18 |
| Consent screen (if the flow has one) | 170–260 / 0–50 | 30–60 min | `PresRequestScreen` 256 → 171 |
| New parts (0–n) | n × (115–280 main + 8–39 registry + 30–90 test) | n × 15–40 min | M5, M5e |
| Sync | 0 | 15–25 min | D8 attempt 2 |
| Drills, iOS and Android, formal | 0 (tools ≈ 50–100) | 3–4 h | M6b 1 h 50, M11 1 h 55 |
| Rework reserve | — | +25–40 % | M8r, M9r, M11c ≈ 2 h 50 against ≈ 8 h of build work |

Range: **a flow made of existing parts with no consent screen ≈ 0.4–0.8 k lines and 4–7 h**; **a flow with a consent
screen and 3–6 new parts ≈ 1.5–3.5 k lines and 1–2 agent-days**, plus the owner's device walks and the app-team
contract sign-off. Risk not in the range: a flow that needs a new flow-language feature (a new invoke kind, an entry
type) changes the runtime (M3/M4-sized work, 1–2 k lines).

### 9.2 (b) A new element

| Item | Lines | Agent time |
|---|---|---|
| The part (variants, token reads, semantics) | 115–280 main (document-card with 4 variants: 504) | 15–40 min |
| Registry adapter and schema | 8–39 | inside |
| Tests (shared scenario, both targets) | 30–90 | inside |
| `comp.*` tokens: exporter row + shell field | exporter side, not measured; 0 in the app (read by key) | — |
| Icons and art | 0 lines if the asset pipeline binds them (M6c); otherwise a finding | — |

Basis: M5 (≈115 main per simple part), M5e (≈220 main per Home part, 24 registry, 49 test). Assumption: the element is
in the library YAML with tokens; a new layout primitive or a platform limit (heading levels, link trait, iOS modal) is
extra.

### 9.3 (c) A UI-only change

Copy, brand, token values in mapped fields, composition of existing parts, rules, ui states, an added state that uses
existing parts and existing result types: **0 hand-written app lines** (D8 attempt 2, D9). Spec patch 1–61 lines. Agent
time **15–25 min** per change: spec gates 5–8 min, export under 1 min, app full gate 5–14 min (up to 21 min with
`regen-smoke`), a simulator check 2–9 min. It still ships as an app PR, CI run and store release (the bundle is compiled
in). Not UI-only: a change to the consent screen's structure (hand edit), a new result type (adapter mapping; the fake
declares it from the generated table), a new element, prop or variant (9.2).

### 9.4 (d) The dashboard, re-derived

Inputs: M5d's gap list for `FLW-dashboard` (19 screens, 7 templates, 18 elements; 17 parts missing at M5d), M5e's
per-part cost, M11b's adapter cost.

| Item | Lines (main + registry + test) | Agent time | Assumption |
|---|---|---|---|
| 6 parts already built in M5e (TPL-home, document-card, status-chip, bottom-bar, segmented-switch, trust-mark) | 0 more | 0 | done in the spike (probe) |
| 11 remaining parts: 5 templates (dialog, list, task, detail, static) and 6 elements (list-item, dialog, action-menu, action-item, text-input, snackbar) | 11 × (115–280 + 8–39 + 30–90) ≈ **1.7–4.5 k** | 11 × 15–40 min ≈ **3–7 h** | M5 low end, M5e high end; text-input and the dialog may be heavier (focus, IME) |
| Services adapter(s) for the dashboard's flows | 8–15 services × (≈110 main + ≈95 test) ≈ **1.6–3.1 k** | **1.5–4 h** | M11b per-service rate; the service count is an assumption (the dashboard flow is not executable yet) |
| Entries, exits, wiring | 0.1–0.3 k | 0.5–1 h | M11b |
| Drills on both platforms | 0 | 4–8 h | M6b + M11, scaled for 19 screens |
| Rework reserve | +25–40 % | +25–40 % | spike rework share |

Total app side: **≈ 3.4–7.9 k lines and ≈ 9–20 h of agent time (≈ 1.5–3 agent-days)**, plus the owner's device walks.
Not included and larger than all of the above: making `FLW-dashboard` executable in the spec (173 states, back,
restore, services and the model check), the typed-error and contract changes the M11b findings call for, and the app
team's review. M5d's earlier figure (about 4–5 k lines for 17 parts) falls inside this range once the six built parts are
taken out and the adapter is added.

## 10. Integrity issues (M12a, all listed)

1. **Agents ruled on kill criteria** (KC1: M8r verdict B; KC4: RULING-M9r-1; KC2: RULING-W6) although SPIKE-PLAN §0.5
   forbids it. The owner must confirm each (Q-KC-CONFIRM).
2. **D7-Android and D2-LMK were reported as "passed"** in milestone notes although they rest on weaker checks (emulator
   events without speech; root `kill -9` instead of a real low-memory kill). Fixed in M12: the rows now read
   "unresolved (weaker than brief)" and "LMK approximation (root kill -9 / memory-factor), not a real LMK".
3. **Four D2 after-fix rows in `spike-results.md` had an extra column** (a `<a|b>` in a cell split the table, so the
   device landed in the status column). Fixed in M12.
4. **Workarounds that weaken checks:** E12 accepted red twice (RULING-M8-3, RULING-M8b-3); the D8-offline event renamed
   to `SYS_NETWORK_LOST` after it collided with E12; spec patches committed on a detached HEAD so the lock says
   `ux_dirty: false` (RULING-M8b-2); tests skip when a spec feature is absent (RULING-M9r-6), which can hide regressions.
5. **DEC-069…082 are agent defaults** ("owner ruling pending"); DEC-076 is a waiver and needs the owner first (Q-DEC).
6. **Stale state:** Q-002 and Q-006 open though superseded; duplicate "not started" M11/M12 rows in the milestone table.
   Fixed in M12.

Also stated here: D8 attempt 2 was not blind (the patches and causes were known, and module tests were run once on
attempt-1 exports during M8r); the attempt-2 logo image was never rendered (the spec's example URL serves HTML), so
"hidden from accessibility" is inferred from the audit tree and the layout shift; T2, T3 and T4 exceed their planned line
counts.

## 11. Deviations from the brief, and the owner rulings

| Brief | What happened | Basis |
|---|---|---|
| §2: the spike branch in the app team's repo | never pushed (push needs the app team's consent; their CI) | brief §2, run rule |
| §3: dashboard out of scope | a Home-screen probe was drawn (fragments, then the exporter's probe and six new parts); not counted | OR-1, OR-8 |
| §3: fake engine in place of the interactors | kept for the drills; a real adapter probe was added | OR-5 (M11b) |
| §6.4: touch list approved at the plan review | never approved; widened by one file (T9) | RULING-W6; Q-TOUCH |
| §6.5: quality bar on every commit | breached (§6) | — |
| §7 D2: a real low-memory kill | emulator approximation only | RULING-M11-3; Q-D2-LMK |
| §7 D7: real devices | VoiceOver and TalkBack walks not done; emulator events only | Q-D7-iOS, Q-D7-Android |
| §7 D8: one run per mutation | run twice: attempt 1 failed on harness and an element gap; attempt 2 from a new baseline, not blind, plus one blind drill | M8r verdict B (agent), RULING-M8r-6 |
| §7 D9: Android and iOS | iOS simulator only; numstat is platform-independent | — |
| Order | Android moved to the end, after iOS | OR-4 |
| Functional questions | decided by best-practice default and logged, not escalated | OR-7 |
| Styling | style guard, fail-loud schemas, a bigger token shell | OR-2 |
| Week 0 inputs | a PROVISIONAL bundle and boundary first (M1b), then the real export (M6a), then exporter 0.5.0 (M6c) | RULING-1, RULING-W4 |
| Tests | spike rule T1 and the `regen-smoke` gate step added | RULING-M9r-1 (agent) |

Owner rulings: OR-1 (Home probe), OR-2 (styling hardening), OR-3 (fake engine does not count; a real-adapter compile
break is a reported cost), OR-4 (Android last), OR-5 (real adapter probe), OR-6 (phase metrics and this cost model),
OR-7 (optional items arrive unticked; functional questions by default), OR-8 (complete Home probe). Orchestrator and
agent rulings with effect on the verdict: RULING-W2 … W6, M8r (B), RULING-M9r-1, RULING-M11b-0 (SPIKE-LOG "Rulings").

## 12. Open decisions

| Decision | Who | Where |
|---|---|---|
| Confirm or overrule the agent rulings on KC1 (M8r B), KC4 (RULING-M9r-1), KC2 (RULING-W6) | owner team | Q-KC-CONFIRM |
| Approve the touch list T1–T5, T9 | owner team | Q-TOUCH |
| Rule DEC-069…082, DEC-076 (waiver) first | owner team | Q-DEC |
| KC5 estimate, and the runtime-versus-compile-time question | Martin | Q-KC5 |
| Push consent for `spike/ux-runtime` (or a private mirror) | app team | brief §2 |
| A verification-code source, typed `Expired`, `transactionData` in the contract (F-M11b-2/3/4) | owner team + app team | adapter report |
| Q-004 (a throwing service, RULING-2 default) and Q-005 (L58 guard) | owner team / week 0 | HUMAN-QUEUE |
| Fix F-D2-1 upstream | app team | SPIKE-LOG M11c notes |
| The go/no-go meeting once the rows exist | owner team + app team | brief §9 |

The DEC draft for the outcome is `DEC-draft.md` (outcome "Incomplete: awaiting evidence", with the conditional outcomes).
