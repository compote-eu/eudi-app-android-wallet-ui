# Human queue: `spike/ux-runtime`

Steps that need the owner (devices, installs, decisions). Each entry says what blocks on it. Mark an entry done by
adding the date and the result under it.

## Q-001: Install Xcode 26.x with an iOS 26 simulator runtime

- **Blocked:** every iOS build and drill.
- **Steps:** install Xcode, then
  `sudo xcode-select -s /Applications/Xcode.app && sudo xcodebuild -runFirstLaunch`.
- **Status:** **done (2026-10-16).** Xcode 27.1 is installed, and `xcode-select -p` prints
  `/Applications/Xcode.app/Contents/Developer`. An agent is installing the iOS simulator runtime; M0 records it.

## Q-002: Forward the interface boundary to week 0, and rule on D8 edge cases

- **Blocks:** M6 (`ux-spec/` sync); KC1 interpretation in M8.
- **Steps:**
  1. Forward `spike-contract-boundary.md` and `spike-evidence/boundary/FLW-pres-cross-device.bundle.PROVISIONAL.json` to the
     week-0 session. Its evidence record is **REV-013** (REV-012 is taken).
  2. Week 0 accepts or amends the 17 defaults in boundary §7. Q14 matters most: the fake-engine scripts come out of the
     exporter under `ux-spec/fixtures/scripts/`.
  3. Rule now, in advance: if a D8 mutation grows the generated `Services` interface and the fake engine fails to compile,
     is that (a) a D8 failure (the default: KC1 failed) or (b) an expected seam break that is exempt? Without a ruling,
     (a) applies.
- **Status:** **closed (superseded, M12, 2026-10-17).** Step 1-2: the boundary went to week 0 and the real export
  replaced the PROVISIONAL bundle (M6a sync `7a24efacf`, 8 divergences resolved app-side). Step 3: ruled by OR-3 (the fake
  engine is data-driven and does not count; a real-adapter compile break is a reported cost, per D8 row).

## Q-003: D3 on a real iPhone (iOS cold start after a kill)

- **Blocks:** row `D3-device` (KC2 evidence).
- **When:** now. **Build:** the device build at the M12 HEAD (code tree `04e4ba57c`; see SPIKE-LOG M12 for the install status).
- **Steps (outline):**
  1. Install the Debug build on the iPhone and launch it with `--ux-spike happy`.
  2. Reach `request`, then kill the app with `xcrun devicectl device process terminate --device <udid> --pid <pid>`
     (`devicectl device info processes` gives the pid). Relaunch from the home screen.
  3. Expected: unlock, then Home. No flow is restored.
  4. Repeat for `auth` and `sending`. For `sending`, launch with `--ux-spike slow`.
  5. Record a screen recording of each run.
- **Status:** **closed (done 2026-10-18), passed.** Owner on the iPhone Air, HEAD build (`f9c8bbc12`, code tree `04e4ba57c`): killed in `request` from the app switcher, relaunched from the icon → unlock, Home, nothing restored; a fresh cold deep link starts at `connecting`. Only `request` was killed on the device (steps 4–5 not done; `auth` and `sending` rest on D3-sim). Row `D3-device`.

## Q-004: Confirm RULING-2 (a service that throws) for the real export

- **Blocks:** nothing now (the default is implemented); M6 re-checks the real bundle against it.
- **Question (week 0 / owner):** what does a service call that throws mean in the flow language?
- **Default (RULING-2, implemented in M4):** the engine converts a throw, or a coroutine failure other than
  cancellation, into the service's `Error` result with payload `{class: unexpected}`, and the state follows its
  declared error branch (`invoke.on.Error` → an event of the state). A state-scoped invoke without that branch, or a
  service without an `Error` result, refuses the bundle at load. Detached invokes are exempt.
- **Options:** (a) keep the default; (b) a separate result name (e.g. `Unexpected`) that every service must declare;
  (c) a flow-level `on_failure` target instead of a per-state branch.
- **Status:** open (default applies)

## Q-005: L58 guard on `CONTINUE` in `request` and `request-untrusted` (M3 open issue 1)

- **Blocks:** the D5 property's caveat; nothing in M4.
- **Question (week 0 / owner):** the fixed bundle reaches `auth` with `selected_count == 0` if `RequestReady` or
  `RpUntrusted` carries only optional items, because `CONTINUE` is unguarded in `request` and `request-untrusted`.
