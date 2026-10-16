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

## Later (queued when their milestone arrives)

- D7-iOS: VoiceOver walk on a real iPhone (M7).
- D10 runs 1–5: iPhone with iOS 26.2+, team signing, hosting the HTTPS `org-iso-mdoc` page (M10).
- M11: an Android emulator or device; the D2 real low-memory kill; D7-Android TalkBack on a real device.
- KC5: the app team's estimate (Martin).
