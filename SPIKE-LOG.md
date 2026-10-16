# Spike log: `spike/ux-runtime`

Base: `origin/feature/kmp` @ `b23f6a1f5`. Design: `SPIKE-PLAN.md`. Boundary: `spike-contract-boundary.md`. Drill
rows: `spike-results.md`. Owner steps: `HUMAN-QUEUE.md`.

## Milestones

| M | Status | Started | Finished | Agent (model) | Commits | Notes |
|---|---|---|---|---|---|---|
| M0 | in progress | 2026-10-16 | — | Haiku/Sonnet | — | Android SDK, Maestro, baseline gate (Android + lint green; iOS tests failed before Xcode). Xcode 27.1 now installed; iOS runtime and `M0-baseline-launch.png` in progress. See `spike-evidence/M0-baseline.md`. |
| M1a | done | 2026-10-16 | 2026-10-16 | Opus | see Commits | plan + state files; RULING-1 fold-in |
| M1b | done | 2026-10-16 | 2026-10-16 | Opus | see Commits | `spike-contract-boundary.md` + PROVISIONAL bundle (sha256 `31bfda65…cf8d71`) |
| M2 | not started | — | — | — | — | ⟲ at end |
| M3 | not started | — | — | — | — | — |
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

## Gate results

Counts from `*/build/test-results/**/*.xml`. A task with 0 executed tests where tests exist is red.

| Date | SHA | Milestone | Task | Executed | Failed | Skipped | detekt | ktlint | xcodebuild | Result |
|---|---|---|---|---|---|---|---|---|---|---|
| 2026-10-16 | b23f6a1f5 | M0 baseline | see `spike-evidence/M0-baseline.md` | 560 (shared-ui host), 166 (shared-logic host), Android unit tests per module | 0 | 0 | pass | pass | not run (no Xcode then) | Android/lint green; iOS tests pending |

## Commits

| SHA | Date | Milestone | Subject | Co-Authored-By (model) | Gate |
|---|---|---|---|---|---|
