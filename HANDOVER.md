# Handover: KMP UI-from-spec spike

Index for the app team and the owner team, 2026-10-18. Start with `DECISION-BRIEF.md`. Nothing has been pushed.

## Branches

| Branch | Where | What |
|---|---|---|
| `spike/ux-runtime` | `/Users/roman/code/eudi-kmp-spike` (repo `compote-eu/eudi-app-android-wallet-ui`, base `feature/kmp` @ `b23f6a1f5`) | option A: runtime, interpreter, elements, theme, generated contract, Services adapter probe; code tree `04e4ba57c` |
| `spike/reskin-probe` | `/Users/roman/code/eudi-kmp-reskin` (cut from `spike/ux-runtime` @ `599e360ec`, head `7f2d65b5c`) | option B: Home and the consent screen on generated elements; reskin commit `6e613720c` |
| `spike-week0` | `/Users/roman/code/dvm-week0` (analysis repo, head `b55be65`) | spec changes and the exporter (`tools/export_kmp.py`, 0.5.0); the export the spike locks to (`ux-spec/UX_SPEC.lock`) |

The device build used on 2026-10-18 is a detached worktree, `/Users/roman/code/eudi-kmp-device` at `f9c8bbc12`.

## Reports

| File | Read for |
|---|---|
| `DECISION-BRIEF.md` | the A / B / hybrid decision, costs, recommendation, next weeks |
| `spike-report.md` | verdict (Incomplete), kill criteria, constraint audit, cost model (§9), lessons |
| `spike-results.md` | one row per drill run |
| `/Users/roman/code/eudi-kmp-reskin/RESKIN-PROBE.md` | option B: component mapping, lines, regeneration matrix, whole-app estimate |
| `DEC-draft.md` | the DEC record to file in the analysis repo |
| `spike-evidence/M11b/adapter-report.md` | why the real interactors fit the Services contract badly |
| `SPIKE-LOG.md`, `SPIKE-PLAN.md`, `spike-contract-boundary.md` | run log and rulings, plan, the spec-to-app boundary |
| `HUMAN-QUEUE.md` | what still needs a person |

Copies (uncommitted) are in `dvm-edoklady-eudiw-analysis/ux/evaluation/kmp-ui-from-spec/`.

## Evidence

- `spike-evidence/INDEX.md`: one line per folder (`M2` … `M12`, `D8`, `D8-attempt2`, `device`, `tools`).
- `spike-evidence/device/`: owner device checklists and the raw log (`device-test-1-results.log`).
  `D7-iOS-voiceover-HEAD.mp4` (96 MB) is **untracked, not in git**: it exists only in this worktree.
- `/Users/roman/code/eudi-kmp-reskin/spike-evidence/reskin/`: before/after shots for both brands, regen patch and export, logs.

## Build and run the spike (debug builds only)

- **Gate:** `./spike-gate.sh` (full, incl. Release build and `regen-smoke`, 5–21 min), `--fast`, `--no-regen`.
- **iOS:** `./gradlew generateIosProject`, then
  `xcodebuild -project iosApp/iosApp.xcodeproj -scheme EudiWallet -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17,OS=27.0' -derivedDataPath DerivedData build`.
  The switch is a launch argument: `xcrun simctl launch <udid> eu.compote.euidi.dev --ux-spike <value>`; it is stored in
  the app-group defaults (the DC-API extension reads it there); `--ux-spike off` clears it.
- **Android:** `./gradlew :androidApp:assembleDevDebug`; the switch is a file:
  `adb shell "run-as eu.compote.euidi.dev sh -c 'mkdir -p files; echo slow > files/ux-spike.on'"` (delete it for off).
- **Start a flow:** unlock, then open `openid4vp://?client_id=spike&request_uri=https://example.invalid/r`.
- **Switch values:** fake-engine scripts `happy`, `slow`, `expired-at-request`, `expired-at-sending`, `untrusted`,
  `doc-missing` (`ux-spec/fixtures/scripts/`); `real` (the M11b adapter on the real interactors; never run against a
  verifier); `dcapi-harness` (iOS, the extension sub-flow inside the app).
- **Gallery entries:** `gallery` (sheet), `gallery-page`, `gallery-progress`, `gallery-outcome`, `gallery-scan`,
  `gallery-parts`; the Home probe `gallery-home[-<ui state>][.<brand>][.<mode>]`.
- **Option B:** on `spike/reskin-probe` the reskin is always on (no switch); `--ux-spike reskin-request[-untrusted]`
  plus a link opens the real consent screen over a canned interactor (`spike-evidence/reskin/sim.sh`).
- **Drill tools:** `spike-evidence/M6b/drill-helpers.sh`, `spike-evidence/tools/` (iOS edge swipe, a11y audit), Maestro
  flows in `.maestro/ux-spike/`; a spec sync is `spike-regen-smoke.sh` plus the lock check in the gate.

## Open items

| Item | Who | Queue |
|---|---|---|
| Real low-memory kill on an Android phone (KC2) | owner team | Q-D2-LMK |
| TalkBack walk on a real Android phone (verdict precondition) | owner team | Q-D7-Android |
| KC5 estimate, runtime vs compile-time | Martin | Q-KC5 |
| Touch list T1–T5, T9 | owner team | Q-TOUCH |
| Agent rulings on KC1, KC2, KC4 | owner team | Q-KC-CONFIRM |
| DEC-069…082 (DEC-076 first) | owner team | Q-DEC |
| DC-API extension: jetsam on warm reuse (220 MB), footprint unmeasured: declare an exception or fix | owner team | spike-report §12 |
| Services contract gaps (verification code, typed `Expired`, `transactionData`); F-D2-1 upstream | app team + owner | adapter report |
| A / B / hybrid decision; push consent for both spike branches | owner team + app team | `DECISION-BRIEF.md` §4 |
