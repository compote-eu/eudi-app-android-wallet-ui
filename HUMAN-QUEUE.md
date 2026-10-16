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

## Later (queued when their milestone arrives)

- D7-iOS: VoiceOver walk on a real iPhone (M7).
- D10 runs 1–5: iPhone with iOS 26.2+, team signing, hosting the HTTPS `org-iso-mdoc` page (M10).
- M11: an Android emulator or device; the D2 real low-memory kill; D7-Android TalkBack on a real device.
- KC5: the app team's estimate (Martin).
