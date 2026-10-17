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
- **Status:** open

## Q-003: D3 on a real iPhone (iOS cold start after a kill)

- **Blocks:** row `D3-device` (KC2 evidence).
- **When:** after M6. The agent posts the exact build and launch steps then.
- **Steps (outline):**
  1. Install the Debug build on the iPhone and launch it with `--ux-spike happy`.
  2. Reach `request`, then kill the app with `xcrun devicectl device process terminate --device <udid> --pid <pid>`
     (`devicectl device info processes` gives the pid). Relaunch from the home screen.
  3. Expected: unlock, then Home. No flow is restored.
  4. Repeat for `auth` and `sending`. For `sending`, launch with `--ux-spike slow`.
  5. Record a screen recording of each run.
- **Status:** open

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
- **Status:** open

## Q-D7-iOS: VoiceOver walk `request` → `success` on the iPhone (D7-iOS, KC3)

- **Blocks:** row `D7-iOS` (KC3 verdict). Automated evidence so far: `D7-iOS-preliminary` in `spike-results.md`.
- **Build:** the M7 commit (see `SPIKE-LOG.md` M7). **A device rebuild is needed**: the phone still runs `e837f53a7`
  (M5b). The orchestrator rebuilds and installs it from the device worktree and launches it with the deep link and
  `--ux-spike slow` (so `auth` lasts 12 s and `sending` 45 s, long enough to hear them).
- **Before:** phone in Light mode. Settings > Accessibility > VoiceOver: on (or triple-click the side button if the
  Accessibility Shortcut is set). Start a screen recording with sound (Control Center > Screen Recording, long-press,
  Microphone on) before the orchestrator launches the app. Use only swipe right/left (next/previous element) and
  double-tap (activate); no direct touch on items, so focus is what the runtime set.
- **Expected texts** are the PROVISIONAL sk copy; the app may show the eudiw_v1 brand variant (e.g. "eDoklady"). VoiceOver
  adds its own role words ("heading", "button", ...); the language of speech is the phone's.

### Steps and what VoiceOver should do after each state change

| # | You do | New state | VoiceOver should say first (without you touching anything) | Focus should be on |
|---|---|---|---|---|
| 1 | unlock with the PIN (VoiceOver: double-tap digits) | `connecting` (4 s) | "Načítava sa žiadosť overovateľa. Overuje sa totožnosť overovateľa a dostupnosť vašich eDokladov." once (an announcement; it waits for current speech) | not moved (wherever VoiceOver was) |
| 2 | wait | `request` (sheet) | "Overenie eDokladu, heading" | the sheet title; swipe right goes to the verifier, privacy link, data, attributes, Continue, Reject. The app bar behind the sheet is never reached |
| 3 | swipe to "Continue", double-tap | `auth` (12 s) | "Overenie, že ste to vy, heading" | the auth title |
| 4 | wait | `verification-code` | "Skontrolujte stránku a prepíšte overovací kód, heading" | the title; swipe right reaches the code 4711 and Confirm |
| 5 | swipe to "Confirm", double-tap | `sending` (45 s) | "Prebieha odoslanie dát eDokladov" exactly **once** | not moved |
| 6 | wait | `success` | "eDoklady boli predložené, heading" | the success title; swipe right reads "Pokračujte na stránke overovateľa vo webovom prehliadači.", then Close |
| 7 | swipe to "Close", double-tap | Home | (wallet Home; not part of D7) | — |

After each change also check: swipe left from the new focus never reaches text of the previous screen (no focus on a node
of the old state), and there is only one heading per screen in the rotor (Rotor > Headings, swipe up/down) apart from
the two attribute group headings on `request`.

### Fill in (one row per step; send the recording)

| # | State | Said first (as heard) | Said once? (y/n) | Focus on (element) | Any old-screen element reachable? (y/n) | Notes (double speech, delay, silence) |
|---|---|---|---|---|---|---|
| 1 | connecting | | | | | |
| 2 | request | | | | | |
| 3 | auth | | | | | |
| 4 | verification-code | | | | | |
| 5 | sending | | | | | |
| 6 | success | | | | | |

Also note on `request`: is a mandatory attribute read as "dimmed"? (It should not be.) Is the privacy link read as
"link" or "button"? (Known gap: Compose Multiplatform 1.12 has no link trait, so "button" is expected.)

- **Pass (SPIKE-PLAN §3 D7):** after each state change focus is on the new heading (2, 3, 4, 6), `sending` is announced
  once (5) and `success` is announced (6), and no focus remains on a node of the old state.
- **Status:** open

## Q-D10: Request sub-flow inside the DC API extension, 5 runs on the iPhone (D10, track C)

- **Blocks:** row `D10` (Track C verdict). Not run means Incomplete; run and failed means a declared exception (SPIKE-PLAN R7).
  The simulator harness (`--ux-spike dcapi-harness`, `spike-evidence/M10/`) is preliminary evidence only.
- **Build:** the M10 commit (see `SPIKE-LOG.md` M10), Debug, from the device worktree (the orchestrator builds and installs it;
  iPhone, iOS 27.0.1, team D2YJRYD9TA). The flag is **on** when the app-group default `ux-spike` holds a script name.
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
| 1 | | | | | | |
| 2 | | | | | | |
| 3 | | | | | | |
| 4 | | | | | | |
| 5 | | | | | | |

- **Pass (brief D10):** renders and completes on the device; memory and start time recorded (5 runs).
- **Status:** open

## Later (queued when their milestone arrives)

- D10 runs 1–5: now queued as Q-D10.
- M11: an Android emulator or device; the D2 real low-memory kill; D7-Android TalkBack on a real device.
- KC5: the app team's estimate (Martin).