- **Default:** the service contract guarantees at least one mandatory item for `RequestReady` and `RpUntrusted`
  (the fake scripts honour it), and the spec stays as it is.
- **Options:** (a) the default (a contract guarantee); (b) move the `selected_count > 0` guard onto `CONTINUE` in
  `request` and `request-untrusted` as well (spec change, re-export).
- **Status:** open (default applies)

## Q-006: An edge swipe the simulator drills can use (D1-iOS)

- **Blocks:** row `D1-iOS` (KC2 evidence, M6); `D1-iOS-preliminary` is recorded as not run.
- **Why:** Maestro 2.11's synthesized swipe from the left edge triggers no back on this simulator, not even on the
  wallet's own pushed screens (`spike-evidence/M4/base-0{3,4,5}-*.png`).
- **Options:** (a) the owner swipes by hand in Simulator.app while the agent records (`simctl io recordVideo`) and reads
  the `UX-SPIKE:` log; (b) allow installing `idb` (`brew install facebook/fb/idb-companion` + `pip install fb-idb`) for
  `idb ui swipe`; (c) run D1-iOS on the real iPhone together with Q-003.
- **Status:** **closed (superseded, M12, 2026-10-17).** D1-iOS passed formally in M6b with the XCUITest edge-swipe tool
  (`spike-evidence/tools/ios-edge-swipe.sh`, press at x=0 then drag, `-t` tap-then-swipe mode), and the owner swiped by hand on
  the iPhone (D1-iOS-device rows, preliminary). No `idb` install needed.

## Q-D7-iOS: VoiceOver walk `request` → `success` on the iPhone (D7-iOS, KC3) — REQUIRED

- **Blocks:** row `D7-iOS`, so the KC3 verdict and the whole spike verdict. Automated evidence so far: `D7-iOS-preliminary`.
- **Build (updated M12):** the device build at the spike HEAD of M12 (code tree `04e4ba57c`; M12 commits are docs and evidence
  only), built from `/Users/roman/code/eudi-kmp-device` (detached at the M12 HEAD). Not the M7 build, not `93c2b9931`. Install
  status: SPIKE-LOG M12 ("build ready, install pending" until the phone is connected).
- **Checklist:** `spike-evidence/device/DEVICE-TEST-3-VOICEOVER.md` (steps, expected speech and focus per state, the result
  table to fill in). Use script `slow` for steps 1–6, then one `happy` pass. Verification code is `482 913`. Send the screen
  recording with sound.
- **Pass (SPIKE-PLAN §3 D7):** after each state change focus is on the new heading (request, auth, verification-code, success),
  `sending` is announced once, `success` is announced, and no focus remains on a node of the old state.
- **If it fails:** KC3 is hit only if the runtime cannot make it right (a fix outside the runtime or the elements). A failure
  means Fall back (M12a conditional verdict).
- **Status:** **closed (done 2026-10-18), passed.** Scripts `slow` and `happy` on the HEAD build: focus on the new heading after request, auth, verification-code and success; `sending` announced once; `success` announced; no old-screen node reachable. KC3 not hit. Recording `spike-evidence/device/D7-iOS-voiceover-HEAD.mp4` (96 MB, untracked, not in git). Row `D7-iOS`.

## Q-D10: Request sub-flow inside the DC API extension, 5 runs on the iPhone (D10, track C)

- **Blocks:** row `D10` (Track C verdict). Not run means Incomplete; run and failed means a declared exception (SPIKE-PLAN R7).
  The simulator harness (`--ux-spike dcapi-harness`, `spike-evidence/M10/`) is preliminary evidence only.
- **Build (updated M12):** the device build at the spike HEAD of M12 (code tree `04e4ba57c`), Debug, from
  `/Users/roman/code/eudi-kmp-device` (the orchestrator installs it; iPhone, iOS 27.0.1, team D2YJRYD9TA). The VoiceOver
  checklist `spike-evidence/device/DEVICE-TEST-3-VOICEOVER.md` uses the same build, so both walks can run in one session. The flag is **on** when the app-group default `ux-spike` holds a script name.
