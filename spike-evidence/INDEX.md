# Evidence index: `spike/ux-runtime`

One line per path under `spike-evidence/` (branch `spike/ux-runtime`, code tree `04e4ba57c`). Rows in
`spike-results.md` cite these paths; milestone notes are in `SPIKE-LOG.md`.

| Path | What it holds |
|---|---|
| `M0-baseline.md`, `M0-baseline-launch.png` | M0: baseline gate on `b23f6a1f5` before any spike change, and the unchanged app's launch screen |
| `boundary/FLW-pres-cross-device.bundle.PROVISIONAL.json` | M1b: the PROVISIONAL flow bundle used before the first real export (sha256 `31bfda65…cf8d71`) |
| `M2/` | M2: switch on/off screenshots, deep link through the unlock gate to the stub (D4-iOS-preliminary, P1/P3) |
| `M4/` | M4: D1-iOS-preliminary (not run, tool limit), D3-sim-preliminary, D-switch-1 screenshots and logs, Maestro flows |
| `M5/` | M5: template gallery screenshots (sheet, page, progress, outcome, scan), D7-semantics-preliminary |
| `M5b/` | M5b: interpreter drills on the PROVISIONAL bundle (D1, D6 happy/expired), the added-state TEST bundle and diff, drill helpers |
| `M5c/` | M5c: styling hardening: exporter requirements, guard mutation logs, snapshot tools |
| `M5d/` | M5d: Home-screen probe from fragment bundles (superseded by M5e): coverage table, 8 probe shots, generator |
| `M5e/` | M5e: Home parts: 56 probe shots + parts gallery, spec-renderer references, coverage and exporter findings, tools |
| `M6a/` | M6a: first real export: API and shape diffs, KC1-guard and uxspec-lock mutation logs, the added-state TEST fixture |
| `M6b/` | M6b: formal iOS drills D1, D3-sim, D4, D5, D6 on the real export: videos, shots, logs, `d3-run.sh` |
| `M6c/` | M6c: exporter 0.5.0 sync: conformance-test proof (red then green), pin-fix rehearsal, asset shots |
| `M7/` | M7: scripted XCUITest accessibility audits (request, verification-code, sending, success) and the a11y log |
| `M9/` | M9 (D9, track B): baseline, brand option, sheet → page for both brands: spec patches, numstat, gate logs, shots, videos |
| `M9r/` | M9r: test-pin sweep and `regen-smoke`: re-exports of the D8 patches on exporter 0.5.0, gate and smoke logs, shots |
| `M10/` | M10 (track C): in-app DC-API harness shots, videos, D10 log excerpt, HTTPS test page for the device runs |
| `M11-prep/` | M11: emulator `spike_pixel` setup notes, boot shot, kill-variant and TalkBack notes |
| `M11/` | M11/M11c: formal Android drills D1, D2 (am kill, LMK approximation, before and after the T9 guard), D4, D6, D7 (events): videos, shots, logs, helpers, release-isolation logs |
| `M11b/` | M11b: real Services adapter probe: `adapter-report.md`, gate log, link and release-isolation log |
| `M12/` | M12: full gate at HEAD, release isolation on both platforms (script and log), D2-Android `am kill` re-run at HEAD for both task roots (`d2/`) |
| `D8/` | M8: D8 attempt 1 (logo, offline, brand, copy): spec patches, exports, diffstats, red gate logs, shots, videos; README |
| `D8-attempt2/` | M8b: D8 attempt 2 (four known drills, not blind) and D8-unseen (blind): patches, spec-gate logs, exports, numstat, gate logs, asserts, shots, videos; baseline runs of the old app |
| `device/` | Owner device runs on the iPhone Air: DEVICE-TEST-1/2 checklists and results log; DEVICE-TEST-3-VOICEOVER checklist for Q-D7-iOS (HEAD build) |
| `tools/` | Reusable drill tools: iOS edge-swipe XCUITest runner, iOS accessibility audit, README |
