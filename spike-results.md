# Spike results: `spike/ux-runtime`

One row per drill run (brief §7). Status is `passed`, `failed` or `not run` (with the reason in Result). Rows are split
by platform, mutation, option and run. Preliminary rows (`*-preliminary`, `*-engine`, `*-provisional`) are appended
under the drill they support and never count toward the verdict (SPIKE-PLAN §0.5, §3). Evidence paths are relative to
`spike-evidence/`.

| # | Date | Drill | Command | Device/Emulator | Status (passed/failed/not run) | Result | Evidence |
|---|---|---|---|---|---|---|---|
| D1-iOS | — | Back (swipe) in `request`, `auth`, `sending` | — | — | not run | pending | — |
| D1-Android | — | Back (system + predictive) in `request`, `auth`, `sending` | — | — | not run | pending | — |
| D2-Android-amkill | — | Process death (`am kill`) in `request`, `auth`, `sending` | — | — | not run | pending | — |
| D2-Android-lmk | — | Process death (real low-memory kill) in `request`, `auth`, `sending` | — | — | not run | pending | — |
| D3-sim | — | iOS cold start after a kill (simulator) | — | — | not run | pending | — |
| D3-device | — | iOS cold start after a kill (real iPhone) | — | — | not run | pending | — |
| D4-iOS | — | Deep-link cold start `openid4vp://` through the unlock gate | — | — | not run | pending | — |
| D4-Android | — | Deep-link cold start `openid4vp://` through the unlock gate | — | — | not run | pending | — |
| D4-iOS-preliminary | 2026-10-16 | M2: cold start via `simctl openurl openid4vp://?client_id=spike&request_uri=https://example.invalid/r`, through the unlock gate, switch on (stub) and off (normal path) | `xcrun simctl launch … --ux-spike happy` (persists), terminate, `openurl`, Maestro `.maestro/ux-spike/m2-open-and-unlock.yaml` (system dialog Open + PIN); off: `--ux-spike off` then the same | iPhone 17, iOS 27.0, sim 3C45E6C6 | preliminary (does not count) | On: after the unlock gate the link lands on the UxFlowRoute stub (`UX-SPIKE stub (FLW-pres-cross-device, deeplink) ux-contract-resource-ok`). Off: the same link takes the normal presentation path (real request flow, error screen for the invalid host). The link was consumed once in each run; `connecting` is not modelled yet. | M2/05-on-deeplink-stub.png, M2/06-off-deeplink-normal-path.png, M2/04-on-deeplink-system-open-dialog.png, M2/01-03 |
| D5 | — | Model check: fails on pre-fix spec (L58), passes on fixed spec (+ Kotlin engine on host and iOS) | — | — | not run | pending | — |
| D6-iOS | — | Happy + expired path, drawn from the bundle except SCR-pres-request | — | — | not run | pending | — |
| D6-Android | — | Happy + expired path, drawn from the bundle except SCR-pres-request | — | — | not run | pending | — |
| D7-iOS | — | VoiceOver on a real iPhone, `request` → `success` | — | — | not run | pending | — |
| D7-Android | — | TalkBack on a real Android device, `request` → `success` | — | — | not run | pending | — |
| D8-logo | — | Regeneration: add `/verifier/logo_url` | — | — | not run | pending | — |
| D8-offline | — | Regeneration: add an `offline-loading` state | — | — | not run | pending | — |
| D8-brand | — | Regeneration: switch the brand | — | — | not run | pending | — |
| D8-copy | — | Regeneration: reword copy | — | — | not run | pending | — |
| D9-brand | — | Track B: eudiw_v1 ↔ idsk (lines: Android = common+android, iOS = common+ios; ≤ 150 each) | — | — | not run | pending | — |
| D9-sheet-page | — | Track B: `decision-steps` sheet → page (same counting) | — | — | not run | pending | — |
| D10-run1 | — | Track C: request sub-flow in the iOS extension host (memory, start time) | — | — | not run | pending | — |
| D10-run2 | — | Track C, run 2 | — | — | not run | pending | — |
| D10-run3 | — | Track C, run 3 | — | — | not run | pending | — |
| D10-run4 | — | Track C, run 4 | — | — | not run | pending | — |
| D10-run5 | — | Track C, run 5 | — | — | not run | pending | — |