- **Steps (owner):**
  1. The orchestrator launches the app once with `--ux-spike happy` (for example
     `xcrun devicectl device process launch --device <id> --terminate-existing eu.compote.euidi.dev --ux-spike happy`).
     The argument is persisted to the app-group defaults, which the extension reads. Unlock once, then close the app.
  2. Host `spike-evidence/M10/dcapi-test-page/index.html` over HTTPS (see its `README.md`) and open the URL in Safari on the iPhone.
  3. On the Mac, start the log before each series: Console.app, select the iPhone, search `UX-SPIKE-D10`, Start streaming
     (or after the runs: `sudo log collect --device --last 10m --output d10.logarchive`, then
     `log show --archive d10.logarchive --predicate 'eventMessage CONTAINS "UX-SPIKE-D10"'`).
  4. Run 5 times. Each run: kill Safari's tab or reload the page, tap **Request PID**, choose the wallet in the system sheet,
     and in the extension tap Continue (request), wait for the verification code screen, tap Confirm, wait for the outcome, tap Close.
     Between runs wait 10 s so the extension process is cold (check that `created` appears again).
  5. Note per run: did the sub-flow render (y/n), did it complete (y/n), anything odd (blank frame, jetsam kill, system sheet
     closed early). Safari shows `ERROR ...` on the page after a refusal; a refusal (`NotAllowedError`) is the expected result here:
     the fake engine returns no data (`onDecision(null)`).
- **Log lines** (tag `UX-SPIKE-D10`, `host=extension`):
  `created`, `first-frame start_to_first_frame_ms=<ms> footprint_mb=<MB>`, `complete outcome=<exit> start_to_complete_ms=<ms> footprint_mb=<MB>`.
  Footprint is `task_info` `phys_footprint` of the extension process. Start is the creation of the view controller, not the process launch (`wall_epoch_ms` allows correlation with the system log).
- **Switch off afterwards:** launch the app with `--ux-spike off`.
- **Fill in:**

| Run | Rendered | Completed | start_to_first_frame_ms | footprint at first frame (MB) | footprint at complete (MB) | Notes |
|---|---|---|---|---|---|---|
| 1 | y | y | not logged (proxy: first Compose view 150–171 ms after extension launch, runs 1–5) | not logged | not logged | jetsam per-process-limit kill (220 MB) on warm scene reuse |
| 2 | y | y | as run 1 | not logged | not logged | jetsam kill (220 MB) on warm reuse |
| 3 | y | y | as run 1 | not logged | not logged | jetsam kill (220 MB) on warm reuse |
| 4 | y | y | as run 1 | not logged | not logged | jetsam kill (220 MB) on warm reuse |
| 5 | y | y | as run 1 | not logged | not logged | — |
| baseline (switch off) | y | y | proxy: 636 ms cold | not logged | not logged | reuse never killed |

- **Pass (brief D10):** renders and completes on the device; memory and start time recorded (5 runs).
- **Status:** **closed (done 2026-10-18).** 5 of 5 rendered and completed cold through the runtime in the extension (the verification-code step was seen); Safari via a temporary HTTPS tunnel page, PID issued in the wallet. Start time by system-log proxy only; **no footprint numbers** (the `UX-SPIKE-D10` lines did not reach the unified log). **4 jetsam per-process-limit kills (220 MB) on warm scene reuse in runs 1–4; the baseline's reuse was never killed.** Reading: rendered and completed 5/5 cold, with a memory risk on reuse: a declared-exception candidate, not a kill; the owner declares it or orders a fix (spike-report §12). Rows `D10-run1..5`, `D10-analysis`.

## Q-D2-LMK: real low-memory kill on an Android phone (D2, KC2)

- **Blocks:** the real-kill half of `D2-Android-lmk` (the emulator approximation, `kill -9` under `memory-factor CRITICAL`, passed).
- **Steps:** install the Debug build (`./gradlew :androidApp:assembleDevDebug`, id `eu.compote.euidi.dev`), write the switch
  (`adb shell "run-as eu.compote.euidi.dev sh -c 'mkdir -p files; echo slow > files/ux-spike.on'"`), start the wallet from the
  launcher and finish onboarding, then open `openid4vp://?client_id=spike&request_uri=https://example.invalid/r` (task root must be the
  launcher, so deliver the link while the app is running). In `request`, press Home and open many heavy apps (camera, maps, a game)
  until the wallet is gone from recents' live processes (`adb shell pidof eu.compote.euidi.dev` empty), then reopen the wallet from
  recents. Repeat for `auth` and `sending` (use script `slow`; press Home within 1.5 s / 3 s).
- **Expected (brief §5):** request restores from the snapshot, auth restores to request, sending restores to error with no second send
  (`adb logcat | grep UX-SPIKE`: `restore ... -> ...`, one `send#1`).
- **Status:** open

## Q-D7-Android: TalkBack walk on a real Android phone — REQUIRED (not optional)

- **Blocks:** row `D7-Android`, which is **unresolved (weaker than brief)**: the emulator trace has events but no speech and
  no focused-node text, and the brief (§7 D7) asks for TalkBack on a real Android device. Without it the verdict stays
  Incomplete (M12a). A failure here is not a kill criterion by itself (KC3 names VoiceOver); the owner decides.
- **Steps:** Debug build and switch as above, script `slow`, TalkBack on. Open the link, then go `request` → `auth` →
  `verification-code` → `sending` → `success` by double tap. After each state change listen: focus on the new heading (or the
  announced status for connecting and sending), no sound from the previous screen, no stray focus on the page behind a sheet.
- **Fill in:** per state: heard text, focus on heading yes/no, anything old reachable yes/no.
- **Status:** open

## Q-TOUCH: owner approves the touch list T1–T5, T9 (brief §6.4)

- **Blocks:** constraint §6.4 ("we approve it at the plan review"); never given.
- **Files (6 existing files, cap 8):** T1 `settings.gradle.kts` (+6), T2 `shared-ui/build.gradle.kts` (+18, plan +8–12),
  T3 `SharedEntries.kt` (+18, plan +10–16), T4 `androidApp/build.gradle.kts` (+2, plan +1), T5 `ConsentHost.swift` (+13/−1, plan +10–14,
  `#if DEBUG`), T9 `EudiComponentActivity.kt` (+32/−1, RULING-W6 and RULING-M11b-0; the guard code ships in Release, inert).
  Details: `SPIKE-PLAN.md` §2, `spike-report.md` §6 audit.
- **Answer:** approve / approve with changes / reject (name the file).
- **Status:** open

## Q-KC-CONFIRM: owner confirms the agent rulings on KC1, KC4, KC2

- **Why:** SPIKE-PLAN §0.5 reserves kill-criterion rulings for the owner; three were made by agents.
- **Confirm or overrule each:**
  1. **KC1 — M8r verdict (B):** D8 attempt 1 failed on test pins and an element gap, KC1 not hit; attempt 2 from a new baseline.
  2. **KC4 — RULING-M9r-1:** the D9 red gates were test pins, not a KC4 signal (D9 = (C), both options 0/0 lines).
  3. **KC2 — RULING-W6:** F-D2-1 is app intent re-delivery, not a flow handler; widen the touch list by T9.
- **Status:** open

## Q-DEC: owner rules DEC-069…082 (DEC-076 waiver first)

- **Why:** the week-0 records DEC-069 to DEC-082 (`dvm-week0`, `ux/records/decisions/`) are agent defaults with
  `provisional: true`, "owner ruling pending". DEC-076 is a waiver (it accepts 24 agreement findings in FLW-pres-same-device and
  FLW-dashboard), so it needs the owner before anything else.
- **Order:** DEC-076 first; then DEC-069…075, DEC-077…082.
- **Status:** open

## Q-KC5: the app team's criterion-5 estimate (Martin)

- **Blocks:** KC5; without it the best possible result is "Go with exceptions" (brief §8).
- **Ask:** estimate the cost of moving navigation control out of the view models for `FLW-pres-cross-device` (the runtime
  drives the flow, the app supplies the Services adapter, entry and exits), against the spike's cost (`spike-report.md`,
  cost model). KC5 is hit if it is more than 2× the spike. Note: Martin proposed compile-time generation; the spike chose
  runtime interpretation, so the estimate also says whether he accepts that.
- **Status:** open

## Later

- D10 runs 1–5: Q-D10, closed 2026-10-18. M11: queued as Q-D2-LMK and Q-D7-Android. KC5: Q-KC5.
- Open on 2026-10-18 (M12c): Q-D2-LMK, Q-D7-Android, Q-KC5, Q-TOUCH, Q-KC-CONFIRM, Q-DEC (Q-004, Q-005 open with defaults applied).
